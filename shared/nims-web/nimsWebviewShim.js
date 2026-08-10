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

  function documentHref(doc) {
    try { return String(doc && doc.location && doc.location.href || ""); } catch (_error) { return ""; }
  }

  function isCrContext(doc) {
    var href = documentHref(doc);
    if (/\/HISInvestigationG5\/new_investigation\/viewcrnowisereportprocess\.cnt/i.test(href)) return true;
    try {
      if (doc.querySelector('form[name="viewExternalInvFB"],form#viewExternalInvFB,form[action*="viewcrnowisereportprocess.cnt"]')) return true;
    } catch (_error) { /* continue */ }
    var text = "";
    try { text = String((doc.body && doc.body.innerText) || ""); } catch (_error2) { text = ""; }
    return /CR\s*(?:No|Number)|CR\s*Wise\s*Result\s*Report/i.test(text);
  }

  function findCrInput(doc) {
    var inputs;
    try { inputs = doc.querySelectorAll("input,textarea"); } catch (_error) { return null; }

    // The live G5 CR form uses patCrNo. Prefer that exact contract first.
    for (var i = 0; i < inputs.length; i += 1) {
      var exact = inputs[i];
      if (exact.disabled || exact.readOnly || String(exact.type || "").toLowerCase() === "hidden") continue;
      var exactSignature = [exact.id, exact.name].join(" ");
      if (/\bpatcrno\b/i.test(exactSignature)) return exact;
    }

    for (var j = 0; j < inputs.length; j += 1) {
      var input = inputs[j];
      if (input.disabled || input.readOnly || String(input.type || "").toLowerCase() === "hidden") continue;
      var signature = [input.id, input.name, input.placeholder, input.title, input.getAttribute && input.getAttribute("aria-label")].join(" ");
      if (/\bcr\s*(?:no|number)?\b|crno|crnum|cr_number/i.test(signature)) return input;
    }

    // Never accept an arbitrary text field outside a validated CR-search form.
    if (!isCrContext(doc)) return null;
    var form = null;
    try { form = doc.querySelector('form[name="viewExternalInvFB"],form#viewExternalInvFB,form[action*="viewcrnowisereportprocess.cnt"]'); } catch (_error3) { form = null; }
    if (!form) return null;
    var formInputs;
    try { formInputs = form.querySelectorAll('input:not([type="hidden"]),textarea'); } catch (_error4) { return null; }
    if (formInputs.length !== 1) return null;
    var candidate = formInputs[0];
    if (candidate.disabled || candidate.readOnly) return null;
    return candidate;
  }

  function findSubmitAction(doc, input) {
    var form = input && (input.form || (input.closest && input.closest("form")));
    var actions = [];
    if (form) {
      try { actions = form.querySelectorAll("button,input[type=button],input[type=submit],a"); } catch (_error) { actions = []; }
      for (var i = 0; i < actions.length; i += 1) {
        var local = actions[i];
        if (local.disabled || local.__nimsProxyAction) continue;
        if (/^(?:go|search|submit|fetch\s*results?)$/i.test(elementText(local))) return local;
      }
      for (var j = 0; j < actions.length; j += 1) {
        var localBroad = actions[j];
        if (localBroad.disabled || localBroad.__nimsProxyAction) continue;
        if (/\b(?:go|search|submit|fetch)\b/i.test(elementText(localBroad))) return localBroad;
      }
    }

    // Global fallback is permitted only inside a validated CR context and never
    // uses generic "View" actions, which can belong to report rows.
    if (!isCrContext(doc)) return null;
    try { actions = doc.querySelectorAll("button,input[type=button],input[type=submit],a"); } catch (_error2) { return null; }
    for (var k = 0; k < actions.length; k += 1) {
      var action = actions[k];
      if (action.disabled || action.__nimsProxyAction) continue;
      if (/^(?:go|search|submit|fetch\s*results?)$/i.test(elementText(action))) return action;
    }
    return null;
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

  function ensureCrMode(doc, input) {
    var form = input && (input.form || (input.closest && input.closest("form")));
    if (!form) return;
    var hmode = null;
    try { hmode = form.querySelector('input[name="hmode"],input#hmode'); } catch (_error) { hmode = null; }
    if (hmode && !String(hmode.value || "").trim()) hmode.value = "SHOWPATDETAILS";
  }

  function invokeKnownCrFunction(doc, input) {
    var view = doc.defaultView || w;
    var candidates = [
      "getCRWiseReport", "getCrWiseReport", "showCRWiseReport", "showCrWiseReport",
      "searchCrNo", "searchCRNo", "getPatientDetails", "showPatientDetails"
    ];
    for (var i = 0; i < candidates.length; i += 1) {
      try {
        if (typeof view[candidates[i]] === "function") {
          // Most G5 functions read patCrNo from the DOM, so call with no
          // arguments first; a harmless value argument is used only as fallback.
          try { view[candidates[i]](); } catch (_first) { view[candidates[i]](String(input.value || "")); }
          return candidates[i];
        }
      } catch (_ignoredFunction) { /* continue */ }
    }
    return "";
  }

  function submitThroughDocument(doc, crNumber) {
    var input = findCrInput(doc);
    if (!input || input.__nimsProxyInput) return null;
    assignValue(input, crNumber);
    ensureCrMode(doc, input);

    var action = findSubmitAction(doc, input);
    if (action && !action.__nimsProxyAction) {
      try {
        action.click();
        return { ok: true, reason: "clicked_cr_submit", field: /patcrno/i.test(String(input.name || input.id || "")) ? "patCrNo" : "cr" };
      } catch (_ignoredClick) { /* try function/form */ }
    }

    var functionName = invokeKnownCrFunction(doc, input);
    if (functionName) return { ok: true, reason: "called_cr_function", functionName: functionName };

    var form = input.form || (input.closest && input.closest("form"));
    if (form) {
      try {
        if (typeof form.requestSubmit === "function") form.requestSubmit();
        else if (typeof form.submit === "function") form.submit();
        else return null;
        return { ok: true, reason: "submitted_cr_form" };
      } catch (_ignoredForm) { /* no more fallbacks */ }
    }
    return null;
  }

  function submitCrNumber(crNumber) {
    var value = String(crNumber || "").replace(/\D/g, "");
    if (value.length < 6) return { ok: false, reason: "invalid_cr" };
    var docs = allDocuments();
    for (var i = docs.length - 1; i >= 0; i -= 1) {
      var result = submitThroughDocument(docs[i], value);
      if (result && result.ok) {
        result.documentCount = docs.length;
        return result;
      }
    }
    return { ok: false, reason: "cr_field_not_ready", documentCount: docs.length };
  }

  w.__nimsSubmitCrNumber = submitCrNumber;
  w.__nimsCrFieldReady = function () {
    var docs = allDocuments();
    for (var i = docs.length - 1; i >= 0; i -= 1) {
      var input = findCrInput(docs[i]);
      if (input && !input.__nimsProxyInput) return true;
    }
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
