// Android-only compatibility adapter for the live NIMS tab contract.
//
// NIMS creates report iframes with onLoad="ajaxCompleteTab();" but the page
// function expects ajaxCompleteTab(iframe). This adapter supplies only the
// iframe that has just emitted its load event. It does not inject jQuery,
// alter EasyUI, click menus, call callMenu, or patch the report core.
(function (w) {
  "use strict";
  if (!w || !w.document) return;

  var ALLOWED_HOSTS = { "nimsts.edu.in": true, "www.nimsts.edu.in": true };
  try {
    if (!ALLOWED_HOSTS[w.location.hostname] || w.location.protocol !== "https:") return;
  } catch (e) {
    return;
  }

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

  // load does not bubble, but capture observes iframe loads before the inline
  // onLoad handler executes in Chromium/WebView.
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
        // The original call is known to be non-fatal and would throw here.
        // Skip it rather than retrying forever or choosing a stale iframe.
        return undefined;
      }
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

  function patchAvailableFunction() {
    try {
      if (typeof w.ajaxCompleteTab === "function" && !w.ajaxCompleteTab.__nimsFrameArgumentAdapter) {
        w.ajaxCompleteTab = wrapAjaxCompleteTab(w.ajaxCompleteTab);
      }
    } catch (e) { /* page may be replacing globals while loading */ }
  }

  [0, 50, 200, 500, 1000, 2000, 5000].forEach(function (delay) {
    w.setTimeout(patchAvailableFunction, delay);
  });
  w.addEventListener("DOMContentLoaded", patchAvailableFunction, { once: true });
  w.addEventListener("load", patchAvailableFunction, { once: true });
})(typeof window !== "undefined" ? window : null);
