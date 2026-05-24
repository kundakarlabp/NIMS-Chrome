const HELPER = "http://127.0.0.1:8765";

chrome.runtime.onInstalled.addListener(() => {
  if (chrome.sidePanel && chrome.sidePanel.setPanelBehavior) {
    chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});
  }
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === "NIMS_OPEN_PANEL") {
    const tabId = sender.tab && sender.tab.id;
    if (chrome.sidePanel && tabId) {
      chrome.sidePanel.open({ tabId }).catch(() => {});
    }
    sendResponse({ ok: true });
    return false;
  }

  if (message.type === "NIMS_PROGRESS") {
    chrome.storage.local.set({ nimsFastSummaryProgress: message.message });
    sendResponse({ ok: true });
    return false;
  }

  if (message.type === "NIMS_FETCH_REPORT") {
    fetchReportWithSession(message.row, sender).then(sendResponse);
    return true;
  }

  if (message.type === "NIMS_HELPER_HEALTH") {
    callHelper("/health").then(sendResponse);
    return true;
  }

  if (message.type === "NIMS_HELPER_PARSE_REPORT") {
    callHelper("/parse-report", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(message.body || {})
    }).then(sendResponse);
    return true;
  }

  if (message.type === "NIMS_HELPER_SUMMARIZE") {
    callHelper("/summarize", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(message.body || {})
    }).then(sendResponse);
    return true;
  }

  if (message.type === "NIMS_HELPER_CLEAR_CACHE" || message.type === "NIMS_CLEAR_CACHE") {
    callHelper("/clear-cache", { method: "POST" }).then(sendResponse);
    return true;
  }

  return false;
});

async function callHelper(path, options = {}) {
  const url = `${HELPER}${path}`;
  try {
    const response = await fetch(url, options);
    const text = await response.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = text;
    }
    if (!response.ok) return { ok: false, error: `Helper ${path} returned status ${response.status}`, data };
    return { ok: true, data };
  } catch (error) {
    return {
      ok: false,
      error: `Local helper is not reachable at 127.0.0.1:8765. Start the helper and retry. Details: ${error.message}`
    };
  }
}

async function fetchReportWithSession(row, sender) {
  const url = row.source_url || row.href;
  if (!url) {
    if (isSupportedPrintReportRow(row)) {
      return captureReportByClick(row, sender);
    }
    return { ok: false, error: "NIMS onclick/form workflow needs specific mapping" };
  }

  try {
    const response = await fetch(url, { credentials: "include", redirect: "follow" });
    const contentType = response.headers.get("content-type") || "";
    const buffer = await response.arrayBuffer();
    const htmlError = await detectHtmlAuthFailure(buffer, contentType);
    if (htmlError) return { ok: false, error: htmlError, status: response.status, finalUrl: response.url, contentType };
    if (response.ok && buffer.byteLength > 0) {
      return {
        ok: true,
        status: response.status,
        contentType,
        finalUrl: response.url,
        base64: arrayBufferToBase64(buffer)
      };
    }
    const tabResult = await fetchViaTemporaryTab(url);
    return tabResult.ok ? tabResult : { ok: false, error: `Fetch returned ${response.status}` };
  } catch (error) {
    const tabResult = await fetchViaTemporaryTab(url);
    return tabResult.ok ? tabResult : { ok: false, error: error.message };
  }
}

function isSupportedPrintReportRow(row) {
  return Boolean(
    row
    && !row.source_url
    && !row.href
    && row.onclick_function_name === "printReport"
    && Number(row.onclick_arg_count || 0) === 1
  );
}

