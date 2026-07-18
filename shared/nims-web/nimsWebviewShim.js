// Minimal Android WebView compatibility adapter for NIMS pages.
// It deliberately avoids intercepting jQuery assignment or rewriting page scripts.
(function (w) {
  "use strict";
  if (!w || !w.document) return;

  try {
    if (w.location.protocol !== "https:" || !/^(?:www\.)?nimsts\.edu\.in$/i.test(w.location.hostname)) return;
  } catch (_error) {
    return;
  }

  w.__nimsInjectedAt = Date.now();

  // Several NIMS pages call these globals before the defining legacy script has
  // loaded. Safe no-op fallbacks prevent the page from aborting during login,
  // menu rendering and report-popup setup. A later page definition may replace
  // them normally.
  if (typeof w.date_time !== "function") w.date_time = function () { return ""; };
  if (typeof w.refresh !== "function") w.refresh = function () { return undefined; };

  if (!w.__nimsErrorCaptureInstalled) {
    w.__nimsErrorCaptureInstalled = true;
    var previous = w.onerror;
    w.onerror = function (message, source, line, column, error) {
      w.__nimsLastError = {
        message: String(message || ""),
        source: String(source || ""),
        line: line || 0,
        column: column || 0,
        stack: error && error.stack ? String(error.stack).slice(0, 2000) : ""
      };
      if (typeof previous === "function") {
        try { return previous.call(w, message, source, line, column, error); } catch (_ignored) { /* continue */ }
      }
      return false;
    };
  }

  function patchOffset() {
    var jq = w.jQuery || w.$;
    if (!jq || !jq.fn || typeof jq.fn.offset !== "function" || jq.fn.offset.__nimsSafeOffset) return;
    var original = jq.fn.offset;
    var wrapped = function () {
      var value;
      try {
        value = original.apply(this, arguments);
      } catch (error) {
        if (arguments.length > 0) throw error;
        value = null;
      }
      return value == null && arguments.length === 0 ? { top: 0, left: 0 } : value;
    };
    wrapped.__nimsSafeOffset = true;
    wrapped.__nimsOriginal = original;
    jq.fn.offset = wrapped;
  }

  function isFrame(value) {
    return Boolean(value && /^(?:IFRAME|FRAME)$/i.test(String(value.tagName || "")));
  }

  function recentNimsFrame() {
    var frames = w.document.querySelectorAll("iframe,frame");
    for (var i = frames.length - 1; i >= 0; i -= 1) {
      var frame = frames[i];
      var id = String(frame.id || frame.name || "");
      var src = "";
      try { src = String(frame.getAttribute("src") || frame.src || ""); } catch (_ignored) { src = ""; }
      if (/_iframe$/i.test(id) || /\/(?:AHIMSG5|HISInvestigationG5|HISClinical)\//i.test(src)) return frame;
    }
    return null;
  }

  function patchAjaxCompleteTab() {
    var original = w.ajaxCompleteTab;
    if (typeof original !== "function" || original.__nimsFrameArgumentAdapter) return;
    var wrapped = function (candidate) {
      var frame = isFrame(candidate) ? candidate : recentNimsFrame();
      if (!frame) return undefined;
      try {
        return original.call(this, frame);
      } catch (error) {
        var message = String(error && error.message || error || "");
        if (/contentDocument|undefined|null|cannot read/i.test(message)) return undefined;
        throw error;
      }
    };
    wrapped.__nimsFrameArgumentAdapter = true;
    wrapped.__nimsOriginal = original;
    w.ajaxCompleteTab = wrapped;
  }

  function patch() {
    try { patchOffset(); } catch (_ignoredOffset) { /* page still loading */ }
    try { patchAjaxCompleteTab(); } catch (_ignoredTab) { /* page still loading */ }
    if (typeof w.date_time !== "function") w.date_time = function () { return ""; };
    if (typeof w.refresh !== "function") w.refresh = function () { return undefined; };
  }

  [0, 25, 75, 150, 300, 600, 1200, 2500, 5000].forEach(function (delay) {
    w.setTimeout(patch, delay);
  });
  w.document.addEventListener("DOMContentLoaded", patch, { once: true });
  w.addEventListener("load", patch, { once: true });
})(typeof window !== "undefined" ? window : null);
