const HELPER = "http://127.0.0.1:8765";
const NIMS_URL_FILTERS = [
  "https://nimsts.edu.in/AHIMSG5/*",
  "https://www.nimsts.edu.in/AHIMSG5/*",
  "https://nimsts.edu.in/HISInvestigationG5/*",
  "https://www.nimsts.edu.in/HISInvestigationG5/*"
];
let privateDirectMapping = null;

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

  if (message.type === "NIMS_DISCOVER_MAPPING") {
    discoverDirectMapping(message.rowPayload, sender).then(sendResponse);
    return true;
  }

  if (message.type === "NIMS_GET_MAPPING_SUMMARY") {
    getDirectMappingSummary().then((summary) => sendResponse({ ok: true, summary }));
    return true;
  }

  if (message.type === "NIMS_CLEAR_DIRECT_MAPPING") {
    clearDirectMapping().then(sendResponse);
    return true;
  }

  if (message.type === "NIMS_FETCH_REPORT_DIRECT") {
    fetchReportDirect(message.rowPayload).then(sendResponse);
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

  if (message.type === "NIMS_HELPER_CACHE_LOOKUP") {
    callHelper("/cache-lookup", {
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

async function discoverDirectMapping(rowPayload, sender) {
  const tabId = sender && sender.tab && sender.tab.id;
  const frameId = sender && typeof sender.frameId === "number" ? sender.frameId : 0;
  if (!tabId || !rowPayload || !rowPayload.transient_print_report_arg) {
    return { ok: false, error: "Direct fetch mapping failed for this report." };
  }

  const observed = [];
  const popupPromise = waitForPopupTab(tabId);
  const requestPromise = observePrintReportRequests(tabId, rowPayload.transient_print_report_arg, observed);
  try {
    const clickResult = await chrome.scripting.executeScript({
      target: { tabId, frameIds: [frameId] },
      world: "MAIN",
      func: clickNimsPrintReportRow,
      args: [safeClickLocator(rowPayload.row)]
    });
    const clicked = clickResult && clickResult[0] && clickResult[0].result;
    if (!clicked || clicked.ok === false) {
      return { ok: false, error: (clicked && clicked.error) || "No View Report button found for row" };
    }
    const popupTab = await popupPromise;
    const candidates = await requestPromise;
    if (popupTab && popupTab.id) chrome.tabs.remove(popupTab.id).catch(() => {});
    const mapping = inferDirectMapping(candidates, rowPayload);
    if (!mapping) return { ok: false, error: "Direct fetch mapping failed for this report." };
    privateDirectMapping = mapping.privateMapping;
    await storeDirectMapping(privateDirectMapping, mapping.safeMappingSummary);
    return { ok: true, summary: mapping.safeMappingSummary };
  } catch {
    return { ok: false, error: "Direct fetch mapping failed for this report." };
  }
}

function observePrintReportRequests(tabId, transientArg, observed) {
  return new Promise((resolve) => {
    const relatedTabIds = new Set([tabId]);
    const timeout = setTimeout(done, 10000);
    function done() {
      clearTimeout(timeout);
      chrome.tabs.onCreated.removeListener(onTabCreated);
      chrome.webRequest.onBeforeRequest.removeListener(onBeforeRequest);
      chrome.webRequest.onHeadersReceived.removeListener(onHeadersReceived);
      chrome.webNavigation.onCommitted.removeListener(onNavigation);
      resolve(observed);
    }
    function onTabCreated(tab) {
      if (tab && tab.openerTabId === tabId && tab.id) relatedTabIds.add(tab.id);
    }
    function onBeforeRequest(details) {
      if (!relatedTabIds.has(details.tabId) && details.tabId !== -1) return;
      if (!isAllowedNimsUrl(details.url)) return;
      const candidate = {
        method: details.method || "GET",
        url: details.url,
        type: details.type || "",
        formData: details.requestBody && details.requestBody.formData ? details.requestBody.formData : {},
        argMatch: requestContainsArg(details, transientArg),
        contentType: ""
      };
      observed.push(candidate);
      if (candidate.argMatch) setTimeout(done, 400);
    }
    function onHeadersReceived(details) {
      const item = observed.find((candidate) => candidate.url === details.url);
      if (!item) return;
      const header = (details.responseHeaders || []).find((inner) => /^content-type$/i.test(inner.name));
      if (header) item.contentType = header.value || "";
    }
    function onNavigation(details) {
      if (relatedTabIds.has(details.tabId) && isAllowedNimsUrl(details.url)) {
        observed.push({ method: "GET", url: details.url, type: "navigation", formData: {}, argMatch: String(details.url).includes(transientArg), contentType: "" });
      }
    }
    chrome.tabs.onCreated.addListener(onTabCreated);
    chrome.webRequest.onBeforeRequest.addListener(onBeforeRequest, { urls: NIMS_URL_FILTERS }, ["requestBody"]);
    chrome.webRequest.onHeadersReceived.addListener(onHeadersReceived, { urls: NIMS_URL_FILTERS }, ["responseHeaders"]);
    chrome.webNavigation.onCommitted.addListener(onNavigation);
  });
}

function requestContainsArg(details, transientArg) {
  const arg = String(transientArg || "");
  if (!arg) return false;
  if (String(details.url || "").includes(encodeURIComponent(arg)) || String(details.url || "").includes(arg)) return true;
  const formData = details.requestBody && details.requestBody.formData ? details.requestBody.formData : {};
  return Object.values(formData).some((values) => (values || []).some((value) => String(value) === arg));
}

function inferDirectMapping(candidates, rowPayload) {
  const arg = rowPayload.transient_print_report_arg;
  const currentFields = rowPayload.transient_form_fields || {};
  const ranked = [...candidates].reverse().filter((candidate) => isAllowedNimsUrl(candidate.url));
  const matched = ranked.find((candidate) => candidate.argMatch) || ranked.find((candidate) => /report|print|pdf|process/i.test(safePath(candidate.url)));
  if (!matched) return null;
  const parsed = new URL(matched.url);
  const method = String(matched.method || "GET").toUpperCase();
  const privateMapping = {
    method,
    origin: parsed.origin,
    pathname: parsed.pathname,
    argumentParameterName: "",
    requiredFieldNames: [],
    discoveredAt: new Date().toISOString()
  };

  if (method === "POST") {
    const formData = matched.formData || {};
    for (const [name, values] of Object.entries(formData)) {
      if ((values || []).some((value) => String(value) === arg)) privateMapping.argumentParameterName = name;
      else privateMapping.requiredFieldNames.push(name);
    }
    if (!privateMapping.argumentParameterName) privateMapping.argumentParameterName = inferArgumentFieldFromCurrentFields(currentFields);
  } else {
    for (const [name, value] of parsed.searchParams.entries()) {
      if (value === arg) privateMapping.argumentParameterName = name;
      else if (Object.prototype.hasOwnProperty.call(currentFields, name)) privateMapping.requiredFieldNames.push(name);
    }
  }
  if (!privateMapping.argumentParameterName) return null;
  privateMapping.requiredFieldNames = unique(privateMapping.requiredFieldNames.filter((name) => name !== privateMapping.argumentParameterName));
  return {
    privateMapping,
    safeMappingSummary: safeMappingSummary(privateMapping, "ready")
  };
}

function inferArgumentFieldFromCurrentFields(currentFields) {
  const names = Object.keys(currentFields || {});
  return names.find((name) => /lab|req|requisition|report|sample|accession|test/i.test(name)) || "";
}

async function fetchReportDirect(rowPayload) {
  const mapping = await getPrivateDirectMapping();
  if (!mapping) return { ok: false, error: "Direct mapping not discovered. Click Discover Mapping first." };
  if (!rowPayload || !rowPayload.transient_print_report_arg) {
    return { ok: false, error: "Direct fetch mapping failed for this report." };
  }
  const fields = rowPayload.transient_form_fields || {};
  const missing = (mapping.requiredFieldNames || []).filter((name) => !Object.prototype.hasOwnProperty.call(fields, name));
  if (missing.length) return { ok: false, error: "Required dynamic form field missing in current NIMS page." };
  try {
    const request = buildDirectRequest(mapping, rowPayload.transient_print_report_arg, fields);
    const response = await fetch(request.url, request.options);
    const contentType = response.headers.get("content-type") || "";
    const buffer = await response.arrayBuffer();
    const reportError = await detectDirectReportFailure(buffer, contentType);
    if (reportError) return { ok: false, error: reportError, status: response.status, finalUrlSafeHostPath: safeHostPath(response.url), contentType };
    if (!response.ok) return { ok: false, error: "Direct fetch mapping failed for this report.", status: response.status, finalUrlSafeHostPath: safeHostPath(response.url), contentType };
    return {
      ok: true,
      status: response.status,
      contentType,
      finalUrlSafeHostPath: safeHostPath(response.url),
      base64: arrayBufferToBase64(buffer)
    };
  } catch {
    return { ok: false, error: "Direct fetch mapping failed for this report." };
  }
}

function buildDirectRequest(mapping, transientArg, fields) {
  const method = String(mapping.method || "GET").toUpperCase();
  const url = new URL(mapping.pathname || "/", mapping.origin);
  const params = new URLSearchParams();
  for (const name of mapping.requiredFieldNames || []) params.set(name, fields[name] || "");
  params.set(mapping.argumentParameterName, transientArg);
  if (method === "POST") {
    return {
      url: url.href,
      options: {
        method: "POST",
        credentials: "include",
        redirect: "follow",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: params.toString()
      }
    };
  }
  url.search = params.toString();
  return { url: url.href, options: { method: "GET", credentials: "include", redirect: "follow" } };
}

async function detectDirectReportFailure(buffer, contentType) {
  if (!buffer || buffer.byteLength === 0) return "Report response was empty.";
  const ctype = (contentType || "").toLowerCase();
  if (ctype.includes("pdf")) return "";
  if (ctype.includes("text/html") || ctype.includes("text/plain")) {
    const text = new TextDecoder("utf-8").decode(buffer.slice(0, 5000)).toLowerCase();
    if (/\b(login|session expired|session has expired|authentication|captcha|otp|sign in|password)\b/.test(text)) {
      return "Report response was login/session page.";
    }
    if (/\b(hemoglobin|creatinine|culture|bilirubin|platelet|sodium|potassium|report)\b/.test(text)) return "";
    return "Report response was not PDF or recognizable report.";
  }
  return "";
}

async function storeDirectMapping(privateMapping, summary) {
  if (chrome.storage.session) {
    await chrome.storage.session.set({ nimsDirectPrivateMapping: privateMapping });
  }
  await chrome.storage.local.set({ nimsDirectMappingSummary: summary });
}

async function getPrivateDirectMapping() {
  if (privateDirectMapping) return privateDirectMapping;
  if (!chrome.storage.session) return null;
  const data = await chrome.storage.session.get("nimsDirectPrivateMapping");
  privateDirectMapping = data.nimsDirectPrivateMapping || null;
  return privateDirectMapping;
}

async function getDirectMappingSummary() {
  const mapping = await getPrivateDirectMapping();
  if (mapping) return safeMappingSummary(mapping, "ready");
  const data = await chrome.storage.local.get("nimsDirectMappingSummary");
  return data.nimsDirectMappingSummary || { status: "failed" };
}

async function clearDirectMapping() {
  privateDirectMapping = null;
  if (chrome.storage.session) await chrome.storage.session.remove("nimsDirectPrivateMapping");
  await chrome.storage.local.remove("nimsDirectMappingSummary");
  return { ok: true };
}

function safeMappingSummary(mapping, status) {
  return {
    status,
    method: mapping.method || "",
    endpoint: safeHostPath(`${mapping.origin || ""}${mapping.pathname || ""}`),
    argumentParameterName: mapping.argumentParameterName || "",
    requiredFieldNames: mapping.requiredFieldNames || [],
    discoveredAt: mapping.discoveredAt || new Date().toISOString()
  };
}

function safeHostPath(url) {
  try {
    const parsed = new URL(url || "");
    return `${parsed.hostname}${parsed.pathname}`;
  } catch {
    return "";
  }
}

function safePath(url) {
  try {
    return new URL(url || "").pathname;
  } catch {
    return "";
  }
}

function isAllowedNimsUrl(url) {
  try {
    const parsed = new URL(url || "");
    return parsed.protocol === "https:" && ["nimsts.edu.in", "www.nimsts.edu.in"].includes(parsed.hostname)
      && (/^\/AHIMSG5\//.test(parsed.pathname) || /^\/HISInvestigationG5\//.test(parsed.pathname));
  } catch {
    return false;
  }
}

function unique(values) {
  return Array.from(new Set(values));
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

