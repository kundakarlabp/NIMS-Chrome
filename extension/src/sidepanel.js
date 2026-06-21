let latestState = null;
let latestDiagnostic = null;
let navigationInProgress = false;
let navigationGeneration = 0;
const sidepanelUtils = window.NimsSidepanelUtils;

document.addEventListener("DOMContentLoaded", () => {
  bindActions();
  loadHelperSettings();
  chrome.storage.local.get(["nimsFastSummaryState", "nimsFastSummaryProgress"], (items) => {
    latestState = items.nimsFastSummaryState || null;
    render(latestState, items.nimsFastSummaryProgress);
  });
  chrome.storage.onChanged.addListener((changes) => {
    if (changes.nimsFastSummaryState) latestState = changes.nimsFastSummaryState.newValue;
    render(latestState, changes.nimsFastSummaryProgress && changes.nimsFastSummaryProgress.newValue);
  });
});

function bindActions() {
  document.getElementById("openCrReports").addEventListener("click", openCrWiseReports);
  document.getElementById("discoverMapping").addEventListener("click", discoverMappingFromBestFrame);
  document.getElementById("testDirectFetch").addEventListener("click", () => runSummaryFromBestFrame("test_direct"));
  document.getElementById("runFast").addEventListener("click", () => runSummaryFromBestFrame("bulk_fast"));
  document.getElementById("runCultures").addEventListener("click", () => runSummaryFromBestFrame("bulk_cultures_only"));
  document.getElementById("runFull").addEventListener("click", () => runSummaryFromBestFrame("bulk_full"));
  document.getElementById("clearMapping").addEventListener("click", clearDirectMapping);
  document.getElementById("manualPopupFallback").addEventListener("click", () => {
    document.getElementById("status").textContent = "Manual popup fallback may open reports one by one and be slow.";
    runSummaryFromBestFrame("manual_fallback");
  });
  document.getElementById("diagnosePage").addEventListener("click", diagnosePage);
  document.getElementById("copyMappingDiagnostics").addEventListener("click", copySafeMappingDiagnostics);
  document.getElementById("copyDirectFetchDiagnostics").addEventListener("click", copyDirectFetchDiagnostics);
  document.getElementById("copySummary").addEventListener("click", copySummary);
  document.getElementById("exportCsv").addEventListener("click", () => download("nims-summary.csv", toCsv(latestState), "text/csv"));
  document.getElementById("exportJson").addEventListener("click", () => download("nims-summary.json", JSON.stringify(latestState || {}, null, 2), "application/json"));
  document.getElementById("clearCache").addEventListener("click", () => {
    chrome.runtime.sendMessage({ type: "NIMS_HELPER_CLEAR_CACHE" }, (response) => {
      document.getElementById("status").textContent = response && response.ok ? "Cache cleared" : ((response && response.error) || "Cache clear failed");
    });
  });
  document.getElementById("retryFailed").addEventListener("click", async () => {
    await runSummaryFromBestFrame("bulk_full");
  });
  document.getElementById("saveHelperSettings").addEventListener("click", saveHelperSettings);
  document.getElementById("testHelperConnection").addEventListener("click", testHelperConnection);
  document.getElementById("clearHelperSettings").addEventListener("click", clearHelperSettings);
}


