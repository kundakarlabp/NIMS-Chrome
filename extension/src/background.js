const HELPER = "http://127.0.0.1:8765";
const popupCaptures = new Map();

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

  if (message.type === "NIMS_PREPARE_POPUP_CAPTURE") {
    const captureId = crypto.randomUUID();
    popupCaptures.set(captureId, {
      openerTabId: sender.tab && sender.tab.id,
      done: false,
      result: null,
      createdAt: Date.now()
    });
    setTimeout(() => finalizeCapture(captureId, { ok: false, error: "NIMS onclick/form workflow needs specific mapping" }), 20000);
    sendResponse({ ok: true, captureId });
    return false;
  }

  if (message.type === "NIMS_GET_POPUP_CAPTURE") {
    const capture = popupCaptures.get(message.captureId);
    sendResponse(capture ? { done: capture.done, result: capture.result } : { done: true, result: { ok: false, error: "NIMS onclick/form workflow needs specific mapping" } });
    if (capture && capture.done) popupCaptures.delete(message.captureId);
    return false;
  }

  if (message.type === "NIMS_CANCEL_POPUP_CAPTURE") {
    popupCaptures.delete(message.captureId);
    sendResponse({ ok: true });
    return false;
  }

  if (message.type === "NIMS_CLEAR_CACHE") {
    fetch(`${HELPER}/clear-cache`, { method: "POST" })
      .then((response) => sendResponse({ ok: response.ok }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  return false;
});

chrome.tabs.onCreated.addListener((tab) => {
  const entry = nextPendingCapture(tab.openerTabId);
  if (!entry) return;
  waitForTabComplete(tab.id)
    .then(() => fetchCapturedTab(tab.id))
    .then((result) => finalizeCapture(entry.captureId, result))
    .catch((error) => finalizeCapture(entry.captureId, { ok: false, error: `NIMS onclick/form workflow needs specific mapping: ${error.message}` }));
});

async function fetchReportWithSession(row) {
  const url = row.source_url || row.href;
  if (!url) {
    return { ok: false, error: "No report URL available" };
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

function nextPendingCapture(openerTabId) {
  for (const [captureId, capture] of popupCaptures.entries()) {
    if (capture.done) continue;
    if (capture.openerTabId && openerTabId && capture.openerTabId !== openerTabId) continue;
    return { captureId, capture };
  }
  return null;
}

async function fetchCapturedTab(tabId) {
  try {
    const current = await chrome.tabs.get(tabId);
    const finalUrl = current.url || "";
    if (!isAllowedNimsOrDataUrl(finalUrl)) {
      return { ok: false, error: "NIMS onclick/form workflow needs specific mapping" };
    }
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
  } finally {
    chrome.tabs.remove(tabId).catch(() => {});
  }
}

function finalizeCapture(captureId, result) {
  const capture = popupCaptures.get(captureId);
  if (!capture || capture.done) return;
  capture.done = true;
  capture.result = result;
}

function isAllowedNimsOrDataUrl(url) {
  if (/^data:/i.test(url || "")) return true;
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:" && ["nimsts.edu.in", "www.nimsts.edu.in"].includes(parsed.hostname);
  } catch {
    return false;
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

