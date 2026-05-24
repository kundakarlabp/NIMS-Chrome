(function () {
  const HELPER = "http://127.0.0.1:8765";
  const isExtension = typeof chrome !== "undefined" && chrome.runtime && chrome.runtime.sendMessage;
  const utils = window.NimsFastSummaryUtils;
  const DEBUG_MODE = false;
  let observerStarted = false;

  function start() {
    scanAndInject();
    startObserver();
    startPeriodicScan();
  }

  function scanAndInject() {
    if (document.getElementById("nims-fast-summary-toolbar")) return;
    if (!utils.hasReportRows(document) && !location.href.includes("mock_report_list")) return;

    const toolbar = document.createElement("div");
    toolbar.id = "nims-fast-summary-toolbar";
    toolbar.innerHTML = `
      <strong>NIMS Fast Summary</strong>
      <button class="nims-summary-button" data-mode="fast">Fast Summary</button>
      <button class="nims-summary-button" data-mode="cultures_only">Cultures Only</button>
      <button class="nims-summary-button" data-mode="full">Full Summary</button>
      <div id="nims-summary-progress">Ready</div>
    `;
    document.body.appendChild(toolbar);
    toolbar.querySelectorAll("button[data-mode]").forEach((button) => {
      button.addEventListener("click", () => runSummary(button.dataset.mode));
    });
  }

  function startObserver() {
    if (observerStarted || !document.body) return;
    observerStarted = true;
    const observer = new MutationObserver(() => scanAndInject());
    observer.observe(document.body, { childList: true, subtree: true });
  }

  function startPeriodicScan() {
    const started = Date.now();
    const interval = setInterval(() => {
      scanAndInject();
      if (Date.now() - started > 15000 || document.getElementById("nims-fast-summary-toolbar")) {
        clearInterval(interval);
      }
    }, 500);
  }

  async function runSummary(mode) {
    const progress = document.getElementById("nims-summary-progress");
    const setProgress = (message) => {
      if (progress) progress.textContent = message;
      if (isExtension) chrome.runtime.sendMessage({ type: "NIMS_PROGRESS", message }).catch(() => {});
    };

    try {
      setProgress("Reading report list");
      const rows = utils.extractReportRows(document, location.href);
      const selected = utils.selectRowsForMode(rows, mode);
      await saveState({ mode, rows, selected, progress: "Reading report list", result: null });
      if (isExtension) await chrome.runtime.sendMessage({ type: "NIMS_OPEN_PANEL" });

      const parsedReports = [];
      for (let index = 0; index < selected.length; index += 1) {
        const row = selected[index];
        if (row.post_workflow || !row.source_url) {
          parsedReports.push(rowError(row, row.status || "POST workflow needs live-site mapping"));
          continue;
        }
        setProgress(`Fetching report ${index + 1}/${selected.length}`);
        const fetched = await fetchReport(row);
        if (!fetched.ok) {
          parsedReports.push(rowError(row, fetched.error || "unable to parse / verify source report"));
          continue;
        }
        setProgress(`Parsing report ${index + 1}/${selected.length}`);
        const parsed = await parseReport(row, fetched);
        parsedReports.push(parsed);
      }

      setProgress("Creating tables");
      const summary = await postJson(`${HELPER}/summarize`, { mode, reports: parsedReports });
      const failed = parsedReports.filter((r) => r.errors && r.errors.length > 0);
      const result = {
        mode,
        reportsFound: rows.length,
        reportsSelected: selected.length,
        reportsParsed: parsedReports.length - failed.length,
        reportsFailed: failed.length,
        reportsSkipped: rows.length - selected.length,
        errors: failed.flatMap((r) => r.errors || []),
        summary
      };
      await saveState({ mode, rows, selected, parsedReports, result, progress: "Done" });
      setProgress("Done");
    } catch (error) {
      setProgress(`Error: ${error.message}`);
      await saveState({ mode, progress: `Error: ${error.message}` });
    }
  }

  async function fetchReport(row) {
    if (isExtension) return chrome.runtime.sendMessage({ type: "NIMS_FETCH_REPORT", row });
    const response = await fetch(row.source_url);
    const buffer = await response.arrayBuffer();
    return { ok: response.ok, status: response.status, finalUrl: response.url, base64: arrayBufferToBase64(buffer) };
  }

  async function parseReport(row, fetched) {
    return postJson(`${HELPER}/parse-report`, {
      report_id: row.report_id,
      report_name: row.report_name,
      date_sent: row.date_sent,
      source_url: fetched.finalUrl || row.source_url,
      pdf_base64: fetched.base64 || "",
      content_type: fetched.contentType || ""
    });
  }

  async function postJson(url, body) {
    const response = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
    if (!response.ok) throw new Error(`${url} returned ${response.status}`);
    return response.json();
  }

  function rowError(row, error) {
    return {
      report_id: row.report_id,
      report_name: row.report_name,
      date_sent: row.date_sent,
      report_type: row.report_type || utils.firstReportType(row.report_tags),
      report_tags: row.report_tags || ["other"],
      parameters: [],
      culture: null,
      errors: [error || "unable to parse / verify source report"]
    };
  }

  function arrayBufferToBase64(buffer) {
    let binary = "";
    const bytes = new Uint8Array(buffer);
    for (let i = 0; i < bytes.byteLength; i += 1) binary += String.fromCharCode(bytes[i]);
    return btoa(binary);
  }

  async function saveState(state) {
    const sanitized = utils.sanitizeState(state, DEBUG_MODE);
    if (isExtension) {
      await chrome.storage.local.set({ nimsFastSummaryState: sanitized });
    } else {
      window.nimsFastSummaryState = sanitized;
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }

  window.NimsFastSummary = {
    extractReportRows: () => utils.extractReportRows(document, location.href),
    selectRowsForMode: utils.selectRowsForMode,
    runSummary,
    scanAndInject
  };
})();