async function openCrWiseReports() {
  const status = document.getElementById("status");
  const button = document.getElementById("openCrReports");
  if (navigationInProgress) {
    status.textContent = "Open CR Reports is already running.";
    return;
  }
  const generation = ++navigationGeneration;
  navigationInProgress = true;
  if (button) button.disabled = true;
  const setStatus = (message) => {
    if (generation === navigationGeneration) status.textContent = message;
  };
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab || !tab.id) throw new Error("No active tab found");
    if (!sidepanelUtils.isAllowedNimsUrl(tab.url || "")) throw new Error("Open a controlled NIMS page after manual login.");
    setStatus("Checking current NIMS page…");
    const started = Date.now();
    for (let attempt = 0; attempt < 14 && Date.now() - started < 20000; attempt += 1) {
      if (generation !== navigationGeneration) return;
      await ensureMainWorldNavigationCore(tab.id);
      const probes = await probeNavigationFrames(tab.id);
      if (generation !== navigationGeneration) return;
      const selected = sidepanelUtils.selectNavigationTarget(probes);
      if (selected && selected.kind === "terminal") {
        const stage = selected.frame.stage || selected.frame.detectedStage;
        if (stage === "cr_search") { setStatus("CR-wise report page ready. Enter the CR number."); return; }
        if (stage === "report_list") { setStatus("Report list detected."); return; }
        if (stage === "login") { setStatus("Manual NIMS login required."); return; }
        if (stage === "session_expired") { setStatus("NIMS session expired. Login again."); return; }
      }
      if (!selected || selected.kind !== "action") {
        setStatus("Unable to locate the Investigation menu. Retrying…");
        await delay(750);
        continue;
      }
      const clicked = await chrome.scripting.executeScript({
        target: { tabId: tab.id, frameIds: [selected.frame.frameId] },
        world: "MAIN",
        func: () => {
          if (!window.NimsReportCore || typeof window.NimsReportCore.navigateCurrentDocumentStep !== "function") {
            return { ok: false, stage: "unknown", action: "none", done: false, errorCode: "navigation_api_unavailable" };
          }
          return window.NimsReportCore.navigateCurrentDocumentStep(document);
        }
      });
      const result = clicked && clicked[0] && clicked[0].result || { stage: "unknown", errorCode: "navigation_target_not_found" };
      if (result.stage === "cr_search") { setStatus("CR-wise report page ready. Enter the CR number."); return; }
      if (result.stage === "report_list") { setStatus("Report list detected."); return; }
      if (result.stage === "login") { setStatus("Manual NIMS login required."); return; }
      if (result.stage === "session_expired") { setStatus("NIMS session expired. Login again."); return; }
      if (result.action === "clicked_investigation_module") setStatus("Opening Investigation…");
      else if (result.action === "clicked_cr_wise_menu") setStatus("Opening CR-wise reports…");
      else if (result.action === "cooldown") setStatus("Waiting for NIMS navigation…");
      else setStatus("Unable to locate the Investigation menu. Retrying…");
      await delay(750);
    }
    setStatus("Unable to open CR-wise reports. Use Diagnose and retry.");
  } catch (error) {
    setStatus(`Error: ${error.message}`);
  } finally {
    if (generation === navigationGeneration) {
      navigationInProgress = false;
      if (button) button.disabled = false;
    }
  }
}

async function probeNavigationFrames(tabId) {
  const probes = await chrome.scripting.executeScript({
    target: { tabId, allFrames: true },
    world: "MAIN",
    func: () => {
      if (!window.NimsReportCore || typeof window.NimsReportCore.getCurrentDocumentNavigationDiagnostic !== "function") {
        return { stage: "unknown", actionable: false, visible: false, errorCode: "navigation_api_unavailable" };
      }
      return window.NimsReportCore.getCurrentDocumentNavigationDiagnostic(document);
    }
  }).catch(() => []);
  return (probes || []).map((item) => ({ frameId: item.frameId, ...(item.result || { stage: "unknown", visible: false }) }));
}

function delay(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }

async function ensureMainWorldNavigationCore(tabId) {
  const checks = await chrome.scripting.executeScript({
    target: { tabId, allFrames: true },
    world: "MAIN",
    func: () => Boolean(window.NimsReportCore && typeof window.NimsReportCore.detectCurrentDocumentStage === "function" && typeof window.NimsReportCore.navigateCurrentDocumentStep === "function")
  }).catch(() => []);
  const missingFrameIds = (checks || []).filter((item) => !item.result).map((item) => item.frameId);
  if (!missingFrameIds.length) return;
  await chrome.scripting.executeScript({ target: { tabId, frameIds: missingFrameIds }, world: "MAIN", files: ["src/navigationCore.js"] }).catch(() => {});
}

