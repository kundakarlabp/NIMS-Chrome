// NIMS WebView compatibility shim + frame diagnostics.
//
// Injected at document-start into EVERY frame of the Android WebView, BEFORE
// NIMS's own scripts run. It does three things:
//
//   1. Defines a safe no-op `date_time` (its source, /AHIMSG5/hissso/script.js,
//      404s, so NIMS's inline `date_time(...)` call would otherwise throw).
//
//   2. Patches jQuery's `.offset()` to return {top:0,left:0} for an empty set,
//      so tabmenu.js line ~576 (`$("#menuStrip").offset().left`, with #menuStrip
//      absent) cannot throw. CRITICAL: this is installed SYNCHRONOUSLY the moment
//      NIMS assigns window.jQuery / window.$ (via a property hook), not by a timer
//      poll, because tabmenu.js runs synchronously during load and a polled patch
//      can land too late to help.
//
//   3. Captures uncaught page errors and reports, once per frame, what that frame
//      actually contains (body child count + text length) to Kotlin via the
//      nimsAndroidBridge listener. This is how we can finally tell whether the
//      blank area under the menu is an EMPTY iframe vs a populated-but-hidden one.
//
// Pure runtime glue; no exports. Must stay tiny and self-guarded.
(function () {
  var w = typeof window !== "undefined" ? window : null;
  if (!w) return;

  try {
    // (1) Missing global from the 404'd script.js.
    if (typeof w.date_time === "undefined") {
      try { w.date_time = function () { return ""; }; } catch (e) { /* ignore */ }
    }

    // (2) jQuery.offset guard, installed synchronously on assignment.
    function patchOffset(jq) {
      if (!jq || !jq.fn || jq.fn.__nimsOffsetPatched) return false;
      var orig = jq.fn.offset;
      if (typeof orig !== "function") return false;
      jq.fn.offset = function () {
        var result = orig.apply(this, arguments);
        if (result === null || typeof result === "undefined") {
          return { top: 0, left: 0 };
        }
        return result;
      };
      jq.fn.__nimsOffsetPatched = true;
      return true;
    }

    function hookGlobal(name) {
      var current;
      try { current = w[name]; } catch (e) { current = undefined; }
      // Already present at injection time -> patch immediately.
      if (current && patchOffset(current)) return true;
      // Otherwise intercept the assignment NIMS will make, and patch then.
      try {
        Object.defineProperty(w, name, {
          configurable: true,
          enumerable: true,
          get: function () { return current; },
          set: function (val) { current = val; patchOffset(val); }
        });
        return true;
      } catch (e) {
        return false;
      }
    }

    var hookedJq = hookGlobal("jQuery");
    var hookedDollar = hookGlobal("$");

    // Fallback poller in case jQuery is assigned through a path the hook missed.
    if ((!hookedJq || !hookedDollar) && typeof w.setInterval === "function") {
      var tries = 0;
      var iv = w.setInterval(function () {
        tries += 1;
        if (patchOffset(w.jQuery) || patchOffset(w.$) || tries > 200) {
          w.clearInterval(iv);
        }
      }, 50);
    }

    // (3) Error capture + one-shot per-frame content report.
    var capturedErrors = [];
    w.__nimsShimErrors = capturedErrors;
    if (typeof w.addEventListener === "function") {
      w.addEventListener("error", function (ev) {
        try {
          var src = (ev && ev.filename ? String(ev.filename).split("/").pop() : "");
          var msg = ev && ev.message ? String(ev.message) : "error";
          if (capturedErrors.length < 12) {
            capturedErrors.push(msg + " @" + src + ":" + (ev && ev.lineno ? ev.lineno : "?"));
          }
        } catch (e) { /* ignore */ }
      });
    }

    function reportFrame() {
      try {
        var bridge = w.nimsAndroidBridge;
        if (!bridge || typeof bridge.postMessage !== "function") return false;
        var doc = w.document;
        var body = doc && doc.body;
        var children = body ? body.querySelectorAll("*").length : 0;
        var textLen = body && body.innerText ? body.innerText.trim().length : 0;
        var height = body ? (body.scrollHeight || 0) : 0;
        var path = "";
        try { var u = new URL(w.location.href); path = u.hostname + u.pathname; } catch (e) {}
        bridge.postMessage(JSON.stringify({
          type: "nims_frame_debug",
          url: path,
          children: children,
          textLen: textLen,
          height: height,
          errors: capturedErrors.slice(0, 6)
        }));
        return true;
      } catch (e) { return false; }
    }

    // Report after the frame settles; retry a few times in case the bridge or
    // content arrives slightly later.
    if (w.document && typeof w.setTimeout === "function") {
      var attempts = 0;
      var fire = function () {
        attempts += 1;
        var done = reportFrame();
        if (!done && attempts < 6) { w.setTimeout(fire, 1000); }
      };
      if (w.document.readyState === "loading" && typeof w.addEventListener === "function") {
        w.addEventListener("DOMContentLoaded", function () { w.setTimeout(fire, 800); }, { once: true });
      } else {
        w.setTimeout(fire, 800);
      }
    }
  } catch (e) {
    if (w.console && typeof w.console.error === "function") {
      w.console.error("NIMS WebView shim failed", e);
    }
  }
})();
