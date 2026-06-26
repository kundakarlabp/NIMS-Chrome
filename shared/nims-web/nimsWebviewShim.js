// NIMS WebView compatibility shim.
//
// Injected at document-start into EVERY frame of the Android WebView, BEFORE
// NIMS's own page scripts run. It neutralizes two crashes that were confirmed
// against the live e-Sushrut page (read-only DevTools/extension inspection):
//
//   1. /AHIMSG5/hissso/script.js returns 404, so the global `date_time` it was
//      supposed to define is missing. NIMS calls `date_time(...)` inline, which
//      throws "date_time is not defined" in the WebView and aborts page init.
//
//   2. tabmenu.js line ~576 does `$("#menuStrip").offset().left`, but #menuStrip
//      does not exist (only #menuContainer does). On desktop that branch isn't
//      reached; in the WebView it is, so `.offset()` returns undefined and
//      reading `.left` throws "Cannot read properties of undefined (reading
//      'left')". That uncaught throw stops the menu/content render, leaving the
//      area under the menu bar blank.
//
// Both are defused here without touching NIMS's server files: define a safe
// no-op `date_time`, and make jQuery's `.offset()` return {top:0,left:0} for an
// empty set instead of undefined, so the `.left` read can never throw. Empty-set
// offset is only hit for missing elements, so returning zeros is harmless.
//
// This file is pure runtime glue (no exports). It must stay tiny, self-guarded,
// and free of side effects beyond the two fixes above.
(function () {
  var w = typeof window !== "undefined" ? window : null;
  if (!w) return;
  try {
    // (1) Missing global from the 404'd script.js. Defined as a tolerant no-op
    // that returns "" whether NIMS uses it as a value or calls it as a function.
    if (typeof w.date_time === "undefined") {
      try {
        w.date_time = function () { return ""; };
      } catch (e) { /* non-writable; ignore */ }
    }

    // (2) Guard jQuery.fn.offset so `$(missing).offset().left/top` can't throw.
    function patchOffset(jq) {
      if (!jq || !jq.fn || jq.fn.__nimsOffsetPatched) return false;
      var orig = jq.fn.offset;
      if (typeof orig !== "function") return false;
      jq.fn.offset = function () {
        var result = orig.apply(this, arguments);
        // jQuery returns undefined for an empty set; hand back zeros instead.
        if (result === null || typeof result === "undefined") {
          return { top: 0, left: 0 };
        }
        return result;
      };
      jq.fn.__nimsOffsetPatched = true;
      return true;
    }

    // jQuery isn't loaded yet at document-start, so patch as soon as it appears.
    if (!patchOffset(w.jQuery) && !patchOffset(w.$)) {
      if (typeof w.setInterval === "function") {
        var tries = 0;
        var iv = w.setInterval(function () {
          tries += 1;
          if (patchOffset(w.jQuery) || patchOffset(w.$) || tries > 200) {
            w.clearInterval(iv); // stop after success or ~10s
          }
        }, 50);
      }
    }
  } catch (e) {
    if (w.console && typeof w.console.error === "function") {
      w.console.error("NIMS WebView shim failed", e);
    }
  }
})();
