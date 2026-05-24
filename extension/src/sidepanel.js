let latestState = null;
let latestDiagnostic = null;
const sidepanelUtils = window.NimsSidepanelUtils;

document.addEventListener("DOMContentLoaded", () => {
  bindActions();
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
  document.getElementById("runFast").addEventListener("click", () => runSummaryFromBestFrame("fast"));
  document.getElementById("runCultures").addEventListener("click", () => runSummaryFromBestFrame("cultures_only"));
  document.getElementById("runFull").addEventListener("click", () => runSummaryFromBestFrame("full"));
  document.getElementById("diagnosePage").addEventListener("click", diagnosePage);
  document.getElementById("copySummary").addEventListener("click", copySummary);
  document.getElementById("exportCsv").addEventListener("click", () => download("nims-summary.csv", toCsv(latestState), "text/csv"));
  document.getElementById("exportJson").addEventListener("click", () => download("nims-summary.json", JSON.stringify(latestState || {}, null, 2), "application/json"));
  document.getElementById("clearCache").addEventListener("click", () => {
    chrome.runtime.sendMessage({ type: "NIMS_CLEAR_CACHE" }, () => {
      document.getElementById("status").textContent = "Cache cleared";
    });
  });
  document.getElementById("retryFailed").addEventListener("click", async () => {
    await runSummaryFromBestFrame("full");
  });
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

  await chrome.scripting.executeScript({
    target: { tabId: tab.id, allFrames: true },
    files: ["src/contentUtils.js"]
  }).catch(() => {});
  await chrome.scripting.executeScript({
    target: { tabId: tab.id, allFrames: true },
    files: ["src/contentScript.js"]
  }).catch(() => {});

  const injections = await chrome.scripting.executeScript({
    target: { tabId: tab.id, allFrames: true },
    func: collectFrameDiagnostic
  });
  const frames = injections.map((item) => ({ frameId: item.frameId, ...(item.result || {}) }));
  const diagnostic = sidepanelUtils.sanitizeDiagnosticResult({
    activeTabUrl: tab.url || "",
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
  const rows = utils ? utils.extractReportRows(document, location.href) : [];
  return {
    url: location.href,
    title: document.title || "",
    totalTr: document.querySelectorAll("tr").length,
    viewReportRows: Array.from(document.querySelectorAll("tr")).filter((row) => /view\s*report/i.test(row.innerText || row.textContent || "")).length,
    hasSummary: Boolean(window.NimsFastSummary),
    hasUtils: Boolean(window.NimsFastSummaryUtils),
    rowPreviews: rows.slice(0, 5).map((row) => ({
      date_sent: row.date_sent || "",
      report_name: row.report_name || "",
      department: row.department || "",
      hasHref: Boolean(row.href),
      hasOnclick: Boolean(row.onclick),
      postWorkflowSuspected: Boolean(row.post_workflow)
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
  const previews = best && best.rowPreviews ? best.rowPreviews : [];
  target.innerHTML = `
    <table><tbody>
      <tr><th>Active tab</th><td>${escapeHtml(diagnostic.activeTabUrl || "")}</td></tr>
      <tr><th>Total frames checked</th><td>${escapeHtml(diagnostic.totalFramesChecked || 0)}</td></tr>
      <tr><th>Best frame selected</th><td>${escapeHtml(diagnostic.bestFrameId || "")} ${escapeHtml(diagnostic.bestFrameUrl || "")}</td></tr>
    </tbody></table>
    <h3>Frames</h3>
    ${frameRows.length ? table(["Frame ID", "URL", "Title", "TR count", "View Report rows", "Summary API", "Utils API"], frameRows) : `<div class="empty">No frames checked.</div>`}
    <h3>Best Frame Row Previews</h3>
    ${previews.length ? table(["Date sent", "Report name", "Department/lab", "Href", "Onclick", "POST suspected"], previews.map((row) => [
      row.date_sent, row.report_name, row.department, row.hasHref ? "yes" : "no", row.hasOnclick ? "yes" : "no", row.postWorkflowSuspected ? "yes" : "no"
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
    ["Date sent", "Culture no.", "Site/specimen", "Result", "Organism", "Sensitivity summary", "Status"],
    rows.map((row) => [
      row.date_sent, row.culture_number, row.site_specimen, row.result, row.organism, row.sensitivity_summary, row.status
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
    lines.push(csv(["Culture", row.date_sent, row.culture_number, row.result, `${row.organism} ${row.sensitivity_summary}`]));
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
    lines.push([row.date_sent, row.culture_number, row.site_specimen, row.result, row.organism, row.sensitivity_summary, row.status].filter(Boolean).join(" | "));
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

