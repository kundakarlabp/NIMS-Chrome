// Android-only compatibility adapter for the live NIMS WebView runtime.
//
// Repairs only the three failures confirmed on-device:
// 1. date_time is referenced before its defining asset is available;
// 2. tabmenu.js reads .offset().left when the selected element is absent;
// 3. dynamically-created iframes call ajaxCompleteTab() without the iframe arg.
//
// This adapter never clicks menus, submits forms, reads credentials, or changes
// the report extraction workflow.
(function (w) {
  "use strict";
  if (!w || !w.document) return;

  var ALLOWED_HOSTS = { "nimsts.edu.in": true, "www.nimsts.edu.in": true };
  try {
    if (!ALLOWED_HOSTS[w.location.hostname] || w.location.protocol !== "https:") return;
  } catch (e) {
    return;
  }

  var lastRuntimeKey = "";

  function safePath() {
    try { return String(w.location.pathname || "").slice(0, 180); }
    catch (e) { return ""; }
  }

  function postRuntime(type, detail) {
    var bridge = w.nimsAndroidBridge;
    if (!bridge || typeof bridge.postMessage !== "function") return;
    try {
      bridge.postMessage(JSON.stringify({
        type: type,
        path: safePath(),
        detail: String(detail || "").slice(0, 160),
        jqueryPresent: typeof w.jQuery === "function",
        jqueryVersion: w.jQuery && w.jQuery.fn ? String(w.jQuery.fn.jquery || "") : "",
        jqueryFallbackUsed: Boolean(w.__nimsBundledJqueryVersion),
        dateTimeReady: typeof w.date_time === "function",
        offsetPatched: Boolean(w.jQuery && w.jQuery.fn && w.jQuery.fn.offset && w.jQuery.fn.offset.__nimsSafeOffset),
        ajaxCompleteTabPatched: Boolean(w.ajaxCompleteTab && w.ajaxCompleteTab.__nimsFrameArgumentAdapter)
      }));
    } catch (e) { /* telemetry is best effort and contains no page values */ }
  }

  function reportReady(phase) {
    var key = [
      safePath(),
      phase,
      typeof w.jQuery === "function",
      w.jQuery && w.jQuery.fn ? String(w.jQuery.fn.jquery || "") : "",
      typeof w.date_time === "function",
      Boolean(w.jQuery && w.jQuery.fn && w.jQuery.fn.offset && w.jQuery.fn.offset.__nimsSafeOffset),
      Boolean(w.ajaxCompleteTab && w.ajaxCompleteTab.__nimsFrameArgumentAdapter)
    ].join("|");
    if (key === lastRuntimeKey) return;
    lastRuntimeKey = key;
    postRuntime("nims_runtime_ready", phase);
  }

  function ensureDateTime() {
    if (typeof w.date_time !== "function") {
      w.date_time = function () { return ""; };
      w.date_time.__nimsCompatibilityFallback = true;
    }
  }

  function patchOffset(jq) {
    if (!jq || !jq.fn || typeof jq.fn.offset !== "function") return false;
    if (jq.fn.offset.__nimsSafeOffset) return true;
    var original = jq.fn.offset;
    var wrapped = function () {
      var value;
      try {
        value = original.apply(this, arguments);
      } catch (error) {
        if (arguments.length) throw error;
        value = null;
      }
      return value == null && arguments.length === 0 ? { top: 0, left: 0 } : value;
    };
    wrapped.__nimsSafeOffset = true;
    wrapped.__nimsOriginal = original;
    jq.fn.offset = wrapped;
    return true;
  }

  function armNextJqueryAssignment() {
    var descriptor;
    try { descriptor = Object.getOwnPropertyDescriptor(w, "jQuery"); }
    catch (e) { descriptor = null; }
    if (descriptor && descriptor.configurable === false) return;
    var current = w.jQuery;
    try {
      Object.defineProperty(w, "jQuery", {
        configurable: true,
        enumerable: descriptor ? descriptor.enumerable !== false : true,
        get: function () { return current; },
        set: function (value) {
          current = value;
          patchOffset(value);
          try {
            Object.defineProperty(w, "jQuery", {
              configurable: true,
              enumerable: true,
              writable: true,
              value: value
            });
          } catch (e) { /* polling below still verifies the active copy */ }
          reportReady("jquery_assigned");
        }
      });
    } catch (e) { /* polling below remains available */ }
  }

  ensureDateTime();
  patchOffset(w.jQuery || w.$);
  armNextJqueryAssignment();

  var lastLoadedFrame = null;
  var lastLoadedAt = 0;
  var FRAME_MAX_AGE_MS = 2500;

  function isFrame(value) {
    return Boolean(value && /^(IFRAME|FRAME)$/i.test(value.tagName || "") && value.ownerDocument === w.document);
  }

  function isNimsTabFrame(frame) {
    if (!isFrame(frame)) return false;
    var id = String(frame.id || frame.name || "");
    if (id === "frmMainMenu") return true;
    if (/_iframe$/i.test(id)) return true;
    try {
      var src = String(frame.getAttribute("src") || frame.src || "");
      return /\/AHIMSG5\/|\/HISInvestigationG5\/|\/HISClinical\//i.test(src);
    } catch (e) {
      return false;
    }
  }

  function rememberLoadedFrame(event) {
    var frame = event && (event.target || event.srcElement);
    if (!isNimsTabFrame(frame)) return;
    lastLoadedFrame = frame;
    lastLoadedAt = Date.now();
  }

  w.document.addEventListener("load", rememberLoadedFrame, true);

  function recentLoadedFrame() {
    if (!lastLoadedFrame || !lastLoadedFrame.isConnected) return null;
    if (Date.now() - lastLoadedAt > FRAME_MAX_AGE_MS) return null;
    return lastLoadedFrame;
  }

  function eventFrame() {
    try {
      var event = w.event;
      var frame = event && (event.currentTarget || event.target || event.srcElement);
      return isNimsTabFrame(frame) ? frame : null;
    } catch (e) {
      return null;
    }
  }

  function isContentDocumentRace(error) {
    var message = String(error && error.message || error || "");
    return /contentDocument/i.test(message) && /undefined|null|cannot read|not an object/i.test(message);
  }

  function wrapAjaxCompleteTab(fn) {
    if (typeof fn !== "function" || fn.__nimsFrameArgumentAdapter) return fn;
    var wrapped = function (obj) {
      var receiver = this;
      var frame = isNimsTabFrame(obj) ? obj : eventFrame() || recentLoadedFrame();
      if (!frame) {
        postRuntime("nims_runtime_error", "ajaxCompleteTab called without a matching iframe");
        return undefined;
      }
      var attempts = 0;
      function invoke() {
        attempts += 1;
        try {
          return fn.call(receiver, frame);
        } catch (error) {
          if (isContentDocumentRace(error) && attempts < 3) {
            w.setTimeout(invoke, attempts * 100);
            return undefined;
          }
          postRuntime("nims_runtime_error", "ajaxCompleteTab: " + String(error && error.message || error));
          if (w.console && w.console.error) w.console.error("NIMS ajaxCompleteTab compatibility failure", error);
          throw error;
        }
      }
      return invoke();
    };
    wrapped.__nimsFrameArgumentAdapter = true;
    wrapped.__nimsOriginal = fn;
    return wrapped;
  }

  function patchAvailableFunctions(phase) {
    ensureDateTime();
    try { patchOffset(w.jQuery || w.$); } catch (e) { /* page still loading */ }
    try {
      if (typeof w.ajaxCompleteTab === "function" && !w.ajaxCompleteTab.__nimsFrameArgumentAdapter) {
        w.ajaxCompleteTab = wrapAjaxCompleteTab(w.ajaxCompleteTab);
      }
    } catch (error) {
      postRuntime("nims_runtime_error", "ajaxCompleteTab patch: " + String(error && error.message || error));
    }
    reportReady(phase || "check");
  }

  [0, 10, 25, 50, 100, 200, 500, 1000, 2000, 5000, 10000].forEach(function (delay) {
    w.setTimeout(function () { patchAvailableFunctions("timer_" + delay); }, delay);
  });
  w.addEventListener("DOMContentLoaded", function () { patchAvailableFunctions("dom_content_loaded"); }, { once: true });
  w.addEventListener("load", function () { patchAvailableFunctions("window_load"); }, { once: true });
})(typeof window !== "undefined" ? window : null);
