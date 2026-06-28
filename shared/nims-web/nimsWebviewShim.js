// Android-only compatibility adapter for the live NIMS WebView runtime.
//
// The NIMS pages currently expose three WebView-only failures seen on-device:
// 1. date_time is referenced before its defining asset is available;
// 2. tabmenu.js reads .offset().left even when the selected element is absent;
// 3. dynamically-created iframes call ajaxCompleteTab() without the iframe arg.
//
// This adapter fixes only those runtime contracts. It does not click menus,
// submit forms, read credentials, or alter the report-extraction workflow.
(function (w) {
  "use strict";
  if (!w || !w.document) return;

  var ALLOWED_HOSTS = { "nimsts.edu.in": true, "www.nimsts.edu.in": true };
  try {
    if (!ALLOWED_HOSTS[w.location.hostname] || w.location.protocol !== "https:") return;
  } catch (e) {
    return;
  }

  if (typeof w.date_time !== "function") {
    w.date_time = function () { return ""; };
  }

  function patchOffset(jq) {
    if (!jq || !jq.fn || typeof jq.fn.offset !== "function" || jq.fn.__nimsSafeOffset) return false;
    var original = jq.fn.offset;
    jq.fn.offset = function () {
      var value;
      try {
        value = original.apply(this, arguments);
      } catch (error) {
        if (arguments.length) throw error;
        value = null;
      }
      return value == null && arguments.length === 0 ? { top: 0, left: 0 } : value;
    };
    jq.fn.__nimsSafeOffset = true;
    return true;
  }

  // Patch the bundled fallback immediately. Then patch one subsequent jQuery
  // assignment, which covers the page replacing the fallback with its own copy.
  patchOffset(w.jQuery || w.$);
  (function armNextJqueryAssignment() {
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
          } catch (e) { /* keep the accessor if the page prevents replacement */ }
        }
      });
    } catch (e) { /* bounded polling below still patches later copies */ }
  })();

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
      if (!frame) return undefined;
      var attempts = 0;
      function invoke() {
        attempts += 1;
        try {
          return fn.call(receiver, frame);
        } catch (error) {
          if (!isContentDocumentRace(error) || attempts >= 3) return undefined;
          w.setTimeout(invoke, attempts * 100);
          return undefined;
        }
      }
      return invoke();
    };
    wrapped.__nimsFrameArgumentAdapter = true;
    wrapped.__nimsOriginal = fn;
    return wrapped;
  }

  function patchAvailableFunctions() {
    try { patchOffset(w.jQuery || w.$); } catch (e) { /* page still loading */ }
    try {
      if (typeof w.ajaxCompleteTab === "function" && !w.ajaxCompleteTab.__nimsFrameArgumentAdapter) {
        w.ajaxCompleteTab = wrapAjaxCompleteTab(w.ajaxCompleteTab);
      }
    } catch (e) { /* page may be replacing globals while loading */ }
  }

  [0, 10, 25, 50, 100, 200, 500, 1000, 2000, 5000].forEach(function (delay) {
    w.setTimeout(patchAvailableFunctions, delay);
  });
  w.addEventListener("DOMContentLoaded", patchAvailableFunctions, { once: true });
  w.addEventListener("load", patchAvailableFunctions, { once: true });
})(typeof window !== "undefined" ? window : null);
