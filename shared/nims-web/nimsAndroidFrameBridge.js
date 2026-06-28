// NIMS Android all-frames bridge.
(function (root) {
  var REPORT_PATHS = [
    "viewcrnowisereportprocess.cnt",
    "invresultreportprintingcrnowise.cnt"
  ];
  var DIRECT_REPORT_PATH = "/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt";

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

  function safeOrigin(doc) {
    try {
      var url = new URL(doc.location.href);
      if (url.hostname !== "nimsts.edu.in" && url.hostname !== "www.nimsts.edu.in") return "";
      return url.origin;
    } catch (e) {
      return "";
    }
  }

  function isReportDocument(doc) {
    if (!doc || !doc.location || !doc.location.href) return true;
    var path = safePath(doc.location.href).toLowerCase();
    return REPORT_PATHS.some(function (item) { return path.indexOf(item) >= 0; });
  }

  function visible(element) {
    if (!element) return false;
    try {
      if (element.hidden || element.getAttribute && element.getAttribute("aria-hidden") === "true") return false;
      var owner = element.ownerDocument && element.ownerDocument.defaultView;
      var style = owner && owner.getComputedStyle ? owner.getComputedStyle(element) : null;
      if (style && (style.display === "none" || style.visibility === "hidden" || style.visibility === "collapse" || Number(style.opacity) === 0)) return false;
      return true;
    } catch (e) {
      return true;
    }
  }

  function visibleTree(element) {
    for (var current = element; current; current = current.parentElement) {
      if (!visible(current)) return false;
    }
    return true;
  }

  function strictVisibleRows(doc, rows) {
    if (!doc || typeof doc.querySelectorAll !== "function") return rows || [];
    var domRows = [];
    try { domRows = Array.prototype.slice.call(doc.querySelectorAll("tr")); } catch (e) { domRows = []; }
    return (rows || []).filter(function (row) {
      var index = Number(row && row.row_index);
      var tr = Number.isFinite(index) ? domRows[index] : null;
      if (!tr || !visibleTree(tr)) return false;
      var controls = [];
      try { controls = Array.prototype.slice.call(tr.querySelectorAll("[onclick]")); } catch (e) { controls = []; }
      return controls.some(function (control) {
        if (!visibleTree(control)) return false;
        var code = String(control.getAttribute("onclick") || "");
        return /^\s*(?:javascript:\s*)?printReport\s*\(\s*(['\"]?).+?\1\s*\)\s*;?\s*$/i.test(code) && code.indexOf(",") < 0;
      });
    });
  }

  function prepareRows(utils, doc, rows) {
    return strictVisibleRows(doc, rows).map(function (row) {
      var prepared = row;
      try {
        if (typeof utils.safeRuntimeRow === "function") prepared = utils.safeRuntimeRow(row);
        if (typeof utils.getTransientReportRequestPayload === "function") {
          var payload = utils.getTransientReportRequestPayload(row, doc);
          if (payload && payload.ok && payload.transient_print_report_arg) {
            prepared.transientPrintReportArg = payload.transient_print_report_arg;
          }
        }
      } catch (e) { /* keep safe metadata */ }
      return prepared;
    });
  }

  function buildFrameReport(utils, doc, hrefSafe) {
    if (!utils || !doc || !isReportDocument(doc)) return null;
    var rows = [];
    try { rows = utils.extractReportRows(doc, hrefSafe) || []; } catch (e) { rows = []; }
    var prepared = prepareRows(utils, doc, rows);
    if (!prepared.length) return null;
    var report = {
      type: "nims_report_frame",
      href: hrefSafe || "",
      rowCount: prepared.length,
      rows: prepared
    };
    var origin = safeOrigin(doc);
    if (origin) {
      report.template = {
        origin: origin,
        pathname: DIRECT_REPORT_PATH,
        modeParamName: "hmode",
        modeParamValue: "PRINTREPORT",
        argumentParameterName: "fileName"
      };
    }
    return report;
  }

  function frameReportKey(report) {
    if (!report) return "";
    var rows = report.rows || [];
    function sig(row) { return row ? String(row.report_id || row.report_name || row.row_index || "") : ""; }
    var first = rows.length ? sig(rows[0]) : "";
    var last = rows.length ? sig(rows[rows.length - 1]) : "";
    return String(report.rowCount || 0) + "|" + first + "|" + last;
  }

  var api = {
    buildFrameReport: buildFrameReport,
    frameReportKey: frameReportKey,
    isReportDocument: isReportDocument,
    visible: visible,
    strictVisibleRows: strictVisibleRows
  };
  root.NimsAndroidFrameBridgeUtil = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;

  if (!root.document || typeof root.setInterval !== "function") return;
  if (root.__NIMS_ANDROID_FRAME_BRIDGE__) return;
  root.__NIMS_ANDROID_FRAME_BRIDGE__ = true;

  function bodyStats(doc) {
    var body = doc && doc.body;
    return {
      children: body && body.querySelectorAll ? body.querySelectorAll("*").length : 0,
      textLen: body && body.innerText ? String(body.innerText).trim().length : 0,
      height: body ? body.scrollHeight || 0 : 0
    };
  }

  function utils() { return root.NimsFastSummaryUtils || null; }
  function bridge() { return root.nimsAndroidBridge || null; }

  function postDebug(note, doc) {
    var target = bridge();
    if (!target || typeof target.postMessage !== "function") return false;
    doc = doc || root.document;
    var size = bodyStats(doc);
    try {
      target.postMessage(JSON.stringify({
        type: "nims_frame_debug",
        url: safePath(doc && doc.location ? doc.location.href : root.location && root.location.href),
        children: size.children,
        textLen: size.textLen,
        height: size.height,
        errors: note ? [note] : []
      }));
      return true;
    } catch (e) { return false; }
  }

  function mainMenuFrame() {
    var doc = root.document;
    try {
      return (doc.getElementById && doc.getElementById("frmMainMenu")) ||
        (doc.querySelector && doc.querySelector('iframe[name="frmMainMenu"],frame[name="frmMainMenu"]')) || null;
    } catch (e) { return null; }
  }

  function frameSnapshot(reason) {
    var frame = mainMenuFrame();
    if (!frame) return postDebug("NAV frame reason=" + reason + " present=false");
    var attr = "", live = "", child = "", ready = "", readable = false;
    var size = { children: 0, textLen: 0, height: 0 };
    try { attr = frame.getAttribute ? frame.getAttribute("src") || "" : ""; } catch (e) {}
    try { live = frame.src || ""; } catch (e) {}
    try {
      var childDoc = frame.contentDocument;
      if (childDoc) {
        readable = true;
        child = safePath(childDoc.location && childDoc.location.href);
        ready = childDoc.readyState || "";
        size = bodyStats(childDoc);
      }
    } catch (e) {}
    return postDebug([
      "NAV frame", "reason=" + reason, "present=true",
      "attr=" + safePath(attr), "live=" + safePath(live), "child=" + child,
      "readable=" + readable, "ready=" + ready,
      "children=" + size.children, "text=" + size.textLen, "h=" + size.height
    ].join(" "));
  }

  function watchFrame() {
    var frame = mainMenuFrame();
    if (!frame || frame.__nimsNavigationWatchInstalled) return false;
    frame.__nimsNavigationWatchInstalled = true;
    try { frame.addEventListener("load", function () { frameSnapshot("frmMainMenu_load"); }); } catch (e) {}
    frameSnapshot("frmMainMenu_watch_ready");
    return true;
  }

  var lastKey = "";
  function tick() {
    var u = utils();
    var b = bridge();
    if (!u || !b) return;
    var report = buildFrameReport(u, root.document, safePath(root.location && root.location.href));
    if (!report) return;
    var key = frameReportKey(report);
    if (key === lastKey) return;
    lastKey = key;
    try { b.postMessage(JSON.stringify(report)); } catch (e) {}
  }

  function start() {
    watchFrame();
    tick();
    var ticks = 0;
    var interval = root.setInterval(function () {
      ticks += 1;
      watchFrame();
      tick();
      if (ticks >= 240) root.clearInterval(interval);
    }, 750);
  }

  if (root.document.readyState !== "loading") start();
  else root.document.addEventListener("DOMContentLoaded", start, { once: true });
})(typeof window !== "undefined" ? window : globalThis);
