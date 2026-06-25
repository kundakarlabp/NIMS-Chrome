// NIMS Android all-frames bridge.
//
// Injected at document-start into EVERY frame of the Android WebView (via
// WebViewCompat.addDocumentStartJavaScript), alongside nimsReportCore.js. This
// mirrors the Chrome extension's `all_frames: true` model: the Android WebView
// only runs evaluateJavascript in the top frame, but the NIMS result rows live
// in a nested, frequently different-origin iframe that the top frame cannot
// read. Running this in-frame lets the frame that actually owns the rows read
// them locally (always same-origin to itself) and hand the DOM-derived facts -
// rows, per-row printReport argument, and the discovered setPdf template - to
// Kotlin through the `nimsAndroidBridge` WebMessageListener. Kotlin then builds
// URLs and fetches/parses/summarizes exactly as before (origin-independent).
//
// Only the frame that contains genuine one-argument printReport rows ever
// posts. buildFrameReport/frameReportKey are exported as pure functions for
// unit testing; the runtime wiring is skipped under Node/test.
(function (root) {
  function genuineRows(core, doc, hrefSafe) {
    try {
      return (core.extractReportRows(doc, hrefSafe) || []).filter(function (r) {
        return r && r.onclick_function_name === "printReport" && Number(r.onclick_arg_count) === 1;
      });
    } catch (e) {
      return [];
    }
  }

  // Pure: returns the payload Kotlin needs, or null if this frame has no rows.
  function buildFrameReport(core, doc, hrefSafe) {
    if (!core || !doc) return null;
    var rows = genuineRows(core, doc, hrefSafe);
    if (!rows.length) return null;
    var enriched = rows.map(function (r) {
      var arg = "";
      try {
        var payload = core.transientPayloadForRow(r, doc);
        if (payload && payload.ok) arg = payload.transientPrintReportArg || "";
      } catch (e) { arg = ""; }
      r.transientPrintReportArg = arg;
      return r;
    });
    var template = null;
    try {
      var t = core.discoverSetPdfTemplate(doc);
      if (t && t.discovered) template = t;
    } catch (e) { template = null; }
    return {
      type: "nims_report_frame",
      href: hrefSafe || "",
      rowCount: enriched.length,
      rows: enriched,
      template: template
    };
  }

  // Debounce key: re-post only when the rows or discovered template change.
  // Includes a row signature (first/last printReport argument) so a different
  // patient with the same row count still re-announces.
  function frameReportKey(report) {
    if (!report) return "";
    var rows = report.rows || [];
    function sig(r) { return r ? String(r.transientPrintReportArg || r.row_index || "") : ""; }
    var first = rows.length ? sig(rows[0]) : "";
    var last = rows.length ? sig(rows[rows.length - 1]) : "";
    return String(report.rowCount || 0) + "|" +
      (report.template ? (report.template.endpoint || "discovered") : "none") + "|" +
      first + "|" + last;
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
  function core() { return root.NimsReportCore || null; }
  function bridge() { return root.nimsAndroidBridge || null; }

  var lastKey = "";
  var discoveryClicked = false;

  function tick() {
    var c = core();
    var b = bridge();
    if (!c || !b) return;
    var hrefSafe = safePath(root.location && root.location.href);
    var report = buildFrameReport(c, root.document, hrefSafe);
    if (!report) return; // only the frame that owns the rows speaks
    if (!report.template && !discoveryClicked) {
      // One-time, in-frame (same-origin) discovery click so the hidden setPdf
      // iframe loads and reveals the report-request template on a later tick.
      discoveryClicked = true;
      try { c.clickFirstReportForMode("test_direct", root.document); } catch (e) { /* ignore */ }
      return;
    }
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
      if (ticks >= 60) root.clearInterval(interval);
    }, 750);
  }

  if (root.document.readyState !== "loading") start();
  else root.document.addEventListener("DOMContentLoaded", start, { once: true });
})(typeof window !== "undefined" ? window : globalThis);