async function captureReportByClick(row, sender) {
  const tabId = sender && sender.tab && sender.tab.id;
  const frameId = sender && typeof sender.frameId === "number" ? sender.frameId : 0;
  if (!tabId) return { ok: false, error: "Unable to capture NIMS printReport output" };

  let popupTab = null;
  const popupPromise = waitForPopupTab(tabId);
  try {
    await setProgress("Waiting for report popup");
    const clickResult = await chrome.scripting.executeScript({
      target: { tabId, frameIds: [frameId] },
      world: "MAIN",
      func: clickNimsPrintReportRow,
      args: [safeClickLocator(row)]
    });
    const clicked = clickResult && clickResult[0] && clickResult[0].result;
    if (!clicked || clicked.ok === false) {
      return { ok: false, error: (clicked && clicked.error) || "No View Report button found for row" };
    }

    popupTab = await popupPromise;
    if (popupTab && popupTab.id) {
      await setProgress("Capturing report content");
      const captured = await captureLoadedTab(popupTab.id);
      await setProgress("Closing temporary tab");
      await chrome.tabs.remove(popupTab.id).catch(() => {});
      return captured.ok ? captured : { ok: false, error: captured.error || "Report popup opened but content could not be fetched" };
    }

    const sameFrame = await captureSameFrameReport(tabId, frameId);
    if (sameFrame.ok) return sameFrame;
    return { ok: false, error: "printReport did not open a popup/tab" };
  } catch {
    return { ok: false, error: "Unable to capture NIMS printReport output" };
  } finally {
    if (popupTab && popupTab.id) chrome.tabs.remove(popupTab.id).catch(() => {});
  }
}

function safeClickLocator(row) {
  return {
    row_index: Number(row.row_index),
    view_report_button_index: Number(row.view_report_button_index),
    report_name: row.report_name || "",
    date_sent: row.date_sent || "",
    onclick_function_name: row.onclick_function_name || "",
    onclick_arg_count: Number(row.onclick_arg_count || 0)
  };
}

function waitForPopupTab(openerTabId) {
  return new Promise((resolve) => {
    const timeout = setTimeout(done, 6000, null);
    function done(tab) {
      clearTimeout(timeout);
      chrome.tabs.onCreated.removeListener(listener);
      resolve(tab || null);
    }
    function listener(tab) {
      if (tab && tab.openerTabId === openerTabId) done(tab);
    }
    chrome.tabs.onCreated.addListener(listener);
  });
}

async function captureLoadedTab(tabId) {
  try {
    await waitForTabComplete(tabId);
    const tab = await chrome.tabs.get(tabId);
    const finalUrl = tab.url || "";
    if (!finalUrl) return { ok: false, error: "Report popup opened but content could not be fetched" };
    const response = await fetch(finalUrl, { credentials: "include", redirect: "follow" });
    const contentType = response.headers.get("content-type") || "";
    const buffer = await response.arrayBuffer();
    const htmlError = await detectHtmlAuthFailure(buffer, contentType);
    if (htmlError) return { ok: false, error: htmlError, status: response.status, finalUrl, contentType };
    if (!response.ok || buffer.byteLength === 0) {
      return { ok: false, error: "Report popup opened but content could not be fetched", status: response.status, finalUrl, contentType };
    }
    return { ok: true, status: response.status, finalUrl, contentType, base64: arrayBufferToBase64(buffer) };
  } catch {
    return { ok: false, error: "Report popup opened but content could not be fetched" };
  }
}

async function captureSameFrameReport(tabId, frameId) {
  await delay(1500);
  const result = await chrome.scripting.executeScript({
    target: { tabId, frameIds: [frameId] },
    func: readPossibleReportDocument
  }).catch(() => []);
  const payload = result && result[0] && result[0].result;
  if (!payload || !payload.ok) return { ok: false, error: "Unable to capture NIMS printReport output" };
  const bytes = new TextEncoder().encode(payload.text);
  return {
    ok: true,
    status: 200,
    finalUrl: payload.url || "",
    contentType: "text/plain",
    base64: arrayBufferToBase64(bytes)
  };
}