async function ensureContentApi(tabId) {
  const check = await chrome.scripting.executeScript({
    target: { tabId, allFrames: true },
    func: () => Boolean(window.NimsFastSummary && window.NimsFastSummary.navigateToCrWiseReports)
  }).catch(() => []);
  if ((check || []).some((item) => item.result)) return;
  await chrome.scripting.executeScript({ target: { tabId, allFrames: true }, files: ["src/navigationCore.js"] }).catch(() => {});
  await chrome.scripting.executeScript({ target: { tabId, allFrames: true }, files: ["src/contentUtils.js"] }).catch(() => {});
  await chrome.scripting.executeScript({ target: { tabId, allFrames: true }, files: ["src/contentScript.js"] }).catch(() => {});
}

async function loadHelperSettings() {
  const response = await chrome.runtime.sendMessage({ type: "NIMS_GET_HELPER_SETTINGS" });
  const settings = response && response.settings || {};
  document.getElementById("helperMode").value = settings.mode || "local";
  document.getElementById("helperUrl").value = settings.url || "http://127.0.0.1:8765";
  document.getElementById("helperApiKey").value = "";
  document.getElementById("helperKeyStatus").textContent = settings.hasApiKey ? `API key ${settings.apiKeyMasked}` : "No API key saved.";
}

async function saveHelperSettings() {
  const settings = {
    mode: document.getElementById("helperMode").value,
    url: document.getElementById("helperUrl").value,
    apiKey: document.getElementById("helperApiKey").value
  };
  const response = await chrome.runtime.sendMessage({ type: "NIMS_SAVE_HELPER_SETTINGS", settings });
  document.getElementById("status").textContent = response && response.ok ? "Helper settings saved" : ((response && response.error) || "Helper settings save failed");
  if (response && response.ok) await loadHelperSettings();
}

async function testHelperConnection() {
  await saveHelperSettings();
  const response = await chrome.runtime.sendMessage({ type: "NIMS_HELPER_HEALTH" });
  document.getElementById("status").textContent = response && response.ok ? "Helper connection ok" : ((response && response.error) || "Helper connection failed");
}

async function clearHelperSettings() {
  const response = await chrome.runtime.sendMessage({ type: "NIMS_CLEAR_HELPER_SETTINGS" });
  document.getElementById("status").textContent = response && response.ok ? "Helper settings cleared; local helper restored" : "Clear helper settings failed";
  await loadHelperSettings();
}

async function discoverMappingFromBestFrame() {
  const status = document.getElementById("status");
  try {
    status.textContent = "Discovering direct report mapping";
    const { tab, diagnostic } = await prepareAndDiagnoseActiveTab();
    renderDiagnostics(diagnostic);
    const best = sidepanelUtils.selectBestFrameDiagnostic(diagnostic.frames);
    if (!best || !best.hasSummary || Number(best.viewReportRows || 0) <= 0) {
      status.textContent = "No frame with View Report rows found";
      return;
    }
    const injections = await chrome.scripting.executeScript({
      target: { tabId: tab.id, frameIds: [best.frameId] },
      func: () => window.NimsFastSummary && window.NimsFastSummary.discoverMapping()
    });
    const response = injections && injections[0] && await injections[0].result;
    status.textContent = response && response.ok
      ? `Direct mapping candidate: ${response.summary.method} ${response.summary.endpoint}. Run Test Direct Fetch.`
      : ((response && response.error) || "Direct fetch mapping failed for this report.");
  } catch (error) {
    status.textContent = `Error: ${error.message}`;
  }
}

async function clearDirectMapping() {
  const response = await chrome.runtime.sendMessage({ type: "NIMS_CLEAR_DIRECT_MAPPING" });
  document.getElementById("status").textContent = response && response.ok ? "Direct mapping cleared" : "Clear mapping failed";
}

