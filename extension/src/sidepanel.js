let latestState = null;

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
  document.getElementById("copySummary").addEventListener("click", copySummary);
  document.getElementById("exportCsv").addEventListener("click", () => download("nims-summary.csv", toCsv(latestState), "text/csv"));
  document.getElementById("exportJson").addEventListener("click", () => download("nims-summary.json", JSON.stringify(latestState || {}, null, 2), "application/json"));
  document.getElementById("clearCache").addEventListener("click", () => {
    chrome.runtime.sendMessage({ type: "NIMS_CLEAR_CACHE" }, () => {
      document.getElementById("status").textContent = "Cache cleared";
    });
  });
  document.getElementById("retryFailed").addEventListener("click", async () => {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tab && tab.id) {
      chrome.scripting.executeScript({
        target: { tabId: tab.id },
        func: () => window.NimsFastSummary && window.NimsFastSummary.runSummary("full")
      });
    }
  });
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
      <tr><th>Reports read</th><td>${result.reportsRead}</td></tr>
      <tr><th>Reports skipped</th><td>${result.reportsSkipped}</td></tr>
      <tr><th>Errors</th><td>${(result.errors || []).length}</td></tr>
      <tr><th>Date range</th><td>${dateRange(result.summary.lab_trend_table)}</td></tr>
    </tbody></table>
  `;

  renderSourceReports(result.summary.source_reports || []);
  renderLabTrends(result.summary.lab_trend_table || { columns: [], rows: [] });
  renderCultures(result.summary.culture_table || []);
  renderInterpretation(result.summary.interpretation || []);
}

function renderSourceReports(rows) {
  const target = document.getElementById("sourceReports");
  if (!rows.length) {
    target.innerHTML = `<div class="empty">No source reports parsed.</div>`;
    return;
  }
  target.innerHTML = table(["Date sent", "Report name", "Type", "Status", "Notes"], rows.map((row) => [
    row.date_sent, row.report_name, row.type, row.status, row.notes
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
  const text = document.body.innerText;
  navigator.clipboard.writeText(text);
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

