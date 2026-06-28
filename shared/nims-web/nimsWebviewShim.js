// Android-only compatibility and live NIMS contract adapter.
//
// This shim runs at document-start in every WebView frame. It deliberately
// avoids redefining global properties such as window.$, window.jQuery,
// window.NimsReportCore, or window.ajaxCompleteTab. The NIMS login/menu pages
// replace those globals while loading; accessor hooks can turn valid page
// functions into "not a function" failures. Instead, this file patches only
// functions that are currently present and rechecks them for a bounded period.
(function (w) {
  if (!w) return;

  var CR_ENDPOINT = "/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt";
  var CR_RESULT_ENDPOINT = "/HISInvestigationG5/new_investigation/invResultReportPrintingCRNoWise.cnt";
  var CR_MENU_ID = "Cr_No_Wise_Result_Report_Printing_New";
  var CR_MENU_LABEL = "Cr No Wise Result Report Printing New";
  var MENU_FRAME_ID = "frmMainMenu";
  var OUTER_REPORT_FRAME_ID = "Cr No Wise Result Report Printing New_iframe";
  var INNER_REPORT_FRAME_ID = "Cr No Wise Result Report Printing_iframe";
  var CR_FORM_NAME = "InvResultReportPrintingFB";
  var openSequence = 0;

  try {
    if (typeof w.date_time !== "function") {
      w.date_time = function () { return ""; };
    }

    function safePath(value) {
      try {
        var base = w.location && w.location.href ? w.location.href : "https://www.nimsts.edu.in/";
        var url = new URL(value || base, base);
        return url.protocol === "about:" ? url.href : url.hostname + url.pathname;
      } catch (e) {
        return String(value || "").split("?")[0].split("#")[0].slice(0, 180);
      }
    }

    function bridgePost(payload) {
      try {
        var bridge = w.nimsAndroidBridge;
        if (!bridge || typeof bridge.postMessage !== "function") return false;
        bridge.postMessage(JSON.stringify(payload));
        return true;
      } catch (e) {
        return false;
      }
    }

    var capturedErrors = [];
    w.__nimsShimErrors = capturedErrors;

    function report(extra) {
      try {
        var doc = w.document;
        var body = doc && doc.body;
        var notes = capturedErrors.slice(0, 5);
        if (extra) notes.unshift(extra);
        return bridgePost({
          type: "nims_frame_debug",
          url: safePath(doc && doc.location ? doc.location.href : w.location && w.location.href),
          children: body && body.querySelectorAll ? body.querySelectorAll("*").length : 0,
          textLen: body && body.innerText ? String(body.innerText).trim().length : 0,
          height: body ? body.scrollHeight || 0 : 0,
          errors: notes.slice(0, 6)
        });
      } catch (e) {
        return false;
      }
    }

    if (typeof w.addEventListener === "function") {
      w.addEventListener("error", function (event) {
        try {
          if (capturedErrors.length >= 12) return;
          capturedErrors.push(
            String(event && event.message || "error") + " @" +
            String(event && event.filename || "").split("/").pop() + ":" +
            String(event && event.lineno || "?")
          );
        } catch (e) { /* ignore */ }
      });
    }

    function patchOffset(jq) {
      if (!jq || !jq.fn || jq.fn.__nimsOffsetPatched || typeof jq.fn.offset !== "function") return false;
      var original = jq.fn.offset;
      jq.fn.offset = function () {
        var value = original.apply(this, arguments);
        return value == null ? { top: 0, left: 0 } : value;
      };
      jq.fn.__nimsOffsetPatched = true;
      return true;
    }

    function isContentDocumentRace(error) {
      var message = String(error && error.message || error || "");
      return /contentDocument/i.test(message) && /undefined|null|cannot read|not an object/i.test(message);
    }

    function wrapAjaxCompleteTab(fn) {
      if (typeof fn !== "function" || fn.__nimsContentDocumentRetry) return fn;
      var wrapped = function () {
        var receiver = this;
        var args = arguments;
        var attempts = 0;
        function invoke() {
          try {
            return fn.apply(receiver, args);
          } catch (error) {
            attempts += 1;
            if (!isContentDocumentRace(error)) throw error;
            if (attempts < 12 && typeof w.setTimeout === "function") {
              report("NAV ajaxCompleteTab deferred attempt=" + attempts);
              w.setTimeout(invoke, Math.min(1200, 100 * attempts));
              return undefined;
            }
            report("NAV ajaxCompleteTab stopped after persistent frame race");
            return undefined;
          }
        }
        return invoke();
      };
      wrapped.__nimsContentDocumentRetry = true;
      wrapped.__nimsOriginal = fn;
      return wrapped;
    }

    function isElementVisible(element) {
      if (!element) return true;
      try {
        if (element.hidden || element.getAttribute && element.getAttribute("aria-hidden") === "true") return false;
        var owner = element.ownerDocument && element.ownerDocument.defaultView;
        var style = owner && owner.getComputedStyle ? owner.getComputedStyle(element) : null;
        if (style && (style.display === "none" || style.visibility === "hidden" || style.visibility === "collapse" || Number(style.opacity) === 0)) return false;
      } catch (e) { /* ignore */ }
      return true;
    }

    function isVisibleThroughAncestors(element) {
      for (var node = element; node; node = node.parentElement) {
        if (!isElementVisible(node)) return false;
      }
      return true;
    }

    function collectDocuments(startDoc, maxDepth) {
      var output = [];
      var seen = [];
      var limit = typeof maxDepth === "number" ? maxDepth : 7;

      function alreadySeen(doc) {
        for (var i = 0; i < seen.length; i += 1) if (seen[i] === doc) return true;
        seen.push(doc);
        return false;
      }

      function visit(doc, depth, frameElement, parentVisible) {
        if (!doc || depth > limit || alreadySeen(doc)) return;
        var visible = parentVisible !== false && isElementVisible(frameElement);
        output.push({ doc: doc, depth: depth, frameElement: frameElement || null, visible: visible });
        var frames = [];
        try { frames = Array.prototype.slice.call(doc.querySelectorAll("iframe, frame")); } catch (e) { frames = []; }
        for (var i = 0; i < frames.length; i += 1) {
          var child = null;
          try { child = frames[i].contentDocument || null; } catch (e) { child = null; }
          if (child) visit(child, depth + 1, frames[i], visible);
        }
      }

      visit(startDoc || w.document, 0, null, true);
      return output;
    }

    function resolveTopDocument(doc) {
      var current = doc || w.document;
      try {
        var topWindow = current.defaultView && current.defaultView.top;
        if (topWindow && topWindow.document) return topWindow.document;
      } catch (e) { /* cross-origin */ }
      return current;
    }

    function frameIdentity(frame) {
      try {
        return {
          id: String(frame.id || ""),
          name: String(frame.name || ""),
          src: safePath(frame.getAttribute && frame.getAttribute("src") || frame.src || "")
        };
      } catch (e) {
        return { id: "", name: "", src: "" };
      }
    }

    function callArity(code, expectedName) {
      var text = String(code || "").trim();
      var match = text.match(/^\s*([\w$.]+)\s*\(([\s\S]*)\)\s*;?\s*$/);
      if (!match || match[1].split(".").pop() !== expectedName) return -1;
      var body = match[2].trim();
      if (!body) return 0;
      var quote = "";
      var escaped = false;
      var depth = 0;
      var commas = 0;
      for (var i = 0; i < body.length; i += 1) {
        var ch = body.charAt(i);
        if (quote) {
          if (escaped) escaped = false;
          else if (ch === "\\") escaped = true;
          else if (ch === quote) quote = "";
          continue;
        }
        if (ch === "'" || ch === '"' || ch === "`") { quote = ch; continue; }
        if (ch === "(" || ch === "[" || ch === "{") depth += 1;
        else if (ch === ")" || ch === "]" || ch === "}") depth -= 1;
        else if (ch === "," && depth === 0) commas += 1;
      }
      return commas + 1;
    }

    function isContractFrame(frame) {
      var identity = frameIdentity(frame);
      return identity.id === OUTER_REPORT_FRAME_ID || identity.name === OUTER_REPORT_FRAME_ID ||
        identity.id === INNER_REPORT_FRAME_ID || identity.name === INNER_REPORT_FRAME_ID ||
        identity.src.indexOf("viewcrnowisereportprocess.cnt") >= 0 ||
        identity.src.indexOf("invResultReportPrintingCRNoWise.cnt") >= 0;
    }

    function isReportContractEntry(entry) {
      if (!entry) return false;
      if (entry.frameElement && isContractFrame(entry.frameElement)) return true;
      try {
        var path = safePath(entry.doc && entry.doc.location ? entry.doc.location.href : "");
        return path.indexOf("invResultReportPrintingCRNoWise.cnt") >= 0 ||
          path.indexOf("viewcrnowisereportprocess.cnt") >= 0;
      } catch (e) {
        return false;
      }
    }

    function genuineReportRows(doc) {
      if (!doc || !doc.querySelectorAll) return [];
      var matches = [];
      try {
        var rows = Array.prototype.slice.call(doc.querySelectorAll("tr"));
        for (var i = 0; i < rows.length; i += 1) {
          if (!isVisibleThroughAncestors(rows[i])) continue;
          var actions = Array.prototype.slice.call(rows[i].querySelectorAll("[onclick]"));
          for (var j = 0; j < actions.length; j += 1) {
            if (!isVisibleThroughAncestors(actions[j])) continue;
            if (callArity(actions[j].getAttribute("onclick") || "", "printReport") === 1) {
              matches.push(rows[i]);
              break;
            }
          }
        }
      } catch (e) { /* ignore */ }
      return matches;
    }

    function hasLiveCrForm(doc) {
      if (!doc || !doc.querySelectorAll) return false;
      try {
        var inputs = Array.prototype.slice.call(doc.querySelectorAll("input, textarea, select"));
        var crInput = null;
        for (var i = 0; i < inputs.length; i += 1) {
          var key = String(inputs[i].id || "") + " " + String(inputs[i].name || "");
          if (!/patcrno|cr\s*(no|number)|crno|crnumber/i.test(key)) continue;
          if (String(inputs[i].type || "").toLowerCase() === "hidden") continue;
          if (!isVisibleThroughAncestors(inputs[i])) continue;
          crInput = inputs[i];
          break;
        }
        if (!crInput) return false;

        var forms = Array.prototype.slice.call(doc.querySelectorAll("form"));
        for (var j = 0; j < forms.length; j += 1) {
          var name = String(forms[j].name || forms[j].id || "");
          var action = safePath(forms[j].getAttribute && forms[j].getAttribute("action") || "");
          if (name === CR_FORM_NAME || action.indexOf("viewcrnowisereportprocess.cnt") >= 0 ||
              action.indexOf("invResultReportPrintingCRNoWise.cnt") >= 0) return true;
        }

        var labels = "";
        try {
          labels = Array.prototype.slice.call(doc.querySelectorAll("label, th, td, h1, h2, h3, legend, span, div"))
            .map(function (node) { return String(node.innerText || node.textContent || ""); }).join(" ");
        } catch (e) { labels = ""; }
        return /CR\s*No|CR\s*Number|CR\s*Wise\s*Result\s*Report\s*Printing/i.test(labels);
      } catch (e) {
        return false;
      }
    }

    function liveContractState(doc) {
      var topDoc = resolveTopDocument(doc);
      var entries = collectDocuments(topDoc, 7);
      var contractFrames = [];
      var visibleEntries = entries.filter(function (entry) { return entry.visible !== false; });
      for (var i = 0; i < entries.length; i += 1) {
        if (entries[i].frameElement && isContractFrame(entries[i].frameElement)) {
          contractFrames.push(frameIdentity(entries[i].frameElement));
        }
      }

      for (var r = 0; r < visibleEntries.length; r += 1) {
        if (isReportContractEntry(visibleEntries[r])) {
          var rows = genuineReportRows(visibleEntries[r].doc);
          if (rows.length) return { stage: "report_list", done: true, contractFrames: contractFrames, depth: visibleEntries[r].depth, rowCount: rows.length };
        }
      }
      for (var c = 0; c < visibleEntries.length; c += 1) {
        if (isReportContractEntry(visibleEntries[c]) && hasLiveCrForm(visibleEntries[c].doc)) {
          return { stage: "cr_search", done: true, contractFrames: contractFrames, depth: visibleEntries[c].depth, rowCount: 0 };
        }
      }
      if (contractFrames.length) return { stage: "loading", done: false, contractFrames: contractFrames, depth: -1, rowCount: 0 };
      return { stage: "absent", done: false, contractFrames: [], depth: -1, rowCount: 0 };
    }

    function navigationReadyResult(state) {
      return {
        ok: true,
        stage: state.stage,
        action: "none",
        done: true,
        canonicalFallbackAttempted: false,
        transitionObserved: true,
        frameDepth: state.depth
      };
    }

    function patchCore(core) {
      if (!core || typeof core !== "object" || core.__nimsLiveContractPatched) return core;
      var originalNavigate = typeof core.navigateToCrWiseReports === "function" ? core.navigateToCrWiseReports : null;
      var originalStep = typeof core.navigateCurrentDocumentStep === "function" ? core.navigateCurrentDocumentStep : null;
      var originalDetect = typeof core.detectNimsPageStage === "function" ? core.detectNimsPageStage : null;
      var originalDiagnose = typeof core.diagnosePage === "function" ? core.diagnosePage : null;

      function patchedNavigate(doc) {
        var state = liveContractState(doc || w.document);
        if (state.stage === "cr_search" || state.stage === "report_list") return navigationReadyResult(state);
        if (state.stage === "loading") {
          return { ok: true, stage: "investigation_menu", action: "waiting_for_report_frame", done: false, canonicalFallbackAttempted: false, transitionObserved: false };
        }
        return originalNavigate ? originalNavigate.call(core, doc || w.document) : { ok: false, stage: "unknown", action: "none", done: false, errorCode: "navigation_contract_not_found" };
      }

      core.navigateToCrWiseReports = patchedNavigate;
      core.navigateCurrentDocumentStep = function (doc) {
        var state = liveContractState(doc || w.document);
        if (state.stage === "cr_search" || state.stage === "report_list") return navigationReadyResult(state);
        if (state.stage === "loading") return patchedNavigate(doc || w.document);
        return originalStep ? originalStep.call(core, doc || w.document) : patchedNavigate(doc || w.document);
      };
      core.detectNimsPageStage = function (doc) {
        var state = liveContractState(doc || w.document);
        if (state.stage === "cr_search" || state.stage === "report_list") {
          return { stage: state.stage, safePath: "", framesChecked: collectDocuments(resolveTopDocument(doc || w.document), 7).length, evidence: ["live_nested_report_contract"] };
        }
        return originalDetect ? originalDetect.call(core, doc || w.document) : { stage: "unknown", safePath: "", framesChecked: 0, evidence: [] };
      };
      core.diagnosePage = function (doc) {
        var result = originalDiagnose ? originalDiagnose.call(core, doc || w.document) : {};
        var state = liveContractState(doc || w.document);
        result.liveContractStage = state.stage;
        result.liveContractFrameIds = state.contractFrames.map(function (item) { return item.id || item.name; }).filter(Boolean);
        if (state.stage === "cr_search" || state.stage === "report_list") {
          result.detectedStage = state.stage;
          result.recommendedNextStep = state.stage === "cr_search" ? "Enter the CR number in NIMS." : "Analyze Current Results.";
          result.crSearchFormFound = state.stage === "cr_search";
        }
        // Prevent the logged-in shell/menu tables from being misclassified as a
        // report list. Only rows inside the confirmed nested CR/report contract
        // are allowed to drive Android's REPORT_PAGE_READY state.
        result.viewReportRows = state.stage === "report_list" ? state.rowCount : 0;
        result.printReportRows = state.stage === "report_list" ? state.rowCount : 0;
        return result;
      };
      core.__nimsLiveContractPatched = true;
      return core;
    }

    function patchAvailableGlobals() {
      try { patchOffset(w.jQuery); } catch (e) { /* ignore */ }
      try { patchOffset(w.$); } catch (e) { /* ignore */ }
      try {
        if (typeof w.ajaxCompleteTab === "function" && !w.ajaxCompleteTab.__nimsContentDocumentRetry) {
          w.ajaxCompleteTab = wrapAjaxCompleteTab(w.ajaxCompleteTab);
        }
      } catch (e) { /* ignore */ }
      try { if (w.NimsReportCore) patchCore(w.NimsReportCore); } catch (e) { /* ignore */ }
    }

    patchAvailableGlobals();
    if (typeof w.setInterval === "function") {
      var patchChecks = 0;
      var patchTimer = w.setInterval(function () {
        patchChecks += 1;
        patchAvailableGlobals();
        if (patchChecks >= 1200) w.clearInterval(patchTimer);
      }, 50);
    }

    function isTopWindow() {
      try { return w.top === w; } catch (e) { return false; }
    }

    function clearStoredReport(reason) {
      if (!isTopWindow()) return;
      bridgePost({ type: "nims_report_frame", href: safePath(w.location && w.location.href), rowCount: 0, rows: [], clearReason: reason || "navigation" });
    }

    function investigationTarget(node) {
      for (var element = node, depth = 0; element && depth < 7; element = element.parentElement, depth += 1) {
        try {
          var onclick = element.getAttribute ? element.getAttribute("onclick") || "" : "";
          var label = String(element.innerText || element.textContent || element.value || "").replace(/\s+/g, " ").trim();
          if (/menuSelected\s*\(\s*['"]Investigation['"]\s*,\s*true\s*\)/i.test(onclick) || /^Investigation$/i.test(label)) return true;
        } catch (e) { /* ignore */ }
      }
      return false;
    }

    function menuFrame(doc) {
      try {
        return (doc.getElementById && doc.getElementById(MENU_FRAME_ID)) ||
          (doc.querySelector && doc.querySelector('iframe[name="frmMainMenu"],frame[name="frmMainMenu"]')) || null;
      } catch (e) {
        return null;
      }
    }

    function exactCrAnchor(doc) {
      if (!doc) return null;
      try {
        var exact = doc.getElementById && doc.getElementById(CR_MENU_ID);
        if (exact && isVisibleThroughAncestors(exact)) return exact;
        var nodes = doc.querySelectorAll ? Array.prototype.slice.call(doc.querySelectorAll("[onclick],a,button")) : [];
        for (var i = 0; i < nodes.length; i += 1) {
          if (!isVisibleThroughAncestors(nodes[i])) continue;
          var onclick = nodes[i].getAttribute ? nodes[i].getAttribute("onclick") || "" : "";
          var label = String(nodes[i].innerText || nodes[i].textContent || nodes[i].value || "").replace(/\s+/g, " ").trim();
          if (onclick.indexOf(CR_ENDPOINT) >= 0 || onclick.indexOf(CR_MENU_ID) >= 0 || label === CR_MENU_LABEL) return nodes[i];
        }
      } catch (e) { /* ignore */ }
      return null;
    }

    function clickElement(element) {
      if (!element) return false;
      try { if (typeof element.click === "function") { element.click(); return true; } } catch (e) { /* ignore */ }
      return false;
    }

    function openCrAfterInvestigation() {
      var sequence = ++openSequence;
      var checks = 0;
      var clickedAnchor = false;
      var calledMenu = false;
      clearStoredReport("investigation_click");

      function attempt() {
        if (sequence !== openSequence) return;
        checks += 1;
        try {
          var state = liveContractState(w.document);
          if (state.stage === "cr_search" || state.stage === "report_list") {
            report("NAV CR contract ready stage=" + state.stage + " depth=" + state.depth);
            return;
          }

          var frame = menuFrame(w.document);
          var childDoc = frame && frame.contentDocument;
          var childWindow = frame && frame.contentWindow;
          var ready = childDoc && (childDoc.readyState === "interactive" || childDoc.readyState === "complete");
          var anchor = ready ? exactCrAnchor(childDoc) : null;

          if (!clickedAnchor && anchor && clickElement(anchor)) {
            clickedAnchor = true;
            report("NAV clicked exact CR-wise menu");
          }

          // A legacy ajaxCompleteTab race can leave only the tab header visible.
          // Re-invoke the authenticated NIMS menu function once; never navigate
          // directly to an unticketed endpoint.
          if (checks >= 10 && !calledMenu && ready && childWindow && typeof childWindow.callMenu === "function") {
            calledMenu = true;
            childWindow.callMenu(CR_ENDPOINT, CR_MENU_ID);
            report("NAV retried authenticated child callMenu");
          }
        } catch (error) {
          if (!isContentDocumentRace(error)) {
            report("NAV CR open error=" + String(error && error.message || "unknown").slice(0, 120));
            return;
          }
        }

        if (checks < 60 && typeof w.setTimeout === "function") w.setTimeout(attempt, 250);
        else report("NAV CR contract not ready after retry");
      }

      if (typeof w.setTimeout === "function") w.setTimeout(attempt, 250);
      else attempt();
    }

    function isCrSubmitTarget(node) {
      for (var element = node, depth = 0; element && depth < 6; element = element.parentElement, depth += 1) {
        try {
          var form = element.form || (element.closest && element.closest("form"));
          var name = form && String(form.name || form.id || "");
          var action = form && safePath(form.getAttribute && form.getAttribute("action") || "");
          if (name === CR_FORM_NAME || action.indexOf("invResultReportPrintingCRNoWise.cnt") >= 0) return true;
        } catch (e) { /* ignore */ }
      }
      return false;
    }

    function install() {
      var doc = w.document;
      if (!doc || doc.__nimsNativeCrOpenInstalled || typeof doc.addEventListener !== "function") return;
      doc.__nimsNativeCrOpenInstalled = true;
      doc.addEventListener("click", function (event) {
        if (isCrSubmitTarget(event && event.target)) clearStoredReport("cr_submit");
        if (!isTopWindow() || !investigationTarget(event && event.target)) return;
        openCrAfterInvestigation();
      }, true);
      doc.addEventListener("submit", function (event) {
        if (isCrSubmitTarget(event && event.target)) clearStoredReport("cr_submit");
      }, true);
    }

    if (w.document && typeof w.setTimeout === "function") {
      var reportAttempts = 0;
      var fire = function () {
        reportAttempts += 1;
        if (!report() && reportAttempts < 6) w.setTimeout(fire, 1000);
      };
      if (w.document.readyState === "loading" && typeof w.addEventListener === "function") {
        w.addEventListener("DOMContentLoaded", function () { install(); w.setTimeout(fire, 800); }, { once: true });
      } else {
        install();
        w.setTimeout(fire, 800);
      }
    }
  } catch (error) {
    if (w.console && typeof w.console.error === "function") w.console.error("NIMS WebView shim failed", error);
  }
})(typeof window !== "undefined" ? window : null);