function clickNimsPrintReportRow(locator) {
  function visible(node) {
    if (!node || node.hidden) return false;
    const style = window.getComputedStyle(node);
    return style.display !== "none" && style.visibility !== "hidden";
  }
  function text(node) {
    return ((node && (node.innerText || node.textContent || node.value)) || "").replace(/\s+/g, " ").trim();
  }
  function parseFunctionCall(onclick) {
    const match = String(onclick || "").match(/([A-Za-z_$][\w$]*)\s*\(([\s\S]*)\)/);
    if (!match) return { name: "", count: 0 };
    let count = 0;
    let current = "";
    let quote = "";
    for (let index = 0; index < match[2].length; index += 1) {
      const char = match[2][index];
      if (quote) {
        if (char === quote && match[2][index - 1] !== "\\") quote = "";
      } else if (char === "'" || char === '"') {
        quote = char;
      } else if (char === ",") {
        if (current.trim()) count += 1;
        current = "";
        continue;
      }
      current += char;
    }
    if (current.trim()) count += 1;
    return { name: match[1], count };
  }
  function isPrintReportButton(node) {
    if (!/view\s*report/i.test(text(node))) return false;
    const parsed = parseFunctionCall(node.getAttribute("onclick") || "");
    return parsed.name === locator.onclick_function_name && parsed.count === locator.onclick_arg_count;
  }
  const rows = Array.from(document.querySelectorAll("tr"));
  const row = Number.isFinite(locator.row_index) ? rows[locator.row_index] : null;
  let button = null;
  if (row && visible(row)) {
    button = Array.from(row.querySelectorAll("a, button, input[type='button'], input[type='submit']"))
      .find((node) => visible(node) && isPrintReportButton(node));
  }
  if (!button && Number.isFinite(locator.view_report_button_index) && locator.view_report_button_index >= 0) {
    button = Array.from(document.querySelectorAll("a, button, input[type='button'], input[type='submit']"))
      .filter((node) => visible(node) && isPrintReportButton(node))[locator.view_report_button_index];
  }
  if (!button) return { ok: false, error: "No View Report button found for row" };
  button.click();
  return { ok: true };
}

function readPossibleReportDocument() {
  const text = (document.body && (document.body.innerText || document.body.textContent) || "").replace(/\s+/g, " ").trim();
  const viewReportRows = Array.from(document.querySelectorAll("tr")).filter((row) => /view\s*report/i.test(row.innerText || row.textContent || "")).length;
  if (text.length < 40 || viewReportRows > 5) return { ok: false };
  if (!/\b(hemoglobin|creatinine|culture|bilirubin|platelet|sodium|potassium|report)\b/i.test(text)) return { ok: false };
  return { ok: true, text, url: location.href };
}

async function fetchViaTemporaryTab(url) {
  let tab;
  try {
    tab = await chrome.tabs.create({ url, active: false });
    await waitForTabComplete(tab.id);
    const current = await chrome.tabs.get(tab.id);
    const finalUrl = current.url || url;
    const response = await fetch(finalUrl, { credentials: "include", redirect: "follow" });
    const buffer = await response.arrayBuffer();
    const contentType = response.headers.get("content-type") || "";
    const htmlError = await detectHtmlAuthFailure(buffer, contentType);
    if (htmlError) return { ok: false, error: htmlError, status: response.status, finalUrl, contentType };
    return {
      ok: response.ok,
      status: response.status,
      finalUrl,
      contentType,
      base64: arrayBufferToBase64(buffer)
    };
  } catch (error) {
    return { ok: false, error: `Popup/new-tab workflow needs manual support: ${error.message}` };
  } finally {
    if (tab && tab.id) chrome.tabs.remove(tab.id).catch(() => {});
  }
}

function waitForTabComplete(tabId) {
  return new Promise((resolve) => {
    const timeout = setTimeout(done, 8000);
    function done() {
      clearTimeout(timeout);
      chrome.tabs.onUpdated.removeListener(listener);
      resolve();
    }
    function listener(updatedTabId, info) {
      if (updatedTabId === tabId && info.status === "complete") done();
    }
    chrome.tabs.onUpdated.addListener(listener);
  });
}

function setProgress(message) {
  return chrome.storage.local.set({ nimsFastSummaryProgress: message });
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function arrayBufferToBase64(buffer) {
  let binary = "";
  const bytes = new Uint8Array(buffer);
  for (let i = 0; i < bytes.byteLength; i += 1) binary += String.fromCharCode(bytes[i]);
  return btoa(binary);
}

async function detectHtmlAuthFailure(buffer, contentType) {
  const ctype = (contentType || "").toLowerCase();
  if (!ctype.includes("text/html")) return "";
  const text = new TextDecoder("utf-8").decode(buffer.slice(0, 5000)).toLowerCase();
  if (/\b(login|session expired|session has expired|authentication|captcha|otp|sign in|password)\b/.test(text)) {
    return "Session expired or login page returned";
  }
  return "HTML response is not a recognizable report";
}