async function runSummaryFromBestFrame(mode) {
  const status = document.getElementById("status");
  try {
    status.textContent = "Checking NIMS frames";
    const { tab, diagnostic } = await prepareAndDiagnoseActiveTab();
    renderDiagnostics(diagnostic);
    const best = sidepanelUtils.selectBestFrameDiagnostic(diagnostic.frames);
    if (!best || !best.hasSummary || Number(best.viewReportRows || 0) <= 0) {
      status.textContent = "No frame with View Report rows found";
      return;
    }
    status.textContent = `Running ${mode} in frame ${best.frameId}`;
    await chrome.scripting.executeScript({
      target: { tabId: tab.id, frameIds: [best.frameId] },
      func: (summaryMode) => window.NimsFastSummary && window.NimsFastSummary.runSummary(summaryMode),
      args: [mode]
    });
  } catch (error) {
    status.textContent = `Error: ${error.message}`;
  }
}

async function diagnosePage() {
  const status = document.getElementById("status");
  try {
    status.textContent = "Diagnosing NIMS frames";
    const { diagnostic } = await prepareAndDiagnoseActiveTab();
    renderDiagnostics(diagnostic);
    status.textContent = "Diagnosis complete";
  } catch (error) {
    status.textContent = `Error: ${error.message}`;
  }
}

async function prepareAndDiagnoseActiveTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab || !tab.id) throw new Error("No active tab found");
  if (!sidepanelUtils.isAllowedNimsUrl(tab.url || "")) {
    throw new Error("Open a NIMS page before running summary");
  }

  await ensureContentApi(tab.id);

  const injections = await chrome.scripting.executeScript({
    target: { tabId: tab.id, allFrames: true },
    func: collectFrameDiagnostic
  });
  const helperHealth = await chrome.runtime.sendMessage({ type: "NIMS_HELPER_HEALTH" });
  const frames = injections.map((item) => ({ frameId: item.frameId, ...(item.result || {}) }));
  const diagnostic = sidepanelUtils.sanitizeDiagnosticResult({
    activeTabUrl: tab.url || "",
    helperStatus: helperHealth && helperHealth.ok ? "ok" : "failed",
    helperError: helperHealth && helperHealth.ok ? "" : ((helperHealth && helperHealth.error) || "Local helper health check failed"),
    totalFramesChecked: frames.length,
    frames
  });
  const best = sidepanelUtils.selectBestFrameDiagnostic(diagnostic.frames);
  diagnostic.bestFrameId = best ? best.frameId : "";
  diagnostic.bestFrameUrl = best ? best.url : "";
  latestDiagnostic = diagnostic;
  return { tab, diagnostic };
}

function collectFrameDiagnostic() {
  const utils = window.NimsFastSummaryUtils;
  const core = window.NimsReportCore;
  const rows = utils ? utils.extractReportRows(document, location.href) : [];
  const stage = core && core.detectNimsPageStage ? core.detectNimsPageStage(document) : { stage: "unknown", framesChecked: 1 };
  const frame = core && core.collectFrames ? (core.collectFrames(document)[0] || {}) : {};
  return {
    url: location.href,
    title: document.title || "",
    totalTr: document.querySelectorAll("tr").length,
    depth: frame.depth || 0,
    detectedStage: stage.stage || "unknown",
    hasCrWiseMenu: Boolean(frame.hasCrWiseMenu),
    hasInvestigationModule: Boolean(frame.hasInvestigationModule),
    hasCrSearchForm: Boolean(frame.hasCrSearchForm),
    hasSetPdfTemplate: Boolean(frame.setPdfTemplate && frame.setPdfTemplate.discovered),
    viewReportRows: Array.from(document.querySelectorAll("tr")).filter((row) => /view\s*report/i.test(row.innerText || row.textContent || "")).length,
    hasSummary: Boolean(window.NimsFastSummary),
    hasUtils: Boolean(window.NimsFastSummaryUtils),
    rowPreviews: rows.slice(0, 10).map((row) => ({
      date_sent: row.date_sent || "",
      report_name: row.report_name || "",
      department: row.department || "",
      hasHref: Boolean(row.href),
      hasOnclick: Boolean(row.onclick),
      onclick_function_name: row.onclick_function_name || "",
      onclick_arg_count: row.onclick_arg_count || 0,
      onclick_parse_status: row.onclick_parse_status || "",
      global_form_present: Boolean(row.global_form_present),
      form_method: row.form_method || "",
      postWorkflowSuspected: Boolean(row.post_workflow),
      unsupported_post_only: Boolean(row.unsupported_post_only),
      nearby_input_names: row.nearby_input_names || []
    }))
  };
}

