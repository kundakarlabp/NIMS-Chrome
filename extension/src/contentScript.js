(function () {
  const HELPER = "http://127.0.0.1:8765";
  const isExtension = typeof chrome !== "undefined" && chrome.runtime && chrome.runtime.sendMessage;

  function init() {
    if (document.getElementById("nims-fast-summary-toolbar")) return;
    if (!hasReportRows() && !location.href.includes("mock_report_list")) return;

    const toolbar = document.createElement("div");
    toolbar.id = "nims-fast-summary-toolbar";
    toolbar.innerHTML = `
      <strong>NIMS Fast Summary</strong>
      <button class="nims-summary-button" data-mode="fast">⚡ Fast Summary</button>
      <button class="nims-summary-button" data-mode="cultures_only">🧫 Cultures Only</button>
      <button class="nims-summary-button" data-mode="full">📋 Full Summary</button>
      <div id="nims-summary-progress">Ready</div>
    `;
    document.body.appendChild(toolbar);
    toolbar.querySelectorAll("button[data-mode]").forEach((button) => {
      button.addEventListener("click", () => runSummary(button.dataset.mode));
    });
  }

  function hasReportRows() {
    return Array.from(document.querySelectorAll("tr")).some((row) =>
      /view\s*report/i.test(row.innerText || "")
    );
  }

  async function runSummary(mode) {
    const progress = document.getElementById("nims-summary-progress");
    const setProgress = (message) => {
      if (progress) progress.textContent = message;
      if (isExtension) chrome.runtime.sendMessage({ type: "NIMS_PROGRESS", message }).catch(() => {});
    };

    try {
      setProgress("Reading report list");
      const rows = extractReportRows();
      const selected = selectRowsForMode(rows, mode);
      await saveState({ mode, rows, selected, progress: "Reading report list", result: null });
      if (isExtension) {
        await chrome.runtime.sendMessage({ type: "NIMS_OPEN_PANEL" });
      }

      const parsedReports = [];
      for (let index = 0; index < selected.length; index += 1) {
        const row = selected[index];
        setProgress(`Fetching report ${index + 1}/${selected.length}`);
        const fetched = await fetchReport(row);
        if (!fetched.ok) {
          parsedReports.push(rowError(row, fetched.error || "Needs manual support"));
          continue;
        }
        setProgress(`Parsing report ${index + 1}/${selected.length}`);
        const parsed = await parseReport(row, fetched);
        parsedReports.push(parsed);
      }

      setProgress("Creating tables");
      const summary = await postJson(`${HELPER}/summarize`, { mode, reports: parsedReports });
      const result = {
        mode,
        reportsFound: rows.length,
        reportsRead: parsedReports.filter((r) => !r.errors || r.errors.length === 0).length,
        reportsSkipped: rows.length - selected.length,
        errors: parsedReports.flatMap((r) => r.errors || []),
        summary
      };
      await saveState({ mode, rows, selected, parsedReports, result, progress: "Done" });
      setProgress("Done");
    } catch (error) {
      setProgress(`Error: ${error.message}`);
      await saveState({ mode, progress: `Error: ${error.message}` });
    }
  }

  function extractReportRows() {
    const rows = [];
    const seen = new Set();
    document.querySelectorAll("tr").forEach((tr, index) => {
      const text = compactText(tr.innerText || tr.textContent || "");
      if (!/view\s*report/i.test(text)) return;
      const cells = Array.from(tr.cells || []).map((cell) => compactText(cell.innerText || ""));
      const link = tr.querySelector("a[href]");
      const button = tr.querySelector("button, input[type='button'], input[type='submit']");
      const clickNode = Array.from(tr.querySelectorAll("[onclick]"))[0];
      const href = link ? link.href || link.getAttribute("href") : "";
      const onclick = (clickNode && clickNode.getAttribute("onclick")) || (button && button.getAttribute("onclick")) || "";
      const parsedUrl = href || parseUrlFromOnclick(onclick);
      const reportName = guessReportName(cells, text);
      const dateSent = guessDate(cells, text);
      const reportId = guessReportId(parsedUrl, onclick, text, index);
      const key = [dateSent, reportName, parsedUrl, reportId].join("|");
      if (seen.has(key)) return;
      seen.add(key);
      rows.push({
        row_index: index,
        date_sent: dateSent,
        department: guessDepartment(cells),
        report_name: reportName,
        href,
        onclick,
        source_url: parsedUrl,
        report_id: reportId,
        raw_row_text: text
      });
    });
    return rows;
  }

  function selectRowsForMode(rows, mode) {
    if (mode === "full") return dedupeRows(rows);
    if (mode === "cultures_only") return dedupeRows(rows.filter(isCultureRow));

    const buckets = { cbc: 0, rft: 0, electrolytes: 0, lft: 0, coagulation: 0 };
    const selected = [];
    for (const row of dedupeRows(rows)) {
      const type = inferTypeFromName(row.report_name);
      const name = row.report_name.toLowerCase();
      if (isCultureRow(row)) selected.push(row);
      else if (name.includes("crp") || name.includes("procalcitonin")) selected.push(row);
      else if (type in buckets && buckets[type] < (type === "coagulation" ? 3 : 5)) {
        selected.push(row);
        buckets[type] += 1;
      }
    }
    return selected;
  }

  function dedupeRows(rows) {
    const seen = new Set();
    return rows.filter((row) => {
      const key = [row.report_name, row.date_sent, row.report_id || row.source_url].join("|");
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }

  async function fetchReport(row) {
    if (!row.source_url) {
      return { ok: false, error: "No direct URL detected; POST/form workflow may need live-site support" };
    }
    if (isExtension) {
      return chrome.runtime.sendMessage({ type: "NIMS_FETCH_REPORT", row });
    }
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
      pdf_base64: fetched.base64 || ""
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
      report_type: inferTypeFromName(row.report_name),
      parameters: [],
      culture: null,
      raw_text_preview: "",
      errors: [error]
    };
  }

  function parseUrlFromOnclick(onclick) {
    if (!onclick) return "";
    const quoted = onclick.match(/['"]([^'"]+(?:Report|report|pdf|PDF)[^'"]*)['"]/);
    if (quoted) return new URL(quoted[1], location.href).href;
    const anyUrl = onclick.match(/https?:\/\/[^'")\s]+/);
    return anyUrl ? anyUrl[0] : "";
  }

  function guessReportName(cells, text) {
    const candidates = cells.filter((cell) => cell && !/view\s*report/i.test(cell) && !looksLikeDate(cell));
    return candidates.find((cell) => /cbc|blood|renal|rft|liver|lft|culture|electrolyte|coag|crp|procalcitonin|urine|sputum/i.test(cell))
      || candidates[Math.min(2, candidates.length - 1)]
      || text.slice(0, 80);
  }

  function guessDate(cells, text) {
    return cells.find(looksLikeDate) || (text.match(/\b\d{1,2}[-/][A-Za-z]{3}[-/]\d{2,4}\b|\b\d{1,2}[-/]\d{1,2}[-/]\d{2,4}\b/) || [""])[0];
  }

  function guessDepartment(cells) {
    return cells.find((cell) => /pathology|microbiology|biochemistry|hematology|radiology/i.test(cell)) || "";
  }

  function guessReportId(url, onclick, text, index) {
    const source = `${url} ${onclick} ${text}`;
    const match = source.match(/(?:reportId|requisitionNo|reqNo|labNo|id)\s*[=:]\s*([A-Za-z0-9\-_/]+)/i);
    return match ? match[1] : `row-${index}`;
  }

  function inferTypeFromName(name) {
    const lower = (name || "").toLowerCase();
    if (/culture|sensitivity|microbiology/.test(lower)) return "culture";
    if (/cbc|hemogram|blood count/.test(lower)) return "cbc";
    if (/rft|urea|creatinine|renal/.test(lower)) return "rft";
    if (/electrolyte|sodium|potassium/.test(lower)) return "electrolytes";
    if (/lft|liver|bilirubin|sgot|sgpt/.test(lower)) return "lft";
    if (/pt|inr|aptt|coag/.test(lower)) return "coagulation";
    if (/xray|x-ray|ct|mri|usg|radiology/.test(lower)) return "radiology";
    return "other";
  }

  function isCultureRow(row) {
    return inferTypeFromName(`${row.report_name} ${row.department} ${row.raw_row_text}`) === "culture";
  }

  function looksLikeDate(value) {
    return /\b\d{1,2}[-/][A-Za-z]{3}[-/]\d{2,4}\b|\b\d{1,2}[-/]\d{1,2}[-/]\d{2,4}\b/.test(value || "");
  }

  function compactText(value) {
    return (value || "").replace(/\s+/g, " ").trim();
  }

  function arrayBufferToBase64(buffer) {
    let binary = "";
    const bytes = new Uint8Array(buffer);
    for (let i = 0; i < bytes.byteLength; i += 1) binary += String.fromCharCode(bytes[i]);
    return btoa(binary);
  }

  async function saveState(state) {
    if (isExtension) {
      await chrome.storage.local.set({ nimsFastSummaryState: state });
    } else {
      window.nimsFastSummaryState = state;
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }

  window.NimsFastSummary = { extractReportRows, selectRowsForMode, runSummary };
})();

