(function (root) {
  const NIMS_ALLOWED_HOSTS = new Set(["nimsts.edu.in", "www.nimsts.edu.in"]);

  function diagnosePage(doc) {
    const frames = collectFrames(doc || root.document);
    const best = frames.slice().sort((a, b) => b.viewReportRows - a.viewReportRows)[0] || null;
    return {
      activeUrl: safeHostPath(root.location && root.location.href),
      framesChecked: frames.length,
      bestFramePath: best ? best.url : "",
      viewReportRows: best ? best.viewReportRows : 0,
      printReportRows: best ? best.printReportRows : 0,
      setPdfTemplateDiscovered: Boolean(best && best.setPdfTemplate && best.setPdfTemplate.discovered)
    };
  }

  function bestReportDocument(doc) {
    return accessibleDocuments(doc || root.document)
      .map((item) => ({ doc: item.doc, rows: extractReportRows(item.doc, item.url) }))
      .sort((a, b) => b.rows.length - a.rows.length)[0] || { doc: doc || root.document, rows: [] };
  }

  function collectFrames(doc) {
    return accessibleDocuments(doc || root.document).map((item) => frameDiagnostic(item.doc, item.url));
  }

  function accessibleDocuments(doc) {
    const out = [{ doc: doc || root.document, url: root.location && root.location.href }];
    Array.from((doc || root.document).querySelectorAll("iframe")).forEach((frame) => {
      try {
        if (frame.contentDocument) out.push({ doc: frame.contentDocument, url: frame.src || "" });
      } catch {
        // Cross-origin frames are intentionally ignored by active logic.
      }
    });
    return out;
  }

  function frameDiagnostic(doc, url) {
    const rows = extractReportRows(doc, url);
    return {
      url: safeHostPath(url || (doc.location && doc.location.href)),
      viewReportRows: rows.length,
      printReportRows: rows.filter((row) => row.onclick_function_name === "printReport").length,
      setPdfTemplate: getSafeSetPdfTemplate(doc)
    };
  }

  function extractReportRows(doc, baseUrl) {
    const rows = [];
    const buttons = Array.from((doc || root.document).querySelectorAll("a, button, input[type='button'], input[type='submit']"))
      .filter((node) => /view\s*report/i.test(textOf(node) || node.value || ""));
    Array.from((doc || root.document).querySelectorAll("tr")).forEach((tr, index) => {
      const rowText = compactText(textOf(tr));
      if (!/view\s*report/i.test(rowText)) return;
      const cells = Array.from(tr.cells || []).map((cell) => compactText(textOf(cell)));
      const button = Array.from(tr.querySelectorAll("[onclick]"))[0];
      const onclick = button ? button.getAttribute("onclick") || "" : "";
      const parsed = parseFunctionArgs(onclick);
      const tags = inferReportTags(`${guessReportName(cells, rowText)} ${rowText}`);
      rows.push({
        row_index: index,
        view_report_button_index: buttons.indexOf(button),
        date_sent: guessDate(cells, rowText),
        report_name: guessReportName(cells, rowText),
        department: guessDepartment(cells),
        report_tags: tags,
        report_type: tags[0] || "other",
        onclick_function_name: parsed.functionName,
        onclick_arg_count: parsed.args.length,
        has_print_report_arg: parsed.functionName === "printReport" && parsed.args.length === 1,
        source_host_path: safeHostPath(baseUrl || "")
      });
    });
    return selectLatestRows(rows);
  }

  function discoverSetPdfTemplate(doc) {
    const best = bestReportDocument(doc || root.document);
    return getSafeSetPdfTemplate(best.doc);
  }

  function getSafeSetPdfTemplate(doc) {
    const frame = (doc || root.document).querySelector("iframe#setPdf");
    const src = frame ? frame.getAttribute("src") || "" : "";
    if (!src) return null;
    const resolved = resolveUrl(src, root.location && root.location.href);
    try {
      const parsed = new URL(resolved);
      const names = Array.from(parsed.searchParams.keys());
      const modeParamName = names.find((name) => /^hmode$/i.test(name) && parsed.searchParams.get(name) === "PRINTREPORT") || "";
      const argumentParameterName = names.find((name) => /^filename$/i.test(name)) || "";
      if (!NIMS_ALLOWED_HOSTS.has(parsed.hostname) || !modeParamName || !argumentParameterName) return null;
      return {
        discovered: true,
        endpoint: `${parsed.hostname}${parsed.pathname}`,
        origin: parsed.origin,
        pathname: parsed.pathname,
        queryParamNames: names,
        modeParamName,
        modeParamValue: "PRINTREPORT",
        argumentParameterName
      };
    } catch {
      return null;
    }
  }

  function rowsFromBestFrame(doc) {
    return bestReportDocument(doc || root.document).rows;
  }

  function clickFirstReportForMode(mode, doc) {
    const best = bestReportDocument(doc || root.document);
    const rowInfo = selectRowsForMode(best.rows, mode || "test_direct")[0];
    if (!rowInfo) return { ok: false, error: "No View Report button found for row" };
    const row = findReportRow(rowInfo, best.doc);
    if (!row) return { ok: false, error: "No View Report button found for row" };
    const button = Array.from(row.querySelectorAll("[onclick]")).find((node) => {
      const parsed = parseFunctionArgs(node.getAttribute("onclick") || "");
      return parsed.functionName === "printReport" && parsed.args.length === 1;
    });
    if (!button) return { ok: false, error: "No View Report button found for row" };
    button.click();
    return { ok: true, row: rowInfo };
  }

  function transientPayloadForRow(rowInfo, doc) {
    const best = bestReportDocument(doc || root.document);
    return getTransientReportPayload(rowInfo, best.doc);
  }

  function getTransientReportPayload(rowInfo, doc) {
    const row = findReportRow(rowInfo, doc || root.document);
    if (!row) return { ok: false, error: "No View Report button found for row" };
    const button = Array.from(row.querySelectorAll("[onclick]")).find((node) => {
      const parsed = parseFunctionArgs(node.getAttribute("onclick") || "");
      return parsed.functionName === "printReport" && parsed.args.length === 1;
    });
    if (!button) return { ok: false, error: "No View Report button found for row" };
    const parsed = parseFunctionArgs(button.getAttribute("onclick") || "");
    return { ok: true, row: rowInfo, transientPrintReportArg: parsed.args[0] || "" };
  }

  function buildReportUrl(template, transientArg) {
    if (!template || !template.origin || !template.pathname || !template.argumentParameterName) return "";
    const url = new URL(template.pathname, template.origin);
    url.searchParams.set(template.modeParamName || "hmode", template.modeParamValue || "PRINTREPORT");
    url.searchParams.set(template.argumentParameterName, transientArg || "");
    return url.href;
  }

  function selectRowsForMode(rows, mode) {
    const sorted = selectLatestRows(rows || []);
    if (mode === "bulk_full") return sorted;
    if (mode === "bulk_cultures_only") return sorted.filter((row) => (row.report_tags || []).includes("culture"));
    if (mode === "test_direct") return sorted.filter((row) => (row.report_tags || []).includes("cbc")).slice(0, 1).concat(sorted.slice(0, 1)).slice(0, 1);
    const selected = [];
    const counts = { cbc: 0, combined: 0 };
    for (const row of sorted) {
      if (selected.length >= 20) break;
      const tags = row.report_tags || [];
      if (tags.includes("culture") || tags.includes("inflammatory")) selected.push(row);
      else if (tags.includes("cbc") && counts.cbc < 3) {
        counts.cbc += 1;
        selected.push(row);
      } else if ((tags.includes("rft") || tags.includes("lft") || tags.includes("electrolytes")) && counts.combined < 3) {
        counts.combined += 1;
        selected.push(row);
      }
    }
    return selected;
  }

  function parseFunctionArgs(onclick) {
    const match = String(onclick || "").match(/([A-Za-z_$][\w$]*)\s*\(([\s\S]*)\)/);
    if (!match) return { functionName: "", args: [] };
    return { functionName: match[1], args: splitArgs(match[2]).map(unquoteArg) };
  }

  function splitArgs(text) {
    const args = [];
    let current = "";
    let quote = "";
    for (let i = 0; i < String(text || "").length; i += 1) {
      const char = text[i];
      if (quote) {
        current += char;
        if (char === quote && text[i - 1] !== "\\") quote = "";
      } else if (char === "'" || char === '"') {
        quote = char;
        current += char;
      } else if (char === ",") {
        args.push(current.trim());
        current = "";
      } else {
        current += char;
      }
    }
    if (current.trim()) args.push(current.trim());
    return args;
  }

  function unquoteArg(value) {
    const text = String(value || "").trim();
    if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith('"') && text.endsWith('"'))) return text.slice(1, -1);
    return text;
  }

  function safeHostPath(url) {
    try {
      const parsed = new URL(url || "");
      return `${parsed.hostname}${parsed.pathname}`;
    } catch {
      return "";
    }
  }

  function inferReportTags(text) {
    const lower = String(text || "").toLowerCase();
    const tags = [];
    if (/culture|sensitivity|microbiology|organism|no growth/.test(lower)) tags.push("culture");
    if (/cbc|hemogram|blood count|hemoglobin|haemoglobin|platelet|tlc|wbc/.test(lower)) tags.push("cbc");
    if (/rft|renal|urea|creatinine/.test(lower)) tags.push("rft");
    if (/electrolyte|sodium|potassium|chloride/.test(lower)) tags.push("electrolytes");
    if (/lft|liver|bilirubin|sgot|sgpt|ast|alt|albumin/.test(lower)) tags.push("lft");
    if (/crp|c reactive protein|procalcitonin/.test(lower)) tags.push("inflammatory");
    return tags.length ? Array.from(new Set(tags)) : ["other"];
  }

  function selectLatestRows(rows) {
    return [...rows].sort((a, b) => parseDateValue(b.date_sent) - parseDateValue(a.date_sent));
  }

  function parseDateValue(value) {
    const month = { jan: 0, feb: 1, mar: 2, apr: 3, may: 4, jun: 5, jul: 6, aug: 7, sep: 8, oct: 9, nov: 10, dec: 11 };
    const match = String(value || "").match(/^(\d{1,2})-([A-Za-z]{3})-(\d{2,4})$/);
    return match ? Date.UTC(Number(match[3]) < 100 ? 2000 + Number(match[3]) : Number(match[3]), month[match[2].toLowerCase()], Number(match[1])) : 0;
  }

  function findReportRow(rowInfo, doc) {
    const rows = Array.from((doc || root.document).querySelectorAll("tr"));
    const index = Number(rowInfo && rowInfo.row_index);
    return Number.isFinite(index) ? rows[index] : null;
  }

  function guessReportName(cells, text) {
    return cells.find((cell) => /cbc|blood|renal|rft|liver|lft|culture|electrolyte|crp|procalcitonin/i.test(cell)) || text.slice(0, 80);
  }

  function guessDate(cells, text) {
    return cells.find((cell) => /\b\d{1,2}-[A-Za-z]{3}-\d{2,4}\b/.test(cell)) || (text.match(/\b\d{1,2}-[A-Za-z]{3}-\d{2,4}\b/) || [""])[0];
  }

  function guessDepartment(cells) {
    return cells.find((cell) => /pathology|microbiology|biochemistry|hematology|radiology/i.test(cell)) || "";
  }

  function resolveUrl(value, baseUrl) {
    try {
      return new URL(value || "", baseUrl || root.location.href).href;
    } catch {
      return "";
    }
  }

  function textOf(node) {
    return node.innerText || node.textContent || "";
  }

  function compactText(value) {
    return String(value || "").replace(/\s+/g, " ").trim();
  }

  const api = { diagnosePage, collectFrames, rowsFromBestFrame, extractReportRows, discoverSetPdfTemplate, getTransientReportPayload, transientPayloadForRow, clickFirstReportForMode, buildReportUrl, selectRowsForMode, parseFunctionArgs, safeHostPath };
  root.NimsReportCore = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : window);