function render(state, progress) {
  const status = document.getElementById("status");
  status.textContent = progress || (state && state.progress) || "Waiting for report list.";
  if (!state || !state.result) return;

  const result = state.result;
  document.getElementById("summaryHeader").innerHTML = `
    <table><tbody>
      <tr><th>Mode</th><td>${escapeHtml(result.mode)}</td></tr>
      <tr><th>Reports found</th><td>${result.reportsFound}</td></tr>
      <tr><th>Reports selected</th><td>${result.reportsSelected || 0}</td></tr>
      <tr><th>Reports parsed</th><td>${result.reportsParsed || result.reportsRead || 0}</td></tr>
      <tr><th>Reports failed</th><td>${result.reportsFailed || 0}</td></tr>
      <tr><th>Reports skipped</th><td>${result.reportsSkipped}</td></tr>
      <tr><th>Errors</th><td>${(result.errors || []).length}</td></tr>
      <tr><th>Date range</th><td>${dateRange(result.summary.lab_trend_table)}</td></tr>
    </tbody></table>
  `;

  renderSourceReports(result.summary.source_reports || []);
  renderFailedReports(result.summary.source_reports || []);
  renderLabTrends(result.summary.lab_trend_table || { columns: [], rows: [] });
  renderCultures(result.summary.culture_table || []);
  renderInterpretation(result.summary.interpretation || []);
}

function renderDiagnostics(diagnostic) {
  latestDiagnostic = diagnostic;
  const target = document.getElementById("diagnostics");
  if (!diagnostic) {
    target.innerHTML = `<div class="empty">No diagnosis yet.</div>`;
    return;
  }
  const frames = diagnostic.frames || [];
  const frameRows = frames.map((frame) => [
    frame.frameId,
    frame.url,
    frame.title,
    frame.totalTr,
    frame.viewReportRows,
    frame.hasSummary ? "yes" : "no",
    frame.hasUtils ? "yes" : "no"
  ]);
  const best = sidepanelUtils.selectBestFrameDiagnostic(frames);
  const previews = best && best.rowPreviews ? best.rowPreviews.slice(0, 5) : [];
  target.innerHTML = `
    <table><tbody>
      <tr><th>Active tab</th><td>${escapeHtml(diagnostic.activeTabUrl || "")}</td></tr>
      <tr><th>Helper status</th><td>${escapeHtml(diagnostic.helperStatus || "")}</td></tr>
      <tr><th>Helper error</th><td>${escapeHtml(diagnostic.helperError || "")}</td></tr>
      <tr><th>Total frames checked</th><td>${escapeHtml(diagnostic.totalFramesChecked || 0)}</td></tr>
      <tr><th>Best frame selected</th><td>${escapeHtml(diagnostic.bestFrameId || "")} ${escapeHtml(diagnostic.bestFrameUrl || "")}</td></tr>
    </tbody></table>
    <h3>Frames</h3>
    ${frameRows.length ? table(["Frame ID", "URL", "Title", "TR count", "View Report rows", "Summary API", "Utils API"], frameRows) : `<div class="empty">No frames checked.</div>`}
    <h3>Best Frame Row Previews</h3>
    ${previews.length ? table(["Date sent", "Report name", "Department/lab", "Href", "Onclick", "Function", "Args", "Parse", "Global form", "Method", "Unsupported POST"], previews.map((row) => [
      row.date_sent, row.report_name, row.department, row.hasHref ? "yes" : "no", row.hasOnclick ? "yes" : "no", row.onclick_function_name, row.onclick_arg_count, row.onclick_parse_status, row.global_form_present ? "yes" : "no", row.form_method, row.unsupported_post_only ? "yes" : "no"
    ])) : `<div class="empty">No sanitized row previews.</div>`}
  `;
}

