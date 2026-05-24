(function () {
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
      <button class="nims-summary-button" data-mode="test_direct">Test Direct Fetch</button>
      <button class="nims-summary-button" data-mode="bulk_fast">Bulk Fast Summary</button>
      <button class="nims-summary-button" data-mode="bulk_cultures_only">Bulk Cultures Only</button>
      <button class="nims-summary-button" data-mode="bulk_full">Bulk Full Summary</button>
      <button class="nims-summary-button" data-mode="manual_fallback">Manual Popup Fallback</button>
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

      const parsedReports = mode === "manual_fallback"
        ? await runManualFallback(selected, setProgress)
        : await runDirectBulk(selected, setProgress);

      setProgress("Creating tables");
      const summary = await callHelper("NIMS_HELPER_SUMMARIZE", { mode: helperSummaryMode(mode), reports: parsedReports });
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

  async function discoverMapping() {
    const rows = utils.extractReportRows(document, location.href);
    const selected = utils.selectRowsForMode(rows, "test_direct");
    const payload = selected[0] ? await buildTransientPayload(selected[0]) : null;
    if (!payload || !payload.ok) throw new Error((payload && payload.error) || "No View Report button found for row");
    return chrome.runtime.sendMessage({ type: "NIMS_DISCOVER_MAPPING", rowPayload: payload });
  }

  async function clearMapping() {
    return chrome.runtime.sendMessage({ type: "NIMS_CLEAR_DIRECT_MAPPING" });
  }

  async function runDirectBulk(selected, setProgress) {
    const mapping = await chrome.runtime.sendMessage({ type: "NIMS_GET_MAPPING_SUMMARY" });
    if (!mapping || !mapping.ok || !mapping.summary || mapping.summary.status !== "ready") {
      throw new Error("Direct report mapping not discovered. Click Discover Mapping first.");
    }

    const payloads = [];
    for (const row of selected) {
      const payload = await buildTransientPayload(row);
      if (!payload.ok) {
        payloads.push({ row, error: payload.error || "Direct fetch mapping failed for this report." });
      } else {
        payloads.push(payload);
      }
    }

    const cacheable = payloads.filter((payload) => payload.ok && payload.row.report_id);
    const lookup = cacheable.length
      ? await callHelper("NIMS_HELPER_CACHE_LOOKUP", {
        reports: cacheable.map((payload) => ({
          report_key: payload.row.report_id,
          report_name: payload.row.report_name,
          date_sent: payload.row.date_sent
        }))
      })
      : { hits: {}, misses: [] };
    const hits = (lookup && lookup.hits) || {};
    const parsedReports = new Array(payloads.length);
    const misses = [];
    payloads.forEach((payload, index) => {
      if (!payload.ok) {
        parsedReports[index] = rowError(payload.row, payload.error);
      } else if (hits[payload.row.report_id]) {
        setProgress(`Using cached result ${index + 1}/${payloads.length}`);
        parsedReports[index] = { ...hits[payload.row.report_id], cached: true };
      } else {
        misses.push({
          payload,
          index,
          onError: (error) => {
            parsedReports[index] = rowError(payload.row, error || "Direct fetch mapping failed for this report.");
          }
        });
      }
    });

    await runQueue(misses, 3, async ({ payload, index }) => {
      setProgress(`Direct fetching ${index + 1}/${payloads.length}`);
      const fetched = await chrome.runtime.sendMessage({ type: "NIMS_FETCH_REPORT_DIRECT", rowPayload: payload });
      if (!fetched || !fetched.ok) {
        parsedReports[index] = rowError(payload.row, (fetched && fetched.error) || "Direct fetch mapping failed for this report.");
        return;
      }
      setProgress(`Parsing ${index + 1}/${payloads.length}`);
      try {
        parsedReports[index] = await parseReport(payload.row, fetched);
      } catch (error) {
        parsedReports[index] = rowError(payload.row, error.message || "PDF text extraction failed");
      }
    });
    setProgress("Done");
    return parsedReports.filter(Boolean);
  }

  async function runManualFallback(selected, setProgress) {
    const parsedReports = [];
    for (let index = 0; index < selected.length; index += 1) {
      const row = selected[index];
      setProgress(`Opening report ${index + 1}/${selected.length}`);
      const fetched = await fetchReport(row);
      if (!fetched.ok) {
        parsedReports.push(rowError(row, fetched.error || "unable to parse / verify source report"));
        continue;
      }
      setProgress(`Parsing report ${index + 1}/${selected.length}`);
      parsedReports.push(await parseReport(row, fetched));
    }
    return parsedReports;
  }

  async function fetchReport(row) {
    if (isExtension) return chrome.runtime.sendMessage({ type: "NIMS_FETCH_REPORT", row });
    const response = await fetch(row.source_url);
    const buffer = await response.arrayBuffer();
    return { ok: response.ok, status: response.status, finalUrl: response.url, base64: arrayBufferToBase64(buffer) };
  }

  async function parseReport(row, fetched) {
    return callHelper("NIMS_HELPER_PARSE_REPORT", {
      report_id: row.report_id || row.report_key || "",
      report_name: row.report_name,
      date_sent: row.date_sent,
      source_url: fetched.finalUrlSafeHostPath || fetched.finalUrl || row.source_url || "",
      pdf_base64: fetched.base64 || "",
      content_type: fetched.contentType || ""
    });
  }

  async function buildTransientPayload(row) {
    const payload = utils.getTransientReportRequestPayload(row, document);
    if (!payload.ok) return { ok: false, row, error: payload.error };
    payload.row.report_id = await makeSafeReportKey(payload.transient_print_report_arg, payload.row);
    return payload;
  }

  async function makeSafeReportKey(transientArg, row) {
    const input = [
      transientArg || "",
      row.date_sent || "",
      row.report_name || "",
      row.department || ""
    ].join("|");
    const hash = await sha256Hex(input);
    return `report_key:${hash}`;
  }

  async function sha256Hex(value) {
    const bytes = new TextEncoder().encode(value);
    const digest = await crypto.subtle.digest("SHA-256", bytes);
    return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, "0")).join("");
  }

  async function runQueue(items, concurrency, worker) {
    const limit = Math.min(Math.max(Number(concurrency) || 3, 1), 5);
    let next = 0;
    async function runOne() {
      while (next < items.length) {
        const current = items[next];
        next += 1;
        try {
          await worker(current);
        } catch (error) {
          if (current.onError) current.onError(error.message || "Direct fetch mapping failed for this report.");
        }
      }
    }
    await Promise.all(Array.from({ length: Math.min(limit, items.length) }, runOne));
  }

  function helperSummaryMode(mode) {
    if (mode === "bulk_cultures_only") return "cultures_only";
    if (mode === "bulk_full" || mode === "manual_fallback") return "full";
    return "fast";
  }

  async function callHelper(type, body) {
    if (!isExtension) throw new Error("Local helper calls require the Chrome extension background worker.");
    const response = await chrome.runtime.sendMessage({ type, body });
    if (!response || response.ok === false) {
      throw new Error((response && response.error) || "Local helper request failed");
    }
    return response.data;
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
    discoverMapping,
    clearMapping,
    scanAndInject
  };
})();
