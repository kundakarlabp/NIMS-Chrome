// Android-only compatibility adapter for the live NIMS WebView runtime.
(function (w) {
  "use strict";
  if (!w || !w.document) return;

  var ALLOWED_HOSTS = { "nimsts.edu.in": true, "www.nimsts.edu.in": true };
  try {
    if (!ALLOWED_HOSTS[w.location.hostname] || w.location.protocol !== "https:") return;
  } catch (e) {
    return;
  }

  function ensureDateTime() {
    if (typeof w.date_time !== "function") {
      w.date_time = function () { return ""; };
      w.date_time.__nimsCompatibilityFallback = true;
    }
  }

  function patchOffset(jq) {
    if (!jq || !jq.fn || typeof jq.fn.offset !== "function" || jq.fn.offset.__nimsSafeOffset) return false;
    var original = jq.fn.offset;
    var wrapped = function () {
      var value;
      try {
        value = original.apply(this, arguments);
      } catch (error) {
        if (arguments.length) throw error;
        value = null;
      }
      if (value == null && arguments.length === 0) return { top: 0, left: 0 };
      if (value && typeof value === "object") {
        if (typeof value.top !== "number") value.top = 0;
        if (typeof value.left !== "number") value.left = 0;
      }
      return value;
    };
    wrapped.__nimsSafeOffset = true;
    wrapped.__nimsOriginal = original;
    jq.fn.offset = wrapped;
    return true;
  }

  function armNextJqueryAssignment() {
    var descriptor;
    try { descriptor = Object.getOwnPropertyDescriptor(w, "jQuery"); } catch (e) { descriptor = null; }
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
          } catch (e) { }
        }
      });
    } catch (e) { }
  }

  ensureDateTime();
  patchOffset(w.jQuery || w.$);
  armNextJqueryAssignment();

  var recentFrames = [];
  var FRAME_MAX_AGE_MS = 1200;

  function isFrame(value) {
    return Boolean(value && /^(IFRAME|FRAME)$/i.test(value.tagName || "") && value.ownerDocument === w.document);
  }

  function isNimsTabFrame(frame) {
    if (!isFrame(frame)) return false;
    var id = String(frame.id || frame.name || "");
    if (id === "frmMainMenu" || /_iframe$/i.test(id)) return true;
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
    var now = Date.now();
    recentFrames = recentFrames.filter(function (entry) {
      return entry.frame && entry.frame.isConnected && now - entry.at <= FRAME_MAX_AGE_MS && entry.frame !== frame;
    });
    recentFrames.push({ frame: frame, at: now });
  }

  w.document.addEventListener("load", rememberLoadedFrame, true);

  function uniqueRecentFrame() {
    var now = Date.now();
    recentFrames = recentFrames.filter(function (entry) {
      return entry.frame && entry.frame.isConnected && now - entry.at <= FRAME_MAX_AGE_MS;
    });
    return recentFrames.length === 1 ? recentFrames[0].frame : null;
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
      var frame = isNimsTabFrame(obj) ? obj : eventFrame() || uniqueRecentFrame();
      if (!frame) {
        if (w.console && w.console.warn) w.console.warn("NIMS ajaxCompleteTab skipped: no unique iframe");
        return undefined;
      }
      var attempts = 0;
      function invoke() {
        attempts += 1;
        try {
          return fn.call(receiver, frame);
        } catch (error) {
          if (isContentDocumentRace(error)) {
            if (attempts < 3) {
              w.setTimeout(invoke, attempts * 100);
              return undefined;
            }
            if (w.console && w.console.warn) w.console.warn("NIMS iframe document unavailable");
            return undefined;
          }
          if (w.console && w.console.error) w.console.error("NIMS ajaxCompleteTab unexpected error", error);
          throw error;
        }
      }
      return invoke();
    };
    wrapped.__nimsFrameArgumentAdapter = true;
    wrapped.__nimsOriginal = fn;
    return wrapped;
  }

  function patchAvailableFunctions() {
    ensureDateTime();
    try { patchOffset(w.jQuery || w.$); } catch (e) { }
    try {
      if (typeof w.ajaxCompleteTab === "function" && !w.ajaxCompleteTab.__nimsFrameArgumentAdapter) {
        w.ajaxCompleteTab = wrapAjaxCompleteTab(w.ajaxCompleteTab);
      }
    } catch (e) { }
  }

  [0, 10, 25, 50, 100, 200, 500, 1000, 2000, 5000, 10000].forEach(function (delay) {
    w.setTimeout(patchAvailableFunctions, delay);
  });
  w.addEventListener("DOMContentLoaded", patchAvailableFunctions, { once: true });
  w.addEventListener("load", patchAvailableFunctions, { once: true });
})(typeof window !== "undefined" ? window : null);
