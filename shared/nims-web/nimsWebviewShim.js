// Android-only compatibility and live NIMS contract adapter.
//
// Grounded against the live e-Sushrut G-5 page (27-Jun-2026):
// - top content frame: #frmMainMenu
// - CR menu: #Cr_No_Wise_Result_Report_Printing_New
// - menu endpoint: /HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt
// - outer EasyUI tab iframe: #Cr No Wise Result Report Printing New_iframe
// - nested report iframe: #Cr No Wise Result Report Printing_iframe
// - CR form: InvResultReportPrintingFB -> invResultReportPrintingCRNoWise.cnt
// - legacy race: ajaxCompleteTab() dereferences a not-yet-created iframe.contentDocument
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
    if (typeof w.date_time === "undefined") w.date_time = function () { return ""; };

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
        var list = capturedErrors.slice(0, 5);
        if (extra) list.unshift(extra);
        return bridgePost({
          type: "nims_frame_debug",
          url: safePath(doc && doc.location ? doc.location.href : w.location && w.location.href),
          children: body && body.querySelectorAll ? body.querySelectorAll("*").length : 0,
          textLen: body && body.innerText ? String(body.innerText).trim().length : 0,
          height: body ? body.scrollHeight || 0 : 0,
          errors: list.slice(0, 6)
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

    function hookGlobal(name, patcher) {
      var current;
      try { current = w[name]; } catch (e) { current = undefined; }
      try { if (current) current = patcher(current) || current; } catch (e) { /* ignore */ }
      try {
        Object.defineProperty(w, name, {
          configurable: true,
          enumerable: true,
          get: function () { return current; },
          set: function (next) {
            try { current = patcher(next) || next; } catch (e) { current = next; }
          }
        });
        return true;
      } catch (e) {
        try { if (current) w[name] = current; } catch (ignored) { /* ignore */ }
        return false;
      }
    }

    hookGlobal("jQuery", function (value) { patchOffset(value); return value; });
    hookGlobal("$", function (value) { patchOffset(value); return value; });

    if (typeof w.setInterval === "function") {
      var jqueryChecks = 0;
      var jqueryTimer = w.setInterval(function () {
        jqueryChecks += 1;
        patchOffset(w.jQuery);
        patchOffset(w.$);
        if (jqueryChecks >= 400) w.clearInterval(jqueryTimer);
      }, 50);
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
      var out = [];
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
        out.push({ doc: doc, depth: depth, frameElement: frameElement || null, visible: visible });
        var frames = [];
        try { frames = Array.prototype.slice.call(doc.querySelectorAll("iframe, frame")); } catch (e) { frames = []; }
        for (var i = 0; i < frames.length; i += 1) {
          var child = null;
          try { child = frames[i].contentDocument || null; } catch (e) { child = null; }
          if (child) visit(child, depth + 1, frames[i], visible);
        }
      }

      visit(startDoc || w.document, 0, null, true);
      return out;
    }

    function resolveTopDocument(doc) {
      var current = doc || w.document;
      try {
        var topWindow = current.defaultView && current.defaultView.top;
        if (topWindow && topWindow.document) return topWindow.document;
      } catch (e) { /* ignore */ }
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

    function isContractFrame(frame) {
      var identity = frameIdentity(frame);
      return identity.id === OUTER_REPORT_FRAME_ID || identity.id === INNER_REPORT_FRAME_ID ||
        identity.src.indexOf("viewcrnowisereportprocess.cnt") >= 0 ||
        identity.src.indexOf("invResultReportPrintingCRNoWise.cnt") >= 0;
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

    function hasGenuineReportRows(doc) {
      if (!doc || !doc.querySelectorAll) return false;
      try {
        var rows = Array.prototype.slice.call(doc.querySelectorAll("tr"));
        for (var i = 0; i < rows.length; i += 1) {
          if (!isVisibleThroughAncestors(rows[i])) continue;
          var actions = Array.prototype.slice.call(rows[i].querySelectorAll("[onclick]"));
          for (var j = 0; j < actions.length; j += 1) {
            if (!isVisibleThroughAncestors(actions[j])) continue;
            if (callArity(actions[j].getAttribute("onclick") || "", "printReport") === 1) return true;
          }
        }
      } catch (e) { /* ignore */ }
      return false;
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
        var contractForm = false;
        for (var j = 0; j < forms.length; j += 1) {
          var name = String(forms[j].name || forms[j].id || "");
          var action = safePath(forms[j].getAttribute && forms[j].getAttribute("action") || "");
          if (name === CR_FORM_NAME || /viewExternalInvFB/i.test(name) ||
              action.indexOf("viewcrnowisereportprocess.cnt") >= 0 ||
              action.indexOf("invResultReportPrintingCRNoWise.cnt") >= 0) {
            contractForm = true;
            break;
          }
        }
        if (contractForm) return true;

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
      var visibleEntries = entries.filter(function (entry) { return entry.visible !== false; });
      var contractFrames = [];
      for (var i = 0; i < entries.length; i += 1) {
        if (entries[i].frameElement && isContractFrame(entries[i].frameElement)) contractFrames.push(frameIdentity(entries[i].frameElement));
      }

      for (var r = 0; r < visibleEntries.length; r += 1) {
        if (hasGenuineReportRows(visibleEntries[r].doc)) {
          return { stage: "report_list", done: true, contractFrames: contractFrames, depth: visibleEntries[r].depth };
        }
      }
      for (var c = 0; c < visibleEntries.length; c += 1) {
        if (hasLiveCrForm(visibleEntries[c].doc)) {
          return { stage: "cr_search", done: true, contractFrames: contractFrames, depth: visibleEntries[c].depth };
        }
      }
      if (contractFrames.length) return { stage: "loading", done: false, contractFrames: contractFrames, depth: -1 };
      return { stage: "absent", done: false, contractFrames: [], depth: -1 };
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
          return {
            ok: true,
            stage: "investigation_menu",
            action: "waiting_for_report_frame",
            done: false,
            canonicalFallbackAttempted: false,
            transitionObserved: false
          };
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
        result.liveContractFrameIds = state.contractFrames.map(function (item) { return item.id; }).filter(Boolean);
        if (state.stage === "cr_search" || state.stage === "report_list") {
          result.detectedStage = state.stage;
          result.recommendedNextStep = state.stage === "cr_search" ? "Enter the CR number in NIMS." : "Discover Mapping.";
          result.crSearchFormFound = state.stage === "cr_search";
        }
        return result;
      };
      core.__nimsLiveContractPatched = true;
      return core;
    }

    hookGlobal("NimsReportCore", patchCore);
    try { if (w.NimsReportCore) patchCore(w.NimsReportCore); } catch (e) { /* ignore */ }

    function isContentDocumentRace(error) {
      var message = String(error && error.message || error || "");
      return /contentDocument/i.test(message) && /undefined|null|cannot read/i.test(message);
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
            if (attempts < 9 && typeof w.setTimeout === "function") {
              report("NAV ajaxCompleteTab deferred contentDocument attempt=" + attempts);
              w.setTimeout(invoke, Math.min(1000, 80 * attempts));
              return undefined;
            }
            report("NAV ajaxCompleteTab suppressed persistent contentDocument race");
            return undefined;
          }
        }
        return invoke();
      };
      wrapped.__nimsContentDocumentRetry = true;
      wrapped.__nimsOriginal = fn;
      return wrapped;
    }

    hookGlobal("ajaxCompleteTab", wrapAjaxCompleteTab);
    if (typeof w.setInterval === "function") {
      var raceChecks = 0;
      var raceTimer = w.setInterval(function () {
        raceChecks += 1;
        try {
          if (typeof w.ajaxCompleteTab === "function" && !w.ajaxCompleteTab.__nimsContentDocumentRetry) {
            w.ajaxCompleteTab = wrapAjaxCompleteTab(w.ajaxCompleteTab);
          }
        } catch (e) { /* ignore */ }
        if (raceChecks >= 1200) w.clearInterval(raceTimer);
      }, 25);
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

    function waitForReportContract(sequence, action) {
      var checks = 0;
      function check() {
        if (sequence !== openSequence) return;
        checks += 1;
        var state = liveContractState(w.document);
        if (state.stage === "cr_search" || state.stage === "report_list") {
          report("NAV native_cr_open action=" + action + " contract=" + state.stage + " depth=" + state.depth);
          return;
        }
        if (checks < 40 && typeof w.setTimeout === "function") {
          w.setTimeout(check, 250);
        } else {
          report("NAV native_cr_open action=" + action + " error=report_contract_not_ready frames=" + state.contractFrames.map(function (item) { return item.id; }).join("|"));
        }
      }
      check();
    }

    function openCrAfterInvestigation() {
      var sequence = ++openSequence;
      var checks = 0;
      clearStoredReport("investigation_click");

      function attempt() {
        if (sequence !== openSequence) return;
        checks += 1;
        try {
          var existing = liveContractState(w.document);
          if (existing.stage === "cr_search" || existing.stage === "report_list") {
            report("NAV native_cr_open already_ready contract=" + existing.stage);
            return;
          }

          var frame = menuFrame(w.document);
          var childDoc = frame && frame.contentDocument;
          var childWindow = frame && frame.contentWindow;
          var ready = childDoc && (childDoc.readyState === "interactive" || childDoc.readyState === "complete");
          var anchor = ready ? exactCrAnchor(childDoc) : null;

          if (anchor && clickElement(anchor)) {
            report("NAV native_cr_open action=clicked_exact_cr_anchor");
            waitForReportContract(sequence, "clicked_exact_cr_anchor");
            return;
          }

          // Fallbacks are deliberately delayed. They run only after the real
          // Investigation menu had time to expose its exact anchor.
          if (checks >= 24 && ready && childWindow && typeof childWindow.callMenu === "function") {
            childWindow.callMenu(CR_ENDPOINT, CR_MENU_ID);
            report("NAV native_cr_open action=called_child_callMenu");
            waitForReportContract(sequence, "called_child_callMenu");
            return;
          }
          if (checks >= 32 && typeof w.callMenu === "function") {
            w.callMenu(CR_ENDPOINT, CR_MENU_LABEL);
            report("NAV native_cr_open action=called_top_callMenu");
            waitForReportContract(sequence, "called_top_callMenu");
            return;
          }
        } catch (error) {
          if (isContentDocumentRace(error) && checks < 40 && typeof w.setTimeout === "function") {
            report("NAV native_cr_open deferred contentDocument attempt=" + checks);
            w.setTimeout(attempt, 250);
            return;
          }
          report("NAV native_cr_open error=" + String(error && error.message || "unknown").slice(0, 120));
          return;
        }

        if (checks < 40 && typeof w.setTimeout === "function") w.setTimeout(attempt, 250);
        else report("NAV native_cr_open error=investigation_menu_not_ready");
      }

      if (typeof w.setTimeout === "function") w.setTimeout(attempt, 250);
      else attempt();
    }

    function install() {
      var doc = w.document;
      if (!doc || doc.__nimsNativeCrOpenInstalled || typeof doc.addEventListener !== "function") return;
      doc.__nimsNativeCrOpenInstalled = true;
      doc.addEventListener("click", function (event) {
        if (!investigationTarget(event && event.target)) return;
        openCrAfterInvestigation();
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
