// Lightweight passive Android WebView observer for NIMS.
//
// This script runs in every approved NIMS frame. It observes the page that NIMS
// has already rendered and posts sanitized state/report metadata to Android. It
// does not load helper libraries, patch portal functions, click, submit, or
// navigate.
(function (root) {
  "use strict";

  var ALLOWED_HOSTS = { "nimsts.edu.in": true, "www.nimsts.edu.in": true };
  var REPORT_LIST_PATHS = [
    "viewcrnowisereportprocess.cnt",
    "invresultreportprintingcrnowise.cnt"
  ];
  var REPORT_PDF_PATH = "/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt";
  var MAX_ROWS = 250;

  function safePath(value) {
    try {
      var base = root.location && root.location.href ? root.location.href : "https://www.nimsts.edu.in/";
      var parsed = new URL(value || base, base);
      return parsed.hostname + parsed.pathname;
    } catch (error) {
      return String(value || "").split("?")[0].split("#")[0].slice(0, 180);
    }
  }

  function isAllowedDocument(doc) {
    try {
      var parsed = new URL(doc.location.href);
      return parsed.protocol === "https:" && Boolean(ALLOWED_HOSTS[parsed.hostname]);
    } catch (error) {
      return false;
    }
  }

  function compactText(value) {
    return String(value || "").replace(/\s+/g, " ").trim();
  }

  function nodeText(node) {
    return compactText(node && (node.innerText || node.textContent || node.value || ""));
  }

  // Avoid getComputedStyle/layout reads: the legacy portal mutates large tables
  // frequently and synchronous layout queries make WebView navigation sluggish.
  function isVisible(node) {
    var current = node;
    var depth = 0;
    while (current && depth < 8) {
      if (current.hidden || String(current.getAttribute && current.getAttribute("aria-hidden") || "").toLowerCase() === "true") return false;
      var style = String(current.getAttribute && current.getAttribute("style") || "").toLowerCase();
      if (/display\s*:\s*none|visibility\s*:\s*hidden/.test(style)) return false;
      current = current.parentElement;
      depth += 1;
    }
    return true;
  }

  function hasPasswordInput(doc) {
    if (!doc || !doc.querySelectorAll) return false;
    return Array.prototype.slice.call(doc.querySelectorAll("input[type='password']")).some(isVisible);
  }

  function crInput(doc) {
    if (!doc || !doc.querySelector) return null;
    return doc.querySelector(
      "input[name='patCrNo'], input[id='patCrNo'], input[name*='crno' i], input[id*='crno' i], input[name*='cr_no' i], input[id*='cr_no' i]"
    );
  }

  function isCrForm(form) {
    if (!form) return false;
    var name = String(form.name || form.id || "");
    var action = safePath(form.getAttribute && form.getAttribute("action") || "");
    return name === "viewExternalInvFB" || REPORT_LIST_PATHS.some(function (part) { return action.indexOf(part) >= 0; });
  }

  function hasCrSearchForm(doc) {
    if (!doc || !doc.forms) return false;
    var input = crInput(doc);
    if (!input || !isVisible(input)) return false;
    var forms = Array.prototype.slice.call(doc.forms || []);
    return forms.some(isCrForm);
  }

  function safeTransientToken(value) {
    var token = String(value || "").trim();
    if (token.length < 5 || token.length > 256) return "";
    if (token.indexOf("..") >= 0 || /[\\/\u0000-\u001f\u007f]/.test(token)) return "";
    return /^[A-Za-z0-9_-]+\.pdf$/i.test(token) ? token : "";
  }

  function parsePrintReportToken(onclick) {
    var source = String(onclick || "");
    var match = source.match(/(?:^|[;\s])(?:return\s+)?printReport\s*\(\s*(['"])(.*?)\1\s*\)/i);
    if (!match) return "";
    return safeTransientToken(
      String(match[2] || "")
        .replace(/\\'/g, "'")
        .replace(/\\"/g, '"')
        .replace(/\\\\/g, "\\")
    );
  }

  function inferReportTags(value) {
    var lower = String(value || "").toLowerCase();
    var tags = [];
    if (/culture|sensitivity|microbiology|organism|no growth/.test(lower)) tags.push("culture");
    if (/cbc|hemogram|blood count|ha?emoglobin|platelet|tlc|wbc/.test(lower)) tags.push("cbc");
    if (/rft|renal|urea|creatinine/.test(lower)) tags.push("rft");
    if (/electrolyte|sodium|potassium|chloride/.test(lower)) tags.push("electrolytes");
    if (/lft|liver|bilirubin|sgot|sgpt|ast|alt|albumin/.test(lower)) tags.push("lft");
    if (/crp|c reactive protein|procalcitonin/.test(lower)) tags.push("inflammatory");
    return tags.length ? tags.filter(function (tag, index) { return tags.indexOf(tag) === index; }) : ["other"];
  }

  function guessDate(cells, rowText) {
    var source = cells.join(" ") + " " + rowText;
    var match = source.match(/\b\d{1,2}[-\/]([A-Za-z]{3}|\d{1,2})[-\/]\d{2,4}(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?\b/);
    return match ? match[0] : "";
  }

  function guessDepartment(cells) {
    for (var index = 0; index < cells.length; index += 1) {
      if (/pathology|microbiology|biochemistry|ha?ematology|radiology|immunology|serology/i.test(cells[index])) return cells[index].slice(0, 100);
    }
    return "";
  }

  function guessReportName(cells, rowText) {
    var meaningful = cells.filter(function (cell) {
      return cell.length > 1 && cell.length <= 140 &&
        !/^view\s*report$/i.test(cell) &&
        !/^\d+$/.test(cell) &&
        !/pathology|microbiology|biochemistry|ha?ematology|radiology|immunology|serology/i.test(cell) &&
        !/^\d{1,2}[-\/]/.test(cell);
    });
    for (var index = 0; index < meaningful.length; index += 1) {
      if (/cbc|hemogram|blood|renal|rft|liver|lft|culture|electrolyte|crp|procalcitonin|coagulation|urine|fluid/i.test(meaningful[index])) {
        return meaningful[index];
      }
    }
    return meaningful.length ? meaningful[0] : rowText.replace(/view\s*report/ig, "").trim().slice(0, 100);
  }

  function closestRow(node) {
    var current = node;
    while (current && current.nodeType === 1) {
      if (String(current.tagName || "").toUpperCase() === "TR") return current;
      current = current.parentElement;
    }
    return null;
  }

  function isLikelyReportDocument(doc) {
    if (!doc || !doc.querySelector) return false;
    var path = safePath(doc.location && doc.location.href).toLowerCase();
    if (REPORT_LIST_PATHS.some(function (part) { return path.indexOf(part) >= 0; })) return true;
    return Boolean(doc.querySelector("[onclick*='printReport'], [onclick*='printreport'], iframe#setPdf"));
  }

  function extractReportRows(doc) {
    if (!doc || !doc.querySelectorAll || !isLikelyReportDocument(doc)) return [];
    var allRows = Array.prototype.slice.call(doc.querySelectorAll("tr"));
    var rowIndexByElement = new Map();
    for (var rowIndex = 0; rowIndex < allRows.length; rowIndex += 1) {
      rowIndexByElement.set(allRows[rowIndex], rowIndex);
    }
    var clickNodes = Array.prototype.slice.call(doc.querySelectorAll("[onclick]"));
    var reportButtons = [];
    for (var buttonIndex = 0; buttonIndex < clickNodes.length; buttonIndex += 1) {
      if (parsePrintReportToken(clickNodes[buttonIndex].getAttribute("onclick"))) reportButtons.push(clickNodes[buttonIndex]);
    }

    var results = [];
    var seen = {};
    for (var index = 0; index < reportButtons.length && results.length < MAX_ROWS; index += 1) {
      var button = reportButtons[index];
      var token = parsePrintReportToken(button.getAttribute("onclick"));
      var row = closestRow(button);
      if (!token || !row || !isVisible(row) || seen[token]) continue;
      seen[token] = true;
      var currentRowIndex = rowIndexByElement.get(row);
      if (typeof currentRowIndex !== "number") continue;
      var cells = Array.prototype.slice.call(row.cells || []).map(nodeText);
      var rowText = nodeText(row);
      var reportName = guessReportName(cells, rowText);
      var tags = inferReportTags(reportName + " " + rowText);
      results.push({
        row_index: currentRowIndex,
        view_report_button_index: index,
        date_sent: guessDate(cells, rowText),
        department: guessDepartment(cells),
        report_name: reportName,
        report_type: tags[0] || "other",
        report_tags: tags,
        transientPrintReportArg: token
      });
    }
    return results;
  }

  function safeTemplateFromFrame(doc) {
    try {
      var frame = doc.querySelector("iframe#setPdf");
      var src = frame && frame.getAttribute("src");
      if (!src) return null;
      var parsed = new URL(src, doc.location.href);
      if (!ALLOWED_HOSTS[parsed.hostname] || parsed.pathname !== REPORT_PDF_PATH) return null;
      if (String(parsed.searchParams.get("hmode") || "").toUpperCase() !== "PRINTREPORT") return null;
      if (!parsed.searchParams.has("fileName")) return null;
      return {
        origin: parsed.origin,
        pathname: REPORT_PDF_PATH,
        modeParamName: "hmode",
        modeParamValue: "PRINTREPORT",
        argumentParameterName: "fileName"
      };
    } catch (error) {
      return null;
    }
  }

  function template(doc) {
    var fromFrame = safeTemplateFromFrame(doc);
    if (fromFrame) return fromFrame;
    try {
      var fn = doc.defaultView && doc.defaultView.printReport;
      if (typeof fn !== "function") return null;
      var source = Function.prototype.toString.call(fn);
      if (source.indexOf("invDuplicateResultReportPrinting.cnt") < 0 || source.indexOf("PRINTREPORT") < 0 || source.indexOf("fileName") < 0) return null;
      return {
        origin: new URL(doc.location.href).origin,
        pathname: REPORT_PDF_PATH,
        modeParamName: "hmode",
        modeParamValue: "PRINTREPORT",
        argumentParameterName: "fileName"
      };
    } catch (error) {
      return null;
    }
  }

  function scan(doc) {
    if (!doc || !isAllowedDocument(doc)) return { pageKind: "unknown", rows: [] };
    if (hasPasswordInput(doc)) return { pageKind: "login", rows: [] };
    var rows = extractReportRows(doc);
    if (rows.length) return { pageKind: "cr_results", rows: rows };
    if (hasCrSearchForm(doc)) return { pageKind: "cr_search", rows: [] };
    if (doc.body && doc.body.children && doc.body.children.length > 0) return { pageKind: "portal", rows: [] };
    return { pageKind: "loading", rows: [] };
  }

  function pageKind(doc) {
    return scan(doc).pageKind;
  }

  function buildReport(doc, existingScan) {
    var result = existingScan || scan(doc);
    if (!result.rows || !result.rows.length) return null;
    return {
      type: "nims_report_frame",
      pageKind: "cr_results",
      href: safePath(doc.location && doc.location.href),
      rowCount: result.rows.length,
      rows: result.rows,
      template: template(doc)
    };
  }

  function reportKey(report) {
    if (!report) return "";
    var rows = report.rows || [];
    var tokens = rows.map(function (row) { return String(row.transientPrintReportArg || ""); });
    return String(report.rowCount || 0) + "|" + tokens.join("|");
  }

  var api = {
    safePath: safePath,
    isAllowedDocument: isAllowedDocument,
    hasCrSearchForm: hasCrSearchForm,
    pageKind: pageKind,
    scan: scan,
    safeTransientToken: safeTransientToken,
    parsePrintReportToken: parsePrintReportToken,
    extractReportRows: extractReportRows,
    buildReport: buildReport,
    reportKey: reportKey
  };
  root.NimsPassiveObserverUtil = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;

  if (!root.document || !isAllowedDocument(root.document)) return;
  if (root.__NIMS_PASSIVE_OBSERVER_INSTALLED__) return;
  root.__NIMS_PASSIVE_OBSERVER_INSTALLED__ = true;

  function bridge() { return root.nimsAndroidBridge || null; }
  function post(payload) {
    var target = bridge();
    if (!target || typeof target.postMessage !== "function") return false;
    try {
      target.postMessage(JSON.stringify(payload));
      return true;
    } catch (error) {
      return false;
    }
  }

  var lastPageKey = "";
  var lastReportKey = "";

  function tick() {
    var result = scan(root.document);
    var stateKey = result.pageKind + "|" + safePath(root.location && root.location.href) + "|" + String(result.rows.length);
    if (stateKey !== lastPageKey) {
      lastPageKey = stateKey;
      post({
        type: "nims_page_state",
        pageKind: result.pageKind,
        path: safePath(root.location && root.location.href),
        reportCount: result.rows.length,
        hasCrInput: Boolean(crInput(root.document))
      });
    }

    var report = buildReport(root.document, result);
    if (report) {
      var key = reportKey(report);
      if (key !== lastReportKey) {
        lastReportKey = key;
        post(report);
      }
    } else if (lastReportKey) {
      lastReportKey = "";
      post({
        type: "nims_report_frame",
        pageKind: result.pageKind,
        href: safePath(root.location && root.location.href),
        rowCount: 0,
        rows: [],
        clearReason: "page_changed"
      });
    }
  }

  function start() {
    tick();
    var timer = 0;
    function scheduleTick() {
      if (timer) root.clearTimeout(timer);
      timer = root.setTimeout(function () {
        timer = 0;
        tick();
      }, 450);
    }

    try {
      if (root.MutationObserver && root.document.documentElement) {
        new root.MutationObserver(scheduleTick).observe(root.document.documentElement, {
          childList: true,
          subtree: true
        });
      }
    } catch (error) { /* initial bounded polling remains */ }

    var count = 0;
    var interval = root.setInterval(function () {
      count += 1;
      tick();
      if (count >= 20) root.clearInterval(interval);
    }, 1500);
    root.addEventListener("pageshow", scheduleTick, false);
  }

  if (root.document.readyState === "loading") {
    root.document.addEventListener("DOMContentLoaded", start, { once: true });
  } else {
    start();
  }
})(typeof window !== "undefined" ? window : globalThis);
