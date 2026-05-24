(function (root) {
  const DEBUG_WARNING = "Debug mode may contain PHI. Do not export/share.";

  function hasReportRows(doc) {
    return Array.from(doc.querySelectorAll("tr")).some((row) =>
      /view\s*report/i.test(textOf(row)) && isVisible(row)
    );
  }

  function extractReportRows(doc, baseUrl) {
    const rows = [];
    const seen = new Set();
    doc.querySelectorAll("tr").forEach((tr, index) => {
      const rowText = compactText(textOf(tr));
      if (!/view\s*report/i.test(rowText) || !isVisible(tr)) return;
      const cells = Array.from(tr.cells || []).map((cell) => compactText(textOf(cell)));
      const urlInfo = extractUrlFromNode(tr, baseUrl);
      const reportName = guessReportName(cells, rowText);
      const dateSent = guessDate(cells, rowText);
      const reportTags = inferReportTags(`${reportName} ${guessDepartment(cells)} ${rowText}`);
      const reportId = guessReportId(urlInfo.source_url, urlInfo.onclick, rowText);
      const key = [dateSent, reportName, urlInfo.source_url, reportId].join("|");
      if (seen.has(key)) return;
      seen.add(key);
      rows.push({
        row_index: index,
        date_sent: dateSent,
        department: guessDepartment(cells),
        report_name: reportName,
        report_type: firstReportType(reportTags),
        report_tags: reportTags,
        href: urlInfo.href,
        onclick: urlInfo.onclick,
        source_url: urlInfo.source_url,
        onclick_present: Boolean(urlInfo.onclick),
        onclick_parse_status: urlInfo.onclick_parse_status,
        onclick_diagnostics: urlInfo.onclick_diagnostics,
        global_form_present: urlInfo.global_form_present,
        nearby_input_names: urlInfo.nearby_input_names,
        post_workflow: urlInfo.post_workflow,
        report_id: reportId,
        raw_row_text: rowText,
        status: urlInfo.post_workflow ? "NIMS onclick/form workflow needs specific mapping" : "ready"
      });
    });
    return sortRowsLatestFirst(rows);
  }

  function extractUrlFromNode(row, baseUrl) {
    const link = row.querySelector("a[href]");
    const href = link ? link.getAttribute("href") || "" : "";
    const absoluteHref = href ? resolveUrl(href, baseUrl) : "";
    const clickNode = row.hasAttribute && row.hasAttribute("onclick")
      ? row
      : Array.from(row.querySelectorAll("[onclick]"))[0];
    const onclick = clickNode ? clickNode.getAttribute("onclick") || "" : "";
    const onclickDiagnostics = analyzeOnclickPattern(onclick);
    const parsedUrl = absoluteHref || parseUrlFromOnclick(onclick, baseUrl);
    const globalFormPresent = hasGlobalPostForm(row);
    const postWorkflow = detectPostWorkflow(row);
    return {
      href: absoluteHref,
      onclick,
      source_url: parsedUrl,
      onclick_parse_status: parsedUrl ? "parsed" : (onclick ? "needs_mapping" : "unsupported"),
      onclick_diagnostics: onclickDiagnostics,
      global_form_present: globalFormPresent,
      nearby_input_names: nearbyInputNames(row),
      post_workflow: postWorkflow
    };
  }

  function parseUrlFromOnclick(onclick, baseUrl) {
    if (!onclick) return "";
    const absolute = onclick.match(/https?:\/\/[^'")\s]+|data:[^'")\s]+/i);
    if (absolute) return absolute[0];
    const windowOpen = onclick.match(/window\.open\s*\(\s*['"]([^'"]+)['"]/i);
    if (windowOpen) return resolveUrl(windowOpen[1], baseUrl);
    const openReport = onclick.match(/openReport\s*\(\s*['"]([^'"]+)['"]/i);
    if (openReport) return resolveUrl(openReport[1], baseUrl);
    const quoted = onclick.match(/['"]([^'"]+)['"]/);
    if (quoted && looksLikeUrlPath(quoted[1])) return resolveUrl(quoted[1], baseUrl);
    return "";
  }

  function detectPostWorkflow(row) {
    const clickNode = row.hasAttribute && row.hasAttribute("onclick")
      ? row
      : Array.from(row.querySelectorAll ? row.querySelectorAll("[onclick]") : [])[0];
    const onclick = clickNode ? clickNode.getAttribute("onclick") || "" : "";
    const form = row.closest("form") || row.querySelector("form");
    if (onclick) {
      const diagnostics = analyzeOnclickPattern(onclick);
      return (/\bsubmit\s*\(|__doPostBack/i.test(onclick) || /submit/i.test(diagnostics.functionName || "")) && !parseUrlFromOnclick(onclick, root.location && root.location.href);
    }
    if (form && String(form.getAttribute("method") || "").toLowerCase() === "post") return true;
    const text = `${row.outerHTML || ""} ${row.getAttribute("onclick") || ""}`;
    return /\bsubmit\s*\(|__doPostBack|method\s*=\s*["']?post/i.test(text);
  }

  function hasGlobalPostForm(row) {
    const form = row.closest ? row.closest("form") : null;
    return Boolean(form && String(form.getAttribute("method") || "").toLowerCase() === "post");
  }

  function analyzeOnclickPattern(onclick) {
    const text = String(onclick || "").trim();
    if (!text) return { onclickPattern: "", functionName: "", argumentCount: 0, argumentKinds: [] };
    const match = text.match(/([A-Za-z_$][\w$]*)\s*\(([\s\S]*)\)/);
    const functionName = match ? match.group?.(1) || match[1] : "";
    const args = match ? splitArgs(match[2]) : [];
    return {
      onclickPattern: functionName ? `${functionName}(args: ${args.length})` : "inline(args: 0)",
      functionName,
      argumentCount: args.length,
      argumentKinds: args.map(classifyArgument)
    };
  }

  function splitArgs(argText) {
    const args = [];
    let current = "";
    let quote = "";
    for (let i = 0; i < argText.length; i += 1) {
      const char = argText[i];
      if (quote) {
        current += char;
        if (char === quote && argText[i - 1] !== "\\") quote = "";
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

  function classifyArgument(arg) {
    const value = String(arg || "").replace(/^['"]|['"]$/g, "");
    if (/^\d+$/.test(value)) return "number_like";
    if (/(sample|lab|req|report|invest|test|crno|episode)/i.test(value)) return "sample_like";
    if (/^https?:\/\//i.test(value) || looksLikeUrlPath(value)) return "url_like";
    if (!value) return "empty";
    return "unknown";
  }

  function nearbyInputNames(row) {
    const scope = (row.closest && row.closest("form")) || row.parentElement || row;
    return unique(Array.from(scope.querySelectorAll ? scope.querySelectorAll("input, select, textarea") : [])
      .map((input) => input.getAttribute("name") || input.getAttribute("id") || "")
      .filter(Boolean)
      .slice(0, 20));
  }

  function selectRowsForMode(rows, mode) {
    const sorted = sortRowsLatestFirst(dedupeRows(rows));
    if (mode === "full") return sorted;
    if (mode === "cultures_only") return sorted.filter((row) => hasTag(row, "culture"));

    const counts = { cbc: 0, chemistry: 0 };
    const selected = [];
    for (const row of sorted) {
      const tags = row.report_tags || inferReportTags(row.report_name || "");
      if (tags.includes("culture")) {
        selected.push(row);
        continue;
      }
      if (tags.includes("cbc") && counts.cbc < 3) {
        selected.push(row);
        counts.cbc += 1;
        continue;
      }
      if (tags.some((tag) => ["rft", "electrolytes", "lft"].includes(tag)) && counts.chemistry < 3) {
        selected.push(row);
        counts.chemistry += 1;
      }
    }
    return selected;
  }

  function sanitizeState(state, debugMode) {
    const safe = {
      debugMode: Boolean(debugMode),
      debugWarning: debugMode ? DEBUG_WARNING : "",
      mode: state.mode,
      progress: state.progress,
      rows: sanitizeRows(state.rows || [], debugMode),
      selected: sanitizeRows(state.selected || [], debugMode),
      parsedReports: sanitizeParsedReports(state.parsedReports || []),
      result: sanitizeResult(state.result)
    };
    return safe;
  }

  function sanitizeRows(rows, debugMode) {
    return rows.map((row) => {
      const safe = {
        date_sent: row.date_sent || "",
        department: row.department || "",
        report_name: row.report_name || "",
        report_type: row.report_type || firstReportType(row.report_tags || []),
        report_tags: row.report_tags || [],
        status: row.status || "",
        onclick_present: Boolean(row.onclick_present),
        onclick_parse_status: row.onclick_parse_status || "",
        onclick_diagnostics: row.onclick_diagnostics || null,
        global_form_present: Boolean(row.global_form_present),
        post_workflow: Boolean(row.post_workflow),
        nearby_input_names: row.nearby_input_names || [],
        report_id: isSafeReportId(row.report_id) ? row.report_id : "",
        errors: row.errors || []
      };
      if (debugMode) {
        safe.debug_warning = DEBUG_WARNING;
        safe.raw_row_text = row.raw_row_text || "";
        safe.onclick = row.onclick || "";
        safe.href = row.href || "";
        safe.source_url = row.source_url || "";
      }
      return safe;
    });
  }

  function sanitizeParsedReports(reports) {
    return reports.map((report) => ({
      report_id: isSafeReportId(report.report_id) ? report.report_id : "",
      report_name: report.report_name || "",
      date_sent: report.date_sent || "",
      report_type: report.report_type || "other",
      report_tags: report.report_tags || [report.report_type || "other"],
      parameters: report.parameters || [],
      culture: report.culture || null,
      errors: report.errors || [],
      cached: Boolean(report.cached)
    }));
  }

  function sanitizeResult(result) {
    if (!result) return null;
    return JSON.parse(JSON.stringify(result, (key, value) => {
      if (["raw_row_text", "onclick", "href", "source_url", "raw_text_preview"].includes(key)) return undefined;
      return value;
    }));
  }

  function sortRowsLatestFirst(rows) {
    return [...rows].sort((a, b) => {
      const aDate = parseDateValue(a.date_sent);
      const bDate = parseDateValue(b.date_sent);
      if (hasTag(a, "culture") && !hasTag(b, "culture") && !aDate && !bDate) return -1;
      if (!hasTag(a, "culture") && hasTag(b, "culture") && !aDate && !bDate) return 1;
      return (bDate || 0) - (aDate || 0);
    });
  }

  function parseDateValue(value) {
    const text = String(value || "").trim();
    if (!text) return 0;
    const month = { jan: 0, feb: 1, mar: 2, apr: 3, may: 4, jun: 5, jul: 6, aug: 7, sep: 8, oct: 9, nov: 10, dec: 11 };
    let match = text.match(/^(\d{1,2})-([A-Za-z]{3})-(\d{2,4})$/);
    if (match) {
      const year = normalizeYear(match[3]);
      return Date.UTC(year, month[match[2].toLowerCase()], Number(match[1]));
    }
    match = text.match(/^(\d{1,2})[/-](\d{1,2})[/-](\d{2,4})$/);
    if (match) return Date.UTC(normalizeYear(match[3]), Number(match[2]) - 1, Number(match[1]));
    match = text.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
    if (match) return Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
    return 0;
  }

  function normalizeYear(year) {
    const number = Number(year);
    return number < 100 ? 2000 + number : number;
  }

  function inferReportTags(text) {
    const lower = String(text || "").toLowerCase();
    const tags = [];
    if (/culture|sensitivity|microbiology|organism|no growth/.test(lower)) tags.push("culture");
    if (/cbc|hemogram|blood count|hemoglobin|haemoglobin|platelet|tlc|wbc/.test(lower)) tags.push("cbc");
    if (/rft|renal|urea|bun|creatinine/.test(lower)) tags.push("rft");
    if (/electrolyte|sodium|potassium|chloride|bicarbonate/.test(lower)) tags.push("electrolytes");
    if (/lft|liver|bilirubin|sgot|sgpt|ast|alt|albumin|alkaline phosphatase|\balp\b/.test(lower)) tags.push("lft");
    if (/coag|prothrombin|\bpt\b|\binr\b|aptt/.test(lower)) tags.push("coagulation");
    if (/crp|c reactive protein|procalcitonin/.test(lower)) tags.push("inflammatory");
    if (/xray|x-ray|\bct\b|mri|usg|ultrasound|radiology/.test(lower)) tags.push("radiology");
    return tags.length ? unique(tags) : ["other"];
  }

  function firstReportType(tags) {
    return (tags || []).find((tag) => tag !== "inflammatory") || "other";
  }

  function isSafeReportId(reportId) {
    const value = String(reportId || "").trim();
    if (!value) return false;
    if (/^row-\d+$/i.test(value)) return false;
    if (/^(index|idx|generated|temp|tmp)[-_]?\d+$/i.test(value)) return false;
    if (/^\d{1,3}$/.test(value)) return false;
    return value.length >= 4;
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

  function guessReportName(cells, text) {
    const candidates = cells.filter((cell) => cell && !/view\s*report/i.test(cell) && !looksLikeDate(cell));
    return candidates.find((cell) => /cbc|blood|renal|rft|liver|lft|culture|electrolyte|coag|crp|procalcitonin|urine|sputum/i.test(cell))
      || candidates[Math.min(2, Math.max(candidates.length - 1, 0))]
      || text.slice(0, 80);
  }

  function guessDate(cells, text) {
    return cells.find(looksLikeDate) || (text.match(/\b\d{1,2}[-/][A-Za-z]{3}[-/]\d{2,4}\b|\b\d{1,2}[-/]\d{1,2}[-/]\d{2,4}\b|\b\d{4}-\d{1,2}-\d{1,2}\b/) || [""])[0];
  }

  function guessDepartment(cells) {
    return cells.find((cell) => /pathology|microbiology|biochemistry|hematology|radiology/i.test(cell)) || "";
  }

  function guessReportId(url, onclick, text) {
    const source = `${url || ""} ${onclick || ""} ${text || ""}`;
    const match = source.match(/(?:reportId|requisitionNo|reqNo|labNo|accessionNo|sampleNo|cultureNo|id)\s*[=:]\s*([A-Za-z0-9\-_/]+)/i);
    return match ? match[1] : "";
  }

  function looksLikeDate(value) {
    return /\b\d{1,2}[-/][A-Za-z]{3}[-/]\d{2,4}\b|\b\d{1,2}[-/]\d{1,2}[-/]\d{2,4}\b|\b\d{4}-\d{1,2}-\d{1,2}\b/.test(value || "");
  }

  function looksLikeUrlPath(value) {
    return /^(?:\/|\.\/|\.\.\/|[A-Za-z0-9_-]+\/|[A-Za-z0-9_-]+\?)/.test(value || "");
  }

  function resolveUrl(value, baseUrl) {
    if (!value) return "";
    if (/^data:/i.test(value)) return value;
    try {
      return new URL(value, baseUrl || root.location.href).href;
    } catch {
      return "";
    }
  }

  function hasTag(row, tag) {
    return (row.report_tags || []).includes(tag);
  }

  function compactText(value) {
    return (value || "").replace(/\s+/g, " ").trim();
  }

  function textOf(node) {
    return node.innerText || node.textContent || "";
  }

  function isVisible(node) {
    if (!node) return false;
    if (node.hidden) return false;
    const style = root.getComputedStyle ? root.getComputedStyle(node) : null;
    return !style || (style.display !== "none" && style.visibility !== "hidden");
  }

  function unique(values) {
    return Array.from(new Set(values));
  }

  const api = {
    DEBUG_WARNING,
    hasReportRows,
    extractReportRows,
    extractUrlFromNode,
    parseUrlFromOnclick,
    detectPostWorkflow,
    hasGlobalPostForm,
    analyzeOnclickPattern,
    splitArgs,
    classifyArgument,
    nearbyInputNames,
    selectRowsForMode,
    sanitizeState,
    sanitizeRows,
    sanitizeParsedReports,
    sanitizeResult,
    sortRowsLatestFirst,
    parseDateValue,
    inferReportTags,
    firstReportType,
    isSafeReportId
  };

  root.NimsFastSummaryUtils = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : window);
