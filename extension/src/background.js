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
    fetchReportWithSession(message.row).then(sendResponse);
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

async function fetchReportWithSession(row) {
  const url = row.source_url || row.href;
  if (!url) {
    if (row && (row.onclick_present || row.onclick_function_name)) {
      return { ok: false, error: "NIMS onclick/form workflow needs specific mapping" };
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
    return "session expired or report fetch failed";
  }
  return "HTML response is not a recognizable report";
}