function renderSourceReports(rows) {
  const target = document.getElementById("sourceReports");
  if (!rows.length) {
    target.innerHTML = `<div class="empty">No source reports parsed.</div>`;
    return;
  }
  target.innerHTML = table(["Date sent", "Report name", "Type", "Tags", "Status", "Notes"], rows.map((row) => [
    row.date_sent, row.report_name, row.type, row.tags, row.status, row.notes
  ]));
}

function renderFailedReports(rows) {
  const target = document.getElementById("failedReports");
  const failed = rows.filter((row) => row.status === "error" || row.notes);
  if (!failed.length) {
    target.innerHTML = `<div class="empty">No failed reports.</div>`;
    return;
  }
  target.innerHTML = table(["Date sent", "Report name", "Error"], failed.map((row) => [
    row.date_sent, row.report_name, row.notes || "unable to parse / verify source report"
  ]));
}

function renderLabTrends(data) {
  const target = document.getElementById("labTrends");
  if (!data.rows || !data.rows.length) {
    target.innerHTML = `<div class="empty">No lab trend data.</div>`;
    return;
  }
  const headers = ["Parameter", ...(data.columns || []), "Trend"];
  const rows = data.rows.map((row) => [row.parameter, ...(row.values || []), row.trend]);
  target.innerHTML = table(headers, rows);
}

function renderCultures(rows) {
  const target = document.getElementById("cultures");
  if (!rows.length) {
    target.innerHTML = `<div class="empty">No culture reports parsed.</div>`;
    return;
  }
  target.innerHTML = table(
    ["Date sent", "Collection date", "Reporting date", "Culture no.", "Specimen no.", "Site/specimen", "Culture type", "Bottle/set", "Status", "Result", "Growth", "Organism", "Comment", "Sensitivity summary"],
    rows.map((row) => [
      row.date_sent,
      row.collection_date,
      row.reporting_date,
      row.culture_no || row.culture_number,
      row.specimen_no,
      row.site_specimen,
      row.culture_type,
      row.bottle_set,
      row.status || row.report_status,
      row.result,
      row.growth || row.growth_quantity,
      row.organism,
      row.comment,
      row.sensitivity_summary
    ])
  );
}

function renderInterpretation(items) {
  const target = document.getElementById("interpretation");
  target.innerHTML = items.length
    ? items.map((item) => `<li>${escapeHtml(item)}</li>`).join("")
    : `<li>AI interpretation disabled; structured tables generated locally.</li>`;
}

function table(headers, rows) {
  return `<table><thead><tr>${headers.map((h) => `<th>${escapeHtml(h)}</th>`).join("")}</tr></thead><tbody>${
    rows.map((row) => `<tr>${row.map((cell) => `<td>${escapeHtml(cell || "")}</td>`).join("")}</tr>`).join("")
  }</tbody></table>`;
}

function copySummary() {
  navigator.clipboard.writeText(toCopyText(latestState));
}

function copySafeMappingDiagnostics() {
  navigator.clipboard.writeText(toSafeMappingDiagnosticsText(latestDiagnostic));
}

async function copyDirectFetchDiagnostics() {
  const response = await chrome.runtime.sendMessage({ type: "NIMS_GET_DIRECT_DIAGNOSTICS" });
  const text = toDirectFetchDiagnosticsText(latestDiagnostic, response && response.diagnostics);
  await navigator.clipboard.writeText(text);
  document.getElementById("status").textContent = "Direct fetch diagnostics copied";
}

