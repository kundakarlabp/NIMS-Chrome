// Android-only compatibility adapter for the live NIMS WebView contract.
//
// The live Android screenshots confirm three independent legacy-page defects:
//   1. loginLogin.action calls date_time() although the global is absent.
//   2. tabmenu.js dereferences $(...).offset().left when offset() is undefined.
//   3. addTab emits onLoad="ajaxCompleteTab();" although ajaxCompleteTab expects
//      the loaded iframe argument.
//
// This adapter repairs only those contracts. It never clicks a menu, calls
// callMenu, changes EasyUI state, replaces an existing jQuery instance, or
// modifies the report extraction core.
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
    try {
      if (typeof w.date_time !== "function") {
        w.date_time = function () { return ""; };
        w.date_time.__nimsCompatibilityFallback = true;
      }
    } catch (e) { /* page may be replacing globals while loading */ }
  }

  function patchJqueryOffset() {
    try {
      var jq = w.jQuery;
      if (typeof jq !== "function" || !jq.fn || typeof jq.fn.offset !== "function") return;
      if (jq.fn.offset.__nimsSafeOffset) return;
      var original = jq.fn.offset;
      var wrapped = function () {
        var value = original.apply(this, arguments);
        if (value && typeof value === "object") {
          if (typeof value.left !== "number") value.left = 0;
          if (typeof value.top !== "number") value.top = 0;
          return value;
        }
        return { left: 0, top: 0 };
      };
      wrapped.__nimsSafeOffset = true;
      wrapped.__nimsOriginal = original;
      jq.fn.offset = wrapped;
    } catch (e) { /* no compatible jQuery in this frame yet */ }
  }

  ensureDateTime();
  patchJqueryOffset();

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

  // load does not bubble; capture sees the iframe before its inline onLoad runs.
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
    ensureDateTime();
    patchJqueryOffset();
    try {
      if (typeof w.ajaxCompleteTab === "function" && !w.ajaxCompleteTab.__nimsFrameArgumentAdapter) {
        w.ajaxCompleteTab = wrapAjaxCompleteTab(w.ajaxCompleteTab);
      }
    } catch (e) { /* page may be replacing globals while loading */ }
  }

  [0, 10, 25, 50, 100, 200, 500, 1000, 2000, 5000, 10000].forEach(function (delay) {
    w.setTimeout(patchAvailableFunctions, delay);
  });
  w.addEventListener("DOMContentLoaded", patchAvailableFunctions, { once: true });
  w.addEventListener("load", patchAvailableFunctions, { once: true });
})(typeof window !== "undefined" ? window : null);
