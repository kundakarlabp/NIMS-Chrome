// NIMS Android all-frames bridge.
//
// Runs inside every WebView frame. The frame that owns the visible CR-wise
// report list announces safe row metadata, an in-memory printReport token per
// row, and the verified read-only PDF request template to native Android.
(function (root) {
  "use strict";

  var ALLOWED_HOSTS = { "nimsts.edu.in": true, "www.nimsts.edu.in": true };
  var REPORT_LIST_PATH = "/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt";
  var REPORT_PDF_PATH = "/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt";
  var REPORT_MODE = "PRINTREPORT";
  var REPORT_MODE_PARAM = "hmode";
  var REPORT_ARG_PARAM = "fileName";
  var CR_FORM_NAME = "viewExternalInvFB";

  function safePath(value) {
    if (!value) return "";
    try {
      var base = root.location && root.location.href ? root.location.href : "https://www.nimsts.edu.in/";
      var url = new URL(value, base);
      return url.protocol === "about:" ? url.href : url.hostname + url.pathname;
    } catch (e) {
      return String(value).split("?")[0].split("#")[0].slice(0, 180);
    }
  }

  function isAllowedDocument(doc) {
    try {
      var url = new URL(doc.location.href);
      return url.protocol === "https:" && Boolean(ALLOWED_HOSTS[url.hostname]);
    } catch (e) {
      return false;
    }
  }

  function isSafeTransientToken(value) {
    var token = String(value || "").trim();
    if (token.length < 5 || token.length > 256) return false;
    if (token.indexOf("..") >= 0 || /[\\/\u0000-\u001f\u007f]/.test(token)) return false;
    return /^[A-Za-z0-9_-]+\.pdf$/i.test(token);
  }

  function firstPrintReportButton(row) {
    if (!row || !row.querySelectorAll) return null;
    // BUG FIX: this used to require the WHOLE onclick attribute to be exactly
    // printReport('x') or printReport('x'); (anchored ^...$ regex). Real NIMS
    // markup can wrap the call (e.g. "return printReport('x.pdf');" or with a
    // trailing statement), which that anchored pattern rejects outright -- so
    // every row in the visible 130+ row report list was silently dropped and
    // the bridge reported rowCount=0 even though contentUtils.js's permissive,
    // tokenizer-based matcher (used by Discover/Test One) found them all fine.
    // Use the same tolerant tokenizer contentUtils.js/nimsReportCore.js already
    // use elsewhere instead of a second, stricter, duplicate regex.
    var u = utils();
    var nodes = Array.prototype.slice.call(row.querySelectorAll("[onclick]"));
    for (var i = 0; i < nodes.length; i += 1) {
      var onclick = nodes[i].getAttribute("onclick") || "";
      if (u && typeof u.parseFunctionCall === "function") {
        var parsed = u.parseFunctionCall(onclick);
        if (parsed.functionName === "printReport" && parsed.argCount === 1) return nodes[i];
      } else if (/printReport\s*\(\s*(['"])[^,()]+\1\s*\)/i.test(onclick)) {
        // utils not yet available: same relaxed (non-anchored) match as a
        // last-resort fallback, never the old whole-attribute anchor.
        return nodes[i];
      }
    }
    return null;
  }

  function rowElementForInfo(doc, rowInfo) {
    if (!doc || !doc.querySelectorAll) return null;
    var rows = Array.prototype.slice.call(doc.querySelectorAll("tr"));
    var index = Number(rowInfo && rowInfo.row_index);
    if (isFinite(index) && rows[index] && firstPrintReportButton(rows[index])) return rows[index];

    var buttonIndex = Number(rowInfo && rowInfo.view_report_button_index);
    var buttons = [];
    for (var i = 0; i < rows.length; i += 1) {
      var button = firstPrintReportButton(rows[i]);
      if (button) buttons.push(button);
    }
    var selected = isFinite(buttonIndex) ? buttons[buttonIndex] : null;
    return selected && selected.closest ? selected.closest("tr") : null;
  }

  function safeRow(utils, rowInfo) {
    if (utils && typeof utils.safeRuntimeRow === "function") {
      try { return utils.safeRuntimeRow(rowInfo); } catch (e) { /* fall through */ }
    }
    return {
      row_index: Number(rowInfo && rowInfo.row_index),
      view_report_button_index: Number(rowInfo && rowInfo.view_report_button_index),
      date_sent: String(rowInfo && rowInfo.date_sent || ""),
      department: String(rowInfo && rowInfo.department || ""),
      report_name: String(rowInfo && rowInfo.report_name || ""),
      report_type: String(rowInfo && rowInfo.report_type || "other"),
      report_tags: Array.isArray(rowInfo && rowInfo.report_tags) ? rowInfo.report_tags : [],
      onclick_function_name: String(rowInfo && rowInfo.onclick_function_name || ""),
      onclick_arg_count: Number(rowInfo && rowInfo.onclick_arg_count || 0)
    };
  }

  function transientTokenForRow(utils, doc, rowInfo) {
    if (utils && typeof utils.getTransientReportRequestPayload === "function") {
      try {
        var payload = utils.getTransientReportRequestPayload(rowInfo, doc);
        var value = payload && (payload.transientPrintReportArg || payload.transient_print_report_arg);
        if (isSafeTransientToken(value)) return String(value);
      } catch (e) { /* use DOM fallback */ }
    }

    var row = rowElementForInfo(doc, rowInfo);
    var button = firstPrintReportButton(row);
    if (!button) return "";
    if (utils && typeof utils.getTransientPrintReportArg === "function") {
      try {
        var parsed = utils.getTransientPrintReportArg(button);
        if (isSafeTransientToken(parsed)) return String(parsed);
      } catch (e) { /* use strict inline parser */ }
    }
    var onclick = button.getAttribute("onclick") || "";
    // Same bug as firstPrintReportButton above: this was anchored to the
    // WHOLE attribute and rejected any real-world wrapping. Use the relaxed,
    // non-anchored match so a button found by the tolerant matcher above
    // doesn't get re-rejected here by a stricter one.
    var match = onclick.match(/printReport\s*\(\s*(['"])([^'"]+)\1\s*\)/i);
    return match && isSafeTransientToken(match[2]) ? match[2] : "";
  }

  function runtimeRows(utils, doc, hrefSafe) {
    var extracted = [];
    try { extracted = utils.extractReportRows(doc, hrefSafe) || []; } catch (e) { extracted = []; }
    var result = [];
    for (var i = 0; i < extracted.length; i += 1) {
      var token = transientTokenForRow(utils, doc, extracted[i]);
      if (!token) continue;
      var row = safeRow(utils, extracted[i]);
      row.transientPrintReportArg = token;
      result.push(row);
    }
    return result;
  }

  function templateFromSetPdf(utils, doc) {
    if (!utils || typeof utils.getSafeSetPdfTemplate !== "function") return null;
    try {
      var template = utils.getSafeSetPdfTemplate(doc);
      if (!template || !template.discovered || template.pathname !== REPORT_PDF_PATH) return null;
      return {
        origin: template.origin,
        pathname: REPORT_PDF_PATH,
        modeParamName: REPORT_MODE_PARAM,
        modeParamValue: REPORT_MODE,
        argumentParameterName: REPORT_ARG_PARAM
      };
    } catch (e) {
      return null;
    }
  }

  function templateFromLivePrintReport(doc) {
    if (!doc || !isAllowedDocument(doc)) return null;
    var win = doc.defaultView;
    var fn = win && win.printReport;
    if (typeof fn !== "function") return null;
    var source = "";
    try { source = Function.prototype.toString.call(fn); } catch (e) { return null; }
    if (source.indexOf("invDuplicateResultReportPrinting.cnt") < 0) return null;
    if (source.indexOf("PRINTREPORT") < 0 || source.indexOf("fileName") < 0) return null;
    if (source.indexOf("AddRowToTableAddMoreValues") < 0) return null;
    try {
      var origin = new URL(doc.location.href).origin;
      return {
        origin: origin,
        pathname: REPORT_PDF_PATH,
        modeParamName: REPORT_MODE_PARAM,
        modeParamValue: REPORT_MODE,
        argumentParameterName: REPORT_ARG_PARAM
      };
    } catch (e) {
      return null;
    }
  }

  function verifiedTemplate(utils, doc) {
    return templateFromSetPdf(utils, doc) || templateFromLivePrintReport(doc);
  }

  function buildFrameReport(utils, doc, hrefSafe) {
    if (!utils || !doc || !isAllowedDocument(doc)) return null;
    try {
      if (typeof utils.hasReportRows === "function" && !utils.hasReportRows(doc)) return null;
    } catch (e) {
      return null;
    }
    var rows = runtimeRows(utils, doc, hrefSafe);
    if (!rows.length) return null;
    var template = verifiedTemplate(utils, doc);
    if (!template) return null;
    return {
      type: "nims_report_frame",
      href: hrefSafe || safePath(doc.location && doc.location.href),
      rowCount: rows.length,
      rows: rows,
      template: template
    };
  }

  function frameReportKey(report) {
    if (!report) return "";
    var rows = report.rows || [];
    function sig(row) {
      return row ? String(row.transientPrintReportArg || row.report_name || row.row_index || "") : "";
    }
    return String(report.rowCount || 0) + "|" + (rows.length ? sig(rows[0]) : "") + "|" + (rows.length ? sig(rows[rows.length - 1]) : "");
  }

  var api = {
    buildFrameReport: buildFrameReport,
    frameReportKey: frameReportKey,
    isSafeTransientToken: isSafeTransientToken,
    verifiedTemplate: verifiedTemplate
  };
  root.NimsAndroidFrameBridgeUtil = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;

  if (!root.document || !isAllowedDocument(root.document)) return;
  if (root.__NIMS_ANDROID_FRAME_BRIDGE__) return;
  root.__NIMS_ANDROID_FRAME_BRIDGE__ = true;

  function bridge() { return root.nimsAndroidBridge || null; }
  function utils() { return root.NimsFastSummaryUtils || null; }

  function post(payload) {
    var target = bridge();
    if (!target || typeof target.postMessage !== "function") return false;
    try {
      target.postMessage(JSON.stringify(payload));
      return true;
    } catch (e) {
      return false;
    }
  }

  var clearPosted = false;
  function postClear(reason) {
    if (clearPosted) return true;
    clearPosted = post({
      type: "nims_report_frame",
      href: safePath(root.location && root.location.href),
      rowCount: 0,
      rows: [],
      clearReason: String(reason || "navigation").slice(0, 60)
    });
    return clearPosted;
  }

  function isCrSearchForm(form) {
    if (!form) return false;
    var name = String(form.name || form.id || "");
    var action = safePath(form.getAttribute && form.getAttribute("action") || "");
    return name === CR_FORM_NAME || action.indexOf(REPORT_LIST_PATH) >= 0;
  }

  function installClearSignals() {
    var doc = root.document;
    if (!doc || doc.__nimsReportClearSignalsInstalled) return;
    doc.__nimsReportClearSignalsInstalled = true;
    doc.addEventListener("submit", function (event) {
      if (isCrSearchForm(event && event.target)) postClear("cr_submit");
    }, true);
    root.addEventListener("beforeunload", function () {
      var path = safePath(root.location && root.location.href);
      if (path.indexOf(REPORT_LIST_PATH) >= 0) postClear("report_frame_unload");
    });
  }

  var lastKey = "";
  var hadReport = false;
  function tick() {
    var u = utils();
    if (!u || !bridge()) return;
    var report = buildFrameReport(u, root.document, safePath(root.location && root.location.href));
    if (!report) {
      if (!hadReport && safePath(root.location && root.location.href).indexOf(REPORT_LIST_PATH) >= 0) postClear("cr_search_or_loading");
      return;
    }
    hadReport = true;
    clearPosted = false;
    var key = frameReportKey(report);
    if (key === lastKey) return;
    lastKey = key;
    post(report);
  }

  function start() {
    installClearSignals();
    tick();
    var scheduled = false;
    function scheduleTick() {
      if (scheduled) return;
      scheduled = true;
      root.setTimeout(function () {
        scheduled = false;
        tick();
      }, 150);
    }
    try {
      if (typeof MutationObserver !== "undefined" && root.document.documentElement) {
        new MutationObserver(scheduleTick).observe(root.document.documentElement, { childList: true, subtree: true, attributes: true });
      }
    } catch (e) { /* bounded interval remains */ }
    var ticks = 0;
    var interval = root.setInterval(function () {
      ticks += 1;
      tick();
      if (ticks >= 180) root.clearInterval(interval);
    }, 1000);
  }

  if (root.document.readyState !== "loading") start();
  else root.document.addEventListener("DOMContentLoaded", start, { once: true });
})(typeof window !== "undefined" ? window : globalThis);