function toDirectFetchDiagnosticsText(pageDiagnostic, direct) {
  const best = sidepanelUtils.selectBestFrameDiagnostic((pageDiagnostic && pageDiagnostic.frames) || []);
  const lines = [
    "NIMS Fast Summary direct fetch diagnostics",
    `Active tab: ${pageDiagnostic ? pageDiagnostic.activeTabUrl || "" : ""}`,
    `Best frame: ${best ? best.url || "" : ""}`,
    `Helper status: ${pageDiagnostic ? pageDiagnostic.helperStatus || "" : ""}`,
    `Mapping status: ${direct ? direct.mappingStatus || "none" : "none"}`,
    `Endpoint: ${direct ? direct.endpoint || "" : ""}`,
    `Method: ${direct ? direct.method || "" : ""}`,
    `setPdf template discovered: ${direct && direct.setPdfTemplateDiscovered ? "yes" : "no"}`,
    `Report mode parameter: ${direct ? direct.reportModeParameterName || "" : ""}`,
    `Report argument parameter: ${direct ? direct.reportArgumentParameterName || "" : ""}`,
    `Argument parameter: ${direct ? direct.argumentParameterName || "" : ""}`,
    `Query parameter names: ${direct ? (direct.queryParamNames || []).join(", ") : ""}`,
    `POST field names: ${direct ? (direct.postFieldNames || []).join(", ") : ""}`,
    `Required field names: ${direct ? (direct.requiredFieldNames || []).join(", ") : ""}`,
    `Last classification: ${direct ? direct.lastClassification || "" : ""}`,
    ""
  ];
  if (direct && direct.selectedTestRow) {
    lines.push("Selected test row");
    lines.push(`Date: ${direct.selectedTestRow.date_sent || ""}`);
    lines.push(`Report: ${direct.selectedTestRow.report_name || ""}`);
    lines.push(`Department: ${direct.selectedTestRow.department || ""}`);
    lines.push(`Function: ${direct.selectedTestRow.onclick_function_name || ""}`);
    lines.push(`Arg count: ${direct.selectedTestRow.onclick_arg_count || 0}`);
    lines.push("");
  }
  if (direct && direct.safeFormStructure) {
    lines.push("Current form structure");
    lines.push(`Method: ${direct.safeFormStructure.form_method || ""}`);
    lines.push(`Action: ${direct.safeFormStructure.form_action_host_path || ""}`);
    lines.push(`Field names: ${(direct.safeFormStructure.field_names || []).join(", ")}`);
    lines.push("");
  }
  lines.push("Discovered requests");
  for (const request of (direct && direct.discoveredRequests) || []) {
    lines.push([
      request.method || "",
      request.endpoint || "",
      `status=${request.statusCode || 0}`,
      `type=${request.type || ""}`,
      `content=${request.contentType || ""}`,
      `query=[${(request.queryParamNames || []).join(", ")}]`,
      `post=[${(request.postFieldNames || []).join(", ")}]`,
      `popup=${request.openedPopup ? "yes" : "no"}`
    ].join(" | "));
  }
  if (direct && direct.lastTestDirectFetch) {
    lines.push("", "Last test direct fetch");
    lines.push(`Status: ${direct.lastTestDirectFetch.status || 0}`);
    lines.push(`Content type: ${direct.lastTestDirectFetch.contentType || ""}`);
    lines.push(`Classification: ${direct.lastTestDirectFetch.classification || ""}`);
    lines.push(`Parsed: ${direct.lastTestDirectFetch.parsed ? "yes" : "no"}`);
    lines.push(`Parameter count: ${direct.lastTestDirectFetch.parameterCount || 0}`);
    lines.push(`Tags: ${(direct.lastTestDirectFetch.reportTags || []).join(", ")}`);
    lines.push(`Errors: ${(direct.lastTestDirectFetch.errors || []).join("; ")}`);
  }
  return sidepanelUtils.sanitizeDiagnosticText(lines.join("\n"));
}

