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

  function collectDocuments(root, output, seen, depth) {
    if (!root || depth > 6 || seen.indexOf(root) >= 0) return;
    seen.push(root);
    output.push(root);
    var frames;
    try { frames = root.querySelectorAll("iframe,frame"); } catch (_error) { frames = []; }
    for (var i = 0; i < frames.length; i += 1) {
      try {
        var child = frames[i].contentDocument || (frames[i].contentWindow && frames[i].contentWindow.document);
        if (child) collectDocuments(child, output, seen, depth + 1);
      } catch (_ignored) { /* same-origin frames only */ }
    }
  }

  function allDocuments() {
    var docs = [];
    collectDocuments(w.document, docs, [], 0);
    return docs;
  }

  function elementText(element) {
    return String((element && (element.innerText || element.textContent || element.value || element.title || element.name || element.id)) || "").trim();
  }

  function findCrInput(doc) {
    var inputs;
    try { inputs = doc.querySelectorAll("input,textarea"); } catch (_error) { return null; }
    var fallback = null;
    for (var i = 0; i < inputs.length; i += 1) {
      var input = inputs[i];
      if (input.disabled || input.readOnly || String(input.type || "").toLowerCase() === "hidden") continue;
      var signature = [input.id, input.name, input.placeholder, input.title, input.getAttribute && input.getAttribute("aria-label")].join(" ");
      if (/\bcr\s*(?:no|number)?\b|crno|crnum|patcrno|cr_number/i.test(signature)) return input;
      if (!fallback && /^(?:text|number|tel|search)?$/i.test(String(input.type || ""))) fallback = input;
    }
    return fallback;
  }

  function findSubmitAction(doc) {
    var actions;
    try { actions = doc.querySelectorAll("button,input[type=button],input[type=submit],a"); } catch (_error) { return null; }
    var broad = null;
    for (var i = 0; i < actions.length; i += 1) {
      var action = actions[i];
      if (action.disabled) continue;
      var text = elementText(action);
      if (/^(?:go|search|view|submit|fetch\s*results?)$/i.test(text)) return action;
      if (!broad && /\b(?:go|search|view|submit|fetch)\b/i.test(text)) broad = action;
    }
    return broad;
  }

  function assignValue(input, value) {
    try {
      var prototype = Object.getPrototypeOf(input);
      var descriptor = prototype && Object.getOwnPropertyDescriptor(prototype, "value");
      if (descriptor && typeof descriptor.set === "function") descriptor.set.call(input, value);
      else input.value = value;
    } catch (_ignored) {
      input.value = value;
    }
    ["input", "change", "blur"].forEach(function (name) {
      try { input.dispatchEvent(new Event(name, { bubbles: true })); } catch (_ignored) { /* continue */ }
    });
  }

  function submitThroughDocument(doc, crNumber) {
    var input = findCrInput(doc);
    if (!input || input.__nimsProxyInput) return false;
    assignValue(input, crNumber);
    var action = findSubmitAction(doc);
    if (action && !action.__nimsProxyAction) {
      try { action.click(); return true; } catch (_ignoredClick) { /* try form */ }
    }
    var form = input.form || (input.closest && input.closest("form"));
    if (form) {
      try {
        if (typeof form.requestSubmit === "function") form.requestSubmit();
        else if (typeof form.submit === "function") form.submit();
        else return false;
        return true;
      } catch (_ignoredForm) { /* try page functions */ }
    }
    var view = doc.defaultView || w;
    var candidates = ["getCRWiseReport", "getCrWiseReport", "showCRWiseReport", "showCrWiseReport", "searchCrNo", "searchCRNo", "submitForm"];
    for (var i = 0; i < candidates.length; i += 1) {
      try {
        if (typeof view[candidates[i]] === "function") {
          view[candidates[i]]();
          return true;
        }
      } catch (_ignoredFunction) { /* continue */ }
    }
    return false;
  }

  function submitCrNumber(crNumber) {
    var value = String(crNumber || "").replace(/\D/g, "");
    if (value.length < 6) return { ok: false, reason: "invalid_cr" };
    var docs = allDocuments();
    for (var i = docs.length - 1; i >= 0; i -= 1) {
      if (submitThroughDocument(docs[i], value)) return { ok: true, reason: "submitted", documentCount: docs.length };
    }
    return { ok: false, reason: "cr_field_not_ready", documentCount: docs.length };
  }

  w.__nimsSubmitCrNumber = submitCrNumber;
  w.__nimsCrFieldReady = function () {
    var docs = allDocuments();
    for (var i = 0; i < docs.length; i += 1) if (findCrInput(docs[i])) return true;
    return false;
  };

  function installCrProxy() {
    var doc = w.document;
    if (!doc || !doc.documentElement || doc.getElementById("__nims_cr_proxy_input")) return;
    var holder = doc.createElement("div");
    holder.id = "__nims_cr_proxy_holder";
    holder.style.cssText = "display:none!important;position:absolute!important;width:0!important;height:0!important;overflow:hidden!important";
    var input = doc.createElement("input");
    input.id = "__nims_cr_proxy_input";
    input.name = "crNo";
    input.type = "text";
    input.__nimsProxyInput = true;
    var button = doc.createElement("button");
    button.id = "__nims_cr_proxy_go";
    button.type = "button";
    button.textContent = "Go";
    button.__nimsProxyAction = true;
    button.addEventListener("click", function () {
      var attempts = 0;
      function trySubmit() {
        attempts += 1;
        var result = submitCrNumber(input.value);
        w.__nimsLastCrSubmit = result;
        if (!result.ok && attempts < 30) w.setTimeout(trySubmit, Math.min(150 + attempts * 35, 600));
      }
      trySubmit();
    });
    holder.appendChild(input);
    holder.appendChild(button);
    (doc.body || doc.documentElement).appendChild(holder);
  }

  var lastLoadedNimsFrame = null;
  w.document.addEventListener("load", function (event) {
    var candidate = event && (event.target || event.srcElement);
    if (isFrame(candidate)) lastLoadedNimsFrame = candidate;
  }, true);

  function recentNimsFrame() {
    if (lastLoadedNimsFrame && lastLoadedNimsFrame.isConnected !== false) return lastLoadedNimsFrame;
    if (!w.document || typeof w.document.querySelectorAll !== "function") return null;
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
    try { installCrProxy(); } catch (_ignoredProxy) { /* body still loading */ }
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
