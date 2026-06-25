// NIMS Android all-frames bridge.
//
// Injected at document-start into EVERY frame of the Android WebView (via
// WebViewCompat.addDocumentStartJavaScript), alongside contentUtils.js. This
// mirrors the Chrome extension's `all_frames: true` model and, crucially, uses
// the EXACT row/URL logic the extension already uses on the live NIMS page
// (NimsFastSummaryUtils from contentUtils.js) instead of any reverse-engineered
// assumption. The frame that actually contains the "View Report" rows reads
// them locally (always same-origin to itself) and hands the rows - each already
// carrying its real source_url - to Kotlin through the nimsAndroidBridge
// WebMessageListener.
//
// buildFrameReport/frameReportKey are exported as pure functions for unit
// testing; the runtime wiring is skipped under Node/test.
(function (root) {
  // Pure: returns the payload Kotlin needs, or null if this frame has no rows.
  // utils is the extension's NimsFastSummaryUtils.
  function buildFrameReport(utils, doc, hrefSafe) {
    if (!utils || !doc) return null;
    try {
      if (typeof utils.hasReportRows === "function" && !utils.hasReportRows(doc)) return null;
    } catch (e) { return null; }
    var rows = [];
    try { rows = utils.extractReportRows(doc, hrefSafe) || []; } catch (e) { rows = []; }
    if (!rows.length) return null;
    return {
      type: "nims_report_frame",
      href: hrefSafe || "",
      rowCount: rows.length,
      rows: rows
    };
  }

  // Debounce key: re-post only when the rows change. Uses each row's source_url
  // (the real report link) plus count, so a different patient re-announces.
  function frameReportKey(report) {
    if (!report) return "";
    var rows = report.rows || [];
    function sig(r) { return r ? String(r.source_url || r.report_id || r.report_name || r.row_index || "") : ""; }
    var first = rows.length ? sig(rows[0]) : "";
    var last = rows.length ? sig(rows[rows.length - 1]) : "";
    return String(report.rowCount || 0) + "|" + first + "|" + last;
  }

  var api = { buildFrameReport, frameReportKey };
  root.NimsAndroidFrameBridgeUtil = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;

  // ---- Runtime wiring (skipped in Node/tests where document/postMessage absent) ----
  if (!root.document || typeof root.setInterval !== "function") return;
  if (root.__NIMS_ANDROID_FRAME_BRIDGE__) return;
  root.__NIMS_ANDROID_FRAME_BRIDGE__ = true;

  function safePath(value) {
    try { var url = new URL(value || ""); return url.hostname + url.pathname; } catch (e) { return ""; }
  }
  function utils() { return root.NimsFastSummaryUtils || null; }
  function bridge() { return root.nimsAndroidBridge || null; }

  var lastKey = "";

  function tick() {
    var u = utils();
    var b = bridge();
    if (!u || !b) return;
    var hrefSafe = safePath(root.location && root.location.href);
    var report = buildFrameReport(u, root.document, hrefSafe);
    if (!report) return; // only the frame that owns the rows speaks
    var key = frameReportKey(report);
    if (key === lastKey) return;
    lastKey = key;
    try { b.postMessage(JSON.stringify(report)); } catch (e) { /* ignore */ }
  }

  function start() {
    tick();
    var ticks = 0;
    var interval = root.setInterval(function () {
      ticks += 1;
      tick();
      if (ticks >= 80) root.clearInterval(interval);
    }, 750);
  }

  if (root.document.readyState !== "loading") start();
  else root.document.addEventListener("DOMContentLoaded", start, { once: true });
})(typeof window !== "undefined" ? window : globalThis);
