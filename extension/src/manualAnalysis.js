(function (root) {
  if (root.__NIMS_MANUAL_ANALYSIS_INSTALLED__) return;
  root.__NIMS_MANUAL_ANALYSIS_INSTALLED__ = true;

  const utils = root.NimsManualAnalysisUtils;
  let running = false;

  function api() {
    return root.NimsFastSummary || null;
  }

  function visibleThroughFrameChain() {
    try {
      let win = root.window || root;
      while (win && win.frameElement) {
        const frame = win.frameElement;
        if (frame.hidden || frame.getAttribute("aria-hidden") === "true") return false;
        const style = frame.ownerDocument && frame.ownerDocument.defaultView
          ? frame.ownerDocument.defaultView.getComputedStyle(frame)
          : null;
        if (style && (style.display === "none" || style.visibility === "hidden" || style.visibility === "collapse" || Number(style.opacity) === 0)) return false;
        win = win.parent;
      }
      return true;
    } catch {
      return true;
    }
  }

  function currentRows() {
    const summaryApi = api();
    if (!summaryApi || typeof summaryApi.extractReportRows !== "function") return [];
    const rows = summaryApi.extractReportRows() || [];
    return rows.filter((row) => utils && utils.isGenuineReportRow(row));
  }

  function probe() {
    const rows = currentRows();
    let frameId = "";
    try { frameId = root.frameElement && root.frameElement.id || ""; } catch { frameId = ""; }
    return {
      ready: rows.length > 0,
      rowCount: rows.length,
      visible: visibleThroughFrameChain(),
      exactResultFrame: frameId === "Cr No Wise Result Report Printing New_iframe",
      safePath: safePath(root.location && root.location.href),
      title: String(root.document && root.document.title || "").slice(0, 80)
    };
  }

  async function send(message) {
    if (!root.chrome || !chrome.runtime || !chrome.runtime.sendMessage) throw new Error("Chrome extension runtime is unavailable.");
    const response = await chrome.runtime.sendMessage(message);
    return response || null;
  }

  function setProgress(message) {
    const progress = root.document && root.document.getElementById("nims-summary-progress");
    if (progress) progress.textContent = message;
    if (root.chrome && chrome.runtime && chrome.runtime.sendMessage) {
      chrome.runtime.sendMessage({ type: "NIMS_PROGRESS", message }).catch(() => {});
    }
  }

  function validated(summary) {
    return Boolean(
      summary
      && summary.status === "validated"
      && summary.lastTestDirectFetch
      && summary.lastTestDirectFetch.ok === true
      && summary.lastTestDirectFetch.parsed === true
    );
  }

  async function waitForValidatedMapping(timeoutMs) {
    const started = Date.now();
    while (Date.now() - started < timeoutMs) {
      const response = await send({ type: "NIMS_GET_MAPPING_SUMMARY" });
      if (response && response.ok && validated(response.summary)) return response.summary;
      await delay(500);
    }
    return null;
  }

  async function analyze(mode) {
    const finalMode = ["bulk_fast", "bulk_cultures_only", "bulk_full"].includes(mode) ? mode : "bulk_fast";
    if (running) return { ok: false, error: "Analysis is already running." };
    running = true;
    try {
      const summaryApi = api();
      if (!summaryApi) throw new Error("NIMS analysis script is not available in this frame.");
      const rows = currentRows();
      if (!rows.length) throw new Error("No visible report-result rows were found. Navigate manually to the submitted CR report list and retry.");

      setProgress(`Found ${rows.length} visible reports. Checking helper…`);
      const helper = await send({ type: "NIMS_HELPER_HEALTH" });
      if (!helper || !helper.ok) throw new Error((helper && helper.error) || "The report-processing helper is unavailable.");

      let mappingResponse = await send({ type: "NIMS_GET_MAPPING_SUMMARY" });
      if (!validated(mappingResponse && mappingResponse.summary)) {
        setProgress("Learning the report request from one visible row…");
        if (typeof summaryApi.clearMapping === "function") await summaryApi.clearMapping();
        const discovery = await summaryApi.discoverMapping();
        if (!discovery || !discovery.ok) throw new Error((discovery && discovery.error) || "Could not learn the report request from the visible result row.");

        setProgress("Testing one report before bulk analysis…");
        await summaryApi.runSummary("test_direct");
        const mapping = await waitForValidatedMapping(30000);
        if (!mapping) throw new Error("One-report validation did not succeed. Keep the report list visible and retry; use advanced diagnostics only if this repeats.");
      }

      setProgress(`Mapping validated. Starting ${modeLabel(finalMode)}…`);
      await summaryApi.runSummary(finalMode);
      setProgress("Analysis complete.");
      return { ok: true, rowCount: rows.length, mode: finalMode };
    } catch (error) {
      const message = error && error.message ? error.message : "Analysis failed.";
      setProgress(`Error: ${message}`);
      return { ok: false, error: message };
    } finally {
      running = false;
    }
  }

  function simplifyInPageToolbar() {
    const toolbar = root.document && root.document.getElementById("nims-fast-summary-toolbar");
    if (!toolbar || toolbar.dataset.manualSimplified === "1") return;
    toolbar.dataset.manualSimplified = "1";
    toolbar.querySelectorAll("button.nims-summary-button").forEach((button) => { button.hidden = true; });
    const button = root.document.createElement("button");
    button.type = "button";
    button.className = "nims-summary-button nims-manual-analysis-button";
    button.textContent = "Analyze Current Results";
    button.addEventListener("click", async () => {
      button.disabled = true;
      try { await analyze("bulk_fast"); } finally { button.disabled = false; }
    });
    const progress = toolbar.querySelector("#nims-summary-progress");
    toolbar.insertBefore(button, progress || null);
  }

  function startToolbarWatcher() {
    simplifyInPageToolbar();
    let checks = 0;
    const interval = root.setInterval(() => {
      checks += 1;
      simplifyInPageToolbar();
      if (checks >= 30 || (root.document && root.document.querySelector(".nims-manual-analysis-button"))) root.clearInterval(interval);
    }, 500);
  }

  function safePath(value) {
    try {
      const url = new URL(value || "");
      return `${url.hostname}${url.pathname}`;
    } catch {
      return "";
    }
  }

  function modeLabel(mode) {
    if (mode === "bulk_full") return "full analysis";
    if (mode === "bulk_cultures_only") return "culture analysis";
    return "fast analysis";
  }

  function delay(ms) { return new Promise((resolve) => root.setTimeout(resolve, ms)); }

  root.NimsManualAnalysis = { probe, analyze };
  if (root.document) {
    if (root.document.readyState === "loading") root.document.addEventListener("DOMContentLoaded", startToolbarWatcher, { once: true });
    else startToolbarWatcher();
  }
})(typeof globalThis !== "undefined" ? globalThis : window);
