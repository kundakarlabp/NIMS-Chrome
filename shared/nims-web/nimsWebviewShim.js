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

  // Android previously loaded the CR leaf endpoint directly when the CR menu was
  // not yet present. That bypassed the authenticated shell/menu contract and
  // produced an empty page (rows=0), often followed by a login-page redirect.
  // Keep the Kotlin call successful, but drive the real NIMS navigation contract
  // repeatedly until the Investigation menu and CR-wise tab are ready.
  function patchCrNavigation() {
    var core = w.NimsReportCore;
    if (!core || typeof core.openCrWiseResultsDirect !== "function" || core.openCrWiseResultsDirect.__nimsShellFallback) return;
    var original = core.openCrWiseResultsDirect;
    var wrapped = function (doc) {
      var result;
      try { result = original.call(core, doc || w.document); } catch (_error) { result = null; }
      if (result && result.ok) return result;

      var attempts = 0;
      var maxAttempts = 24;
      function advance() {
        attempts += 1;
        try {
          var step = core.navigateToCrWiseReports(doc || w.document);
          if (step && step.done) return;
          if (step && (step.errorCode === "manual_login_required" || step.errorCode === "session_expired")) return;
        } catch (_ignored) { /* retry while the shell finishes loading */ }
        if (attempts < maxAttempts) w.setTimeout(advance, 500);
      }
      advance();
      return {
        ok: true,
        action: "native_shell_navigation_started",
        fallbackFrom: result && result.errorCode ? String(result.errorCode) : "direct_navigation_failed"
      };
    };
    wrapped.__nimsShellFallback = true;
    wrapped.__nimsOriginal = original;
    core.openCrWiseResultsDirect = wrapped;
  }

  function patch() {
    try { patchOffset(); } catch (_ignoredOffset) { /* page still loading */ }
    try { patchAjaxCompleteTab(); } catch (_ignoredTab) { /* page still loading */ }
    try { patchCrNavigation(); } catch (_ignoredNavigation) { /* core still loading */ }
    if (typeof w.date_time !== "function") w.date_time = function () { return ""; };
    if (typeof w.refresh !== "function") w.refresh = function () { return undefined; };
  }

  [0, 25, 75, 150, 300, 600, 1200, 2500, 5000].forEach(function (delay) {
    w.setTimeout(patch, delay);
  });
  w.document.addEventListener("DOMContentLoaded", patch, { once: true });
  w.addEventListener("load", patch, { once: true });
})(typeof window !== "undefined" ? window : null);
