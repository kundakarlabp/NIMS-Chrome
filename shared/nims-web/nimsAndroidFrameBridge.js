// NIMS Android all-frames bridge.
(function (root) {
  function buildFrameReport(utils, doc, hrefSafe) {
    if (!utils || !doc) return null;
    try {
      if (typeof utils.hasReportRows === "function" && !utils.hasReportRows(doc)) return null;
    } catch (e) { return null; }
    var rows = [];
    try { rows = utils.extractReportRows(doc, hrefSafe) || []; } catch (e) { rows = []; }
    if (!rows.length) return null;
    return { type: "nims_report_frame", href: hrefSafe || "", rowCount: rows.length, rows: rows };
  }

  function frameReportKey(report) {
    if (!report) return "";
    var rows = report.rows || [];
    function sig(row) { return row ? String(row.source_url || row.report_id || row.report_name || row.row_index || "") : ""; }
    var first = rows.length ? sig(rows[0]) : "";
    var last = rows.length ? sig(rows[rows.length - 1]) : "";
    return String(report.rowCount || 0) + "|" + first + "|" + last;
  }

  var api = { buildFrameReport: buildFrameReport, frameReportKey: frameReportKey };
  root.NimsAndroidFrameBridgeUtil = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;

  if (!root.document || typeof root.setInterval !== "function") return;
  if (root.__NIMS_ANDROID_FRAME_BRIDGE__) return;
  root.__NIMS_ANDROID_FRAME_BRIDGE__ = true;

  function safePath(value) {
    if (!value) return "";
    try {
      var base = root.location && root.location.href ? root.location.href : "https://www.nimsts.edu.in/";
      var url = new URL(value, base);
      return url.protocol === "about:" ? url.href : url.hostname + url.pathname;
    } catch (e) { return String(value).split("?")[0].split("#")[0].slice(0, 160); }
  }

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

  function scheduleSnapshots(prefix) {
    if (typeof root.setTimeout !== "function") return;
    [0, 100, 500, 1500, 3000].forEach(function (ms) {
      root.setTimeout(function () { frameSnapshot(prefix + "_" + ms + "ms"); }, ms);
    });
  }

  function watchFrame() {
    var frame = mainMenuFrame();
    if (!frame || frame.__nimsNavigationWatchInstalled) return false;
    frame.__nimsNavigationWatchInstalled = true;
    try {
      frame.addEventListener("load", function () { frameSnapshot("frmMainMenu_load"); });
      frame.addEventListener("error", function () { frameSnapshot("frmMainMenu_error"); });
    } catch (e) {}
    try {
      if (typeof MutationObserver !== "undefined") {
        new MutationObserver(function (records) {
          if (records.some(function (item) { return item.attributeName === "src"; })) {
            frameSnapshot("frmMainMenu_src_changed");
            scheduleSnapshots("after_src_change");
          }
        }).observe(frame, { attributes: true, attributeFilter: ["src"] });
      }
    } catch (e) {}
    frameSnapshot("frmMainMenu_watch_ready");
    return true;
  }

  function isInvestigationTarget(node) {
    for (var current = node, depth = 0; current && depth < 7; current = current.parentElement, depth += 1) {
      try {
        var onclick = current.getAttribute ? current.getAttribute("onclick") || "" : "";
        var label = String(current.innerText || current.textContent || current.value || "").replace(/\s+/g, " ").trim();
        if (/menuSelected\s*\(\s*['"]Investigation['"]\s*,\s*true\s*\)/i.test(onclick) || /^Investigation$/i.test(label)) return true;
      } catch (e) {}
    }
    return false;
  }

  function installNavigationTrace() {
    var doc = root.document;
    watchFrame();
    if (doc && !doc.__nimsInvestigationTraceInstalled && typeof doc.addEventListener === "function") {
      doc.__nimsInvestigationTraceInstalled = true;
      doc.addEventListener("click", function (event) {
        if (!isInvestigationTarget(event && event.target)) return;
        postDebug("NAV investigation_click");
        frameSnapshot("before_click");
        scheduleSnapshots("after_click");
      }, true);
    }
    if (typeof root.addEventListener === "function") {
      root.addEventListener("error", function (event) {
        var source = event && event.filename ? String(event.filename).split("/").pop() : "";
        var message = event && event.message ? String(event.message).replace(/\s+/g, " ").slice(0, 120) : "error";
        postDebug("NAV page_error message=" + message + " source=" + source + " line=" + (event && event.lineno ? event.lineno : 0));
      });
    }
    try {
      if (typeof MutationObserver !== "undefined" && doc && doc.documentElement) {
        new MutationObserver(watchFrame).observe(doc.documentElement, { childList: true, subtree: true });
      }
    } catch (e) {}
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
    installNavigationTrace();
    tick();
    var ticks = 0;
    var interval = root.setInterval(function () {
      ticks += 1;
      watchFrame();
      tick();
      if (ticks >= 80) root.clearInterval(interval);
    }, 750);
  }

  if (root.document.readyState !== "loading") start();
  else root.document.addEventListener("DOMContentLoaded", start, { once: true });
})(typeof window !== "undefined" ? window : globalThis);
