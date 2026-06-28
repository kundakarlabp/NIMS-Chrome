// Passive Android WebView observer for NIMS.
//
// This script runs in every approved NIMS frame. It observes the existing page,
// classifies the current portal state, and announces sanitized report-row
// metadata to Android. It never patches jQuery, defines NIMS globals, clicks
// menus, submits forms, or changes navigation.
(function (root) {
  "use strict";

  var ALLOWED_HOSTS = { "nimsts.edu.in": true, "www.nimsts.edu.in": true };
  var REPORT_LIST_PATHS = [
    "viewcrnowisereportprocess.cnt",
    "invresultreportprintingcrnowise.cnt"
  ];
  var REPORT_PDF_PATH = "/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt";

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

  function isVisible(node) {
    if (!node || node.hidden || node.isConnected === false) return false;
    try {
      var style = root.getComputedStyle ? root.getComputedStyle(node) : null;
      if (style && (style.display === "none" || style.visibility === "hidden")) return false;
    } catch (error) { /* visibility remains best effort */ }
    return true;
  }

  function hasPasswordInput(doc) {
    return Boolean(doc && doc.querySelector && doc.querySelector("input[type='password']"));
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
    var forms = Array.prototype.slice.call(doc.forms || []);
    return Boolean(crInput(doc)) && forms.some(isCrForm);
  }

  function utils() {
    return root.NimsFastSummaryUtils || null;
  }

  function visibleReportRows(doc) {
    var u = utils();
    if (u && typeof u.extractReportRows === "function") {
      try { return u.extractReportRows(doc, doc.location.href) || []; }
      catch (error) { return []; }
    }
    if (!doc || !doc.querySelectorAll) return [];
    var rows = Array.prototype.slice.call(doc.querySelectorAll("tr"));
    var matched = [];
    for (var index = 0; index < rows.length; index += 1) {
      var row = rows[index];
      if (isVisible(row) && /view\s*report/i.test(compactText(row.innerText || row.textContent))) {
        matched.push({ row_index: index });
      }
    }
    return matched;
  }

  function pageKind(doc) {
    if (!doc || !isAllowedDocument(doc)) return "unknown";
    if (hasPasswordInput(doc)) return "login";
    var rows = visibleReportRows(doc);
    if (rows.length) return "cr_results";
    if (hasCrSearchForm(doc)) return "cr_search";
    var path = safePath(doc.location && doc.location.href).toLowerCase();
    if (/loginlogin\.action|login\.action|hissso/.test(path) && hasPasswordInput(doc)) return "login";
    if (doc.body && compactText(doc.body.textContent).length > 0) return "portal";
    return "loading";
  }

  function safeTransientToken(value) {
    var token = String(value || "").trim();
    if (token.length < 5 || token.length > 256) return "";
    if (token.indexOf("..") >= 0 || /[\\/\u0000-\u001f\u007f]/.test(token)) return "";
    return /^[A-Za-z0-9_-]+\.pdf$/i.test(token) ? token : "";
  }

  function safeRuntimeRow(u, rowInfo) {
    if (u && typeof u.safeRuntimeRow === "function") {
      try { return u.safeRuntimeRow(rowInfo); } catch (error) { /* fall through */ }
    }
    return {
      row_index: Number(rowInfo && rowInfo.row_index),
      view_report_button_index: Number(rowInfo && rowInfo.view_report_button_index),
      date_sent: String(rowInfo && rowInfo.date_sent || ""),
      department: String(rowInfo && rowInfo.department || ""),
      report_name: String(rowInfo && rowInfo.report_name || ""),
      report_type: String(rowInfo && rowInfo.report_type || "other"),
      report_tags: Array.isArray(rowInfo && rowInfo.report_tags) ? rowInfo.report_tags : []
    };
  }

  function transientToken(u, doc, rowInfo) {
    if (!u || typeof u.getTransientReportRequestPayload !== "function") return "";
    try {
      var payload = u.getTransientReportRequestPayload(rowInfo, doc);
      return safeTransientToken(payload && (payload.transientPrintReportArg || payload.transient_print_report_arg));
    } catch (error) {
      return "";
    }
  }

  function template(u, doc) {
    if (u && typeof u.getSafeSetPdfTemplate === "function") {
      try {
        var discovered = u.getSafeSetPdfTemplate(doc);
        if (discovered && discovered.discovered && discovered.pathname === REPORT_PDF_PATH) {
          return {
            origin: discovered.origin,
            pathname: REPORT_PDF_PATH,
            modeParamName: "hmode",
            modeParamValue: "PRINTREPORT",
            argumentParameterName: "fileName"
          };
        }
      } catch (error) { /* use verified live-function fallback */ }
    }
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

  function buildReport(doc) {
    if (!doc || !isAllowedDocument(doc)) return null;
    var u = utils();
    var extracted = visibleReportRows(doc);
    if (!extracted.length) return null;
    var rows = [];
    for (var index = 0; index < extracted.length; index += 1) {
      var token = transientToken(u, doc, extracted[index]);
      if (!token) continue;
      var row = safeRuntimeRow(u, extracted[index]);
      row.transientPrintReportArg = token;
      rows.push(row);
    }
    if (!rows.length) return null;
    return {
      type: "nims_report_frame",
      pageKind: "cr_results",
      href: safePath(doc.location && doc.location.href),
      rowCount: rows.length,
      rows: rows,
      template: template(u, doc)
    };
  }

  function reportKey(report) {
    if (!report) return "";
    var rows = report.rows || [];
    var first = rows.length ? String(rows[0].transientPrintReportArg || rows[0].report_name || "") : "";
    var last = rows.length ? String(rows[rows.length - 1].transientPrintReportArg || rows[rows.length - 1].report_name || "") : "";
    return String(report.rowCount || 0) + "|" + first + "|" + last;
  }

  var api = {
    safePath: safePath,
    isAllowedDocument: isAllowedDocument,
    hasCrSearchForm: hasCrSearchForm,
    pageKind: pageKind,
    safeTransientToken: safeTransientToken,
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
  var hadReport = false;

  function tick() {
    var kind = pageKind(root.document);
    var visibleRows = visibleReportRows(root.document).length;
    var stateKey = kind + "|" + safePath(root.location && root.location.href) + "|" + String(visibleRows);
    if (stateKey !== lastPageKey) {
      lastPageKey = stateKey;
      post({
        type: "nims_page_state",
        pageKind: kind,
        path: safePath(root.location && root.location.href),
        reportCount: visibleRows,
        hasCrInput: Boolean(crInput(root.document))
      });
    }

    var report = buildReport(root.document);
    if (report) {
      hadReport = true;
      var key = reportKey(report);
      if (key !== lastReportKey) {
        lastReportKey = key;
        post(report);
      }
    } else if (hadReport && kind !== "cr_results") {
      hadReport = false;
      lastReportKey = "";
      post({
        type: "nims_report_frame",
        pageKind: kind,
        href: safePath(root.location && root.location.href),
        rowCount: 0,
        rows: [],
        clearReason: "page_changed"
      });
    }
  }

  function start() {
    tick();
    var scheduled = false;
    function scheduleTick() {
      if (scheduled) return;
      scheduled = true;
      root.setTimeout(function () {
        scheduled = false;
        tick();
      }, 120);
    }
    try {
      if (typeof MutationObserver !== "undefined" && root.document.documentElement) {
        new MutationObserver(scheduleTick).observe(root.document.documentElement, {
          childList: true,
          subtree: true,
          attributes: true,
          attributeFilter: ["style", "class", "hidden", "src"]
        });
      }
    } catch (error) { /* bounded polling remains */ }
    var count = 0;
    var interval = root.setInterval(function () {
      count += 1;
      tick();
      if (count >= 120) root.clearInterval(interval);
    }, 1000);
  }

  if (root.document.readyState === "loading") {
    root.document.addEventListener("DOMContentLoaded", start, { once: true });
  } else {
    start();
  }
})(typeof window !== "undefined" ? window : globalThis);