function toSafeMappingDiagnosticsText(diagnostic) {
  if (!diagnostic) return "No diagnosis yet.";
  const best = sidepanelUtils.selectBestFrameDiagnostic(diagnostic.frames || []);
  const lines = [
    "NIMS Fast Summary safe mapping diagnostics",
    `Best frame: ${best ? best.url : ""}`,
    `View Report rows: ${best ? best.viewReportRows : 0}`,
    `Helper status: ${diagnostic.helperStatus || ""}`,
    ""
  ];
  const previews = best && best.rowPreviews ? best.rowPreviews.slice(0, 10) : [];
  previews.forEach((row, index) => {
    lines.push(`Row ${index + 1}`);
    lines.push(`Date: ${row.date_sent || ""}`);
    lines.push(`Report: ${row.report_name || ""}`);
    lines.push(`Department: ${row.department || ""}`);
    lines.push(`Href: ${row.hasHref ? "yes" : "no"}`);
    lines.push(`Onclick: ${row.hasOnclick ? "yes" : "no"}`);
    lines.push(`Onclick function: ${row.onclick_function_name || ""}`);
    lines.push(`Onclick arg count: ${row.onclick_arg_count || 0}`);
    lines.push(`Onclick parse status: ${row.onclick_parse_status || ""}`);
    lines.push(`Global form present: ${row.global_form_present ? "yes" : "no"}`);
    lines.push(`Form method: ${row.form_method || ""}`);
    lines.push(`Unsupported POST only: ${row.unsupported_post_only ? "yes" : "no"}`);
    lines.push(`Nearby input names: ${(row.nearby_input_names || []).join(", ")}`);
    lines.push("");
  });
  return lines.join("\n");
}

function toCsv(state) {
  const result = state && state.result && state.result.summary;
  if (!result) return "";
  const lines = [];
  lines.push("Section,Date,Name,Value,Notes");
  for (const row of result.source_reports || []) {
    lines.push(csv(["Source", row.date_sent, row.report_name, row.status, row.notes]));
  }
  for (const row of (result.lab_trend_table && result.lab_trend_table.rows) || []) {
    lines.push(csv(["Lab", "", row.parameter, (row.values || []).join(" | "), row.trend]));
  }
  for (const row of result.culture_table || []) {
    lines.push(csv([
      "Culture",
      row.date_sent,
      row.culture_no || row.culture_number,
      row.result,
      [
        row.collection_date && `collection=${row.collection_date}`,
        row.reporting_date && `reporting=${row.reporting_date}`,
        row.specimen_no && `specimen=${row.specimen_no}`,
        row.site_specimen,
        row.culture_type,
        row.bottle_set,
        row.growth || row.growth_quantity,
        row.organism,
        row.comment,
        row.sensitivity_summary
      ].filter(Boolean).join(" | ")
    ]));
  }
  return lines.join("\n");
}

function toCopyText(state) {
  const result = state && state.result && state.result.summary;
  if (!result) return "Clinical safety: verify values against source reports before clinical decisions.";
  const lines = ["Clinical safety: verify values against source reports before clinical decisions.", ""];
  lines.push("Source Reports");
  for (const row of result.source_reports || []) {
    lines.push([row.date_sent, row.report_name, row.type, row.tags, row.status, row.notes].filter(Boolean).join(" | "));
  }
  lines.push("", "Lab Trends");
  const lab = result.lab_trend_table || { columns: [], rows: [] };
  lines.push(["Parameter", ...lab.columns, "Trend"].join(" | "));
  for (const row of lab.rows || []) {
    lines.push([row.parameter, ...(row.values || []), row.trend].join(" | "));
  }
  lines.push("", "Cultures");
  for (const row of result.culture_table || []) {
    lines.push([
      row.date_sent,
      row.collection_date,
      row.reporting_date,
      row.culture_no || row.culture_number,
      row.specimen_no,
      row.site_specimen,
      row.culture_type,
      row.bottle_set,
      row.status || row.report_status,
      row.result,
      row.growth || row.growth_quantity,
      row.organism,
      row.comment,
      row.sensitivity_summary
    ].filter(Boolean).join(" | "));
  }
  lines.push("", "Interpretation");
  for (const item of result.interpretation || []) lines.push(`- ${item}`);
  return lines.join("\n");
}

function csv(values) {
  return values.map((value) => `"${String(value || "").replace(/"/g, '""')}"`).join(",");
}

function download(filename, content, type) {
  const blob = new Blob([content || ""], { type });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

function dateRange(tableData) {
  const columns = (tableData && tableData.columns) || [];
  return columns.length ? `${columns[columns.length - 1]} to ${columns[0]}` : "";
}

function escapeHtml(value) {
  return String(value == null ? "" : value).replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#039;"
  }[char]));
}

