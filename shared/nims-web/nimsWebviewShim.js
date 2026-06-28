// Android-only compatibility and safe NIMS navigation adapter.
//
// The live 0.7.8 failure showed a blank CR tab together with
// `response.filter is not a function`. The previous shim retried the entire
// ajaxCompleteTab callback and also invoked callMenu with a guessed signature.
// Both behaviours could start a second, malformed tab transaction. This shim
// uses only visible native clicks, never calls callMenu directly, normalizes the
// documented response argument when necessary, and repairs a blank CR iframe
// only with a NIMS-generated ticketed URL captured from addTab.
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
  var ACTION_COOLDOWN_MS = 5000;
  var lastNavigationActionAt = 0;
  var capturedTicketedCrUrl = "";
  var repairGeneration = 0;

  try {
    if (typeof w.date_time !== "function") w.date_time = function () { return ""; };

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

    function report(note) {
      try {
        var doc = w.document;
        var body = doc && doc.body;
        var errors = capturedErrors.slice(0, 4);
        if (note) errors.unshift(note);
        return bridgePost({
          type: "nims_frame_debug",
          url: safePath(doc && doc.location ? doc.location.href : w.location && w.location.href),
          children: body && body.querySelectorAll ? body.querySelectorAll("*").length : 0,
          textLen: body && body.innerText ? String(body.innerText).trim().length : 0,
          height: body ? body.scrollHeight || 0 : 0,
          errors: errors.slice(0, 5)
        });
      } catch (e) {
        return false;
      }
    }

    if (typeof w.addEventListener === "function") {
      w.addEventListener("error", function (event) {
        try {
          if (capturedErrors.length >= 10) return;
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

    function patchJqueryWhenReady() {
      try { patchOffset(w.jQuery); } catch (e) { /* ignore */ }
      try { patchOffset(w.$); } catch (e) { /* ignore */ }
    }

    patchJqueryWhenReady();
    if (typeof w.setInterval === "function") {
      var jqChecks = 0;
      var jqTimer = w.setInterval(function () {
        jqChecks += 1;
        patchJqueryWhenReady();
        if (jqChecks >= 600) w.clearInterval(jqTimer);
      }, 50);
    }

    function topWindow() {
      try { return w.top || w; } catch (e) { return w; }
    }

    function topDocument(doc) {
      var current = doc || w.document;
      try {
        var top = current.defaultView && current.defaultView.top;
        if (top && top.document) return top.document;
      } catch (e) { /* cross-origin */ }
      try { return topWindow().document || current; } catch (e) { return current; }
    }

    function isVisible(element) {
      if (!element) return true;
      try {
        if (element.hidden || element.getAttribute && element.getAttribute("aria-hidden") === "true") return false;
        var owner = element.ownerDocument && element.ownerDocument.defaultView;
        var style = owner && owner.getComputedStyle ? owner.getComputedStyle(element) : null;
        return !style || (style.display !== "none" && style.visibility !== "hidden" && style.visibility !== "collapse" && Number(style.opacity) !== 0);
      } catch (e) {
        return true;
      }
    }

    function visibleThroughAncestors(element) {
      for (var current = element; current; current = current.parentElement) {
        if (!isVisible(current)) return false;
      }
      return true;
    }

    function collectDocuments(startDoc, maxDepth) {
      var output = [];
      var visited = [];
      var limit = typeof maxDepth === "number" ? maxDepth : 7;

      function seen(doc) {
        for (var i = 0; i < visited.length; i += 1) if (visited[i] === doc) return true;
        visited.push(doc);
        return false;
      }

      function visit(doc, depth, frameElement, parentVisible) {
        if (!doc || depth > limit || seen(doc)) return;
        var entryVisible = parentVisible !== false && isVisible(frameElement);
        output.push({ doc: doc, depth: depth, frameElement: frameElement || null, visible: entryVisible });
        var frames = [];
        try { frames = Array.prototype.slice.call(doc.querySelectorAll("iframe, frame")); } catch (e) { frames = []; }
        for (var i = 0; i < frames.length; i += 1) {
          var child = null;
          try { child = frames[i].contentDocument || null; } catch (e) { child = null; }
          if (child) visit(child, depth + 1, frames[i], entryVisible);
        }
      }

      visit(startDoc || w.document, 0, null, true);
      return output;
    }

    function exactFrame(doc, id) {
      try {
        return (doc && doc.getElementById && doc.getElementById(id)) ||
          (doc && doc.querySelector && doc.querySelector('iframe[name="' + id + '"],frame[name="' + id + '"]')) || null;
      } catch (e) {
        return null;
      }
    }

    function elementText(element) {
      return String(element && (element.innerText || element.textContent || element.value) || "").replace(/\s+/g, " ").trim();
    }

    function hasCrTabHeader(doc) {
      try {
        var nodes = Array.prototype.slice.call(doc.querySelectorAll("a,li,div,span,button"));
        return nodes.some(function (node) {
          return visibleThroughAncestors(node) && elementText(node) === CR_MENU_LABEL;
        });
      } catch (e) {
        return false;
      }
    }

    function functionArity(code, expectedName) {
      var match = String(code || "").match(/^\s*([A-Za-z_$][\w$]*)\s*\(([^()]*)\)\s*;?\s*$/);
      if (!match || match[1] !== expectedName) return -1;
      var args = match[2].trim();
      return args ? args.split(",").length : 0;
    }

    function genuineRows(doc) {
      if (!doc || !doc.querySelectorAll) return [];
      var matches = [];
      try {
        var rows = Array.prototype.slice.call(doc.querySelectorAll("tr"));
        for (var i = 0; i < rows.length; i += 1) {
          if (!visibleThroughAncestors(rows[i])) continue;
          var controls = Array.prototype.slice.call(rows[i].querySelectorAll("[onclick]"));
          if (controls.some(function (node) {
            return visibleThroughAncestors(node) && functionArity(node.getAttribute("onclick") || "", "printReport") === 1;
          })) matches.push(rows[i]);
        }
      } catch (e) { /* ignore */ }
      return matches;
    }

    function hasCrForm(doc) {
      if (!doc || !doc.querySelectorAll) return false;
      try {
        var inputs = Array.prototype.slice.call(doc.querySelectorAll("input,textarea,select"));
        var crInput = inputs.find(function (input) {
          var key = String(input.id || "") + " " + String(input.name || "");
          return /patcrno|cr\s*(no|number)|crno|crnumber/i.test(key) &&
            String(input.type || "").toLowerCase() !== "hidden" && visibleThroughAncestors(input);
        });
        if (!crInput) return false;
        var forms = Array.prototype.slice.call(doc.querySelectorAll("form"));
        return forms.some(function (form) {
          var name = String(form.name || form.id || "");
          var action = safePath(form.getAttribute && form.getAttribute("action") || "");
          return name === CR_FORM_NAME || action.indexOf("viewcrnowisereportprocess.cnt") >= 0 || action.indexOf("invResultReportPrintingCRNoWise.cnt") >= 0;
        });
      } catch (e) {
        return false;
      }
    }

    function contractDocument(entry) {
      if (!entry) return false;
      try {
        var path = safePath(entry.doc && entry.doc.location ? entry.doc.location.href : "");
        if (path.indexOf("viewcrnowisereportprocess.cnt") >= 0 || path.indexOf("invResultReportPrintingCRNoWise.cnt") >= 0) return true;
        var frame = entry.frameElement;
        var id = frame ? String(frame.id || frame.name || "") : "";
        return id === OUTER_REPORT_FRAME_ID || id === INNER_REPORT_FRAME_ID;
      } catch (e) {
        return false;
      }
    }

    function liveContractState(doc) {
      var top = topDocument(doc || w.document);
      var entries = collectDocuments(top, 7).filter(function (entry) { return entry.visible !== false; });
      for (var i = 0; i < entries.length; i += 1) {
        if (!contractDocument(entries[i])) continue;
        var rows = genuineRows(entries[i].doc);
        if (rows.length) return { stage: "report_list", done: true, depth: entries[i].depth, rowCount: rows.length };
      }
      for (var j = 0; j < entries.length; j += 1) {
        if (contractDocument(entries[j]) && hasCrForm(entries[j].doc)) {
          return { stage: "cr_search", done: true, depth: entries[j].depth, rowCount: 0 };
        }
      }
      if (exactFrame(top, OUTER_REPORT_FRAME_ID) || hasCrTabHeader(top)) {
        return { stage: "loading", done: false, depth: -1, rowCount: 0 };
      }
      return { stage: "absent", done: false, depth: -1, rowCount: 0 };
    }

    function readableLoginForm(doc) {
      var entries = collectDocuments(topDocument(doc || w.document), 5);
      return entries.some(function (entry) {
        try {
          var password = entry.doc.querySelector('input[type="password"]');
          var user = entry.doc.querySelector('input[type="text"],input[name*="user" i],input[id*="user" i],input[name*="login" i],input[id*="login" i]');
          return Boolean(password && user);
        } catch (e) {
          return false;
        }
      });
    }

    function exactCrAnchor(doc) {
      var entries = collectDocuments(topDocument(doc || w.document), 6).sort(function (a, b) { return a.depth - b.depth; });
      for (var i = 0; i < entries.length; i += 1) {
        if (entries[i].visible === false) continue;
        try {
          var exact = entries[i].doc.getElementById && entries[i].doc.getElementById(CR_MENU_ID);
          if (exact && visibleThroughAncestors(exact)) return exact;
          var nodes = Array.prototype.slice.call(entries[i].doc.querySelectorAll("[onclick],a,button"));
          var found = nodes.find(function (node) {
            if (!visibleThroughAncestors(node)) return false;
            var onclick = node.getAttribute ? node.getAttribute("onclick") || "" : "";
            return onclick.indexOf(CR_ENDPOINT) >= 0 || onclick.indexOf(CR_MENU_ID) >= 0 || elementText(node) === CR_MENU_LABEL;
          });
          if (found) return found;
        } catch (e) { /* ignore */ }
      }
      return null;
    }

    function investigationTarget(doc) {
      var entries = collectDocuments(topDocument(doc || w.document), 4).sort(function (a, b) { return a.depth - b.depth; });
      for (var i = 0; i < entries.length; i += 1) {
        if (entries[i].visible === false) continue;
        try {
          var nodes = Array.prototype.slice.call(entries[i].doc.querySelectorAll("[onclick],a,button,[role='button']"));
          var found = nodes.find(function (node) {
            if (!visibleThroughAncestors(node)) return false;
            var onclick = node.getAttribute ? node.getAttribute("onclick") || "" : "";
            return /menuSelected\s*\(\s*['"]Investigation['"]\s*,\s*true\s*\)/i.test(onclick) || elementText(node) === "Investigation";
          });
          if (found) return found;
        } catch (e) { /* ignore */ }
      }
      return null;
    }

    function clickOnce(element, action, stage) {
      var now = Date.now ? Date.now() : new Date().getTime();
      if (now - lastNavigationActionAt < ACTION_COOLDOWN_MS) {
        return { ok: true, stage: stage, action: "cooldown", done: false, errorCode: "" };
      }
      try {
        if (element && typeof element.click === "function") {
          lastNavigationActionAt = now;
          element.click();
          return { ok: true, stage: stage, action: action, done: false, errorCode: "" };
        }
      } catch (e) { /* ignore */ }
      return { ok: false, stage: stage, action: "none", done: false, errorCode: "native_click_failed" };
    }

    function navigateToCrWiseReports(doc) {
      var state = liveContractState(doc || w.document);
      if (state.stage === "cr_search" || state.stage === "report_list") {
        return { ok: true, stage: state.stage, action: "none", done: true, errorCode: "", frameDepth: state.depth };
      }
      if (state.stage === "loading") {
        repairCrTab("navigation_wait");
        return { ok: true, stage: "investigation_menu", action: "waiting_for_report_frame", done: false, errorCode: "" };
      }
      if (readableLoginForm(doc || w.document)) {
        return { ok: false, stage: "login", action: "none", done: false, errorCode: "manual_login_required" };
      }
      var anchor = exactCrAnchor(doc || w.document);
      if (anchor) return clickOnce(anchor, "clicked_cr_wise_menu", "investigation_menu");
      var investigation = investigationTarget(doc || w.document);
      if (investigation) return clickOnce(investigation, "selected_investigation", "home");
      return { ok: true, stage: "home", action: "waiting_for_shell", done: false, errorCode: "" };
    }

    function diagnosePage(doc) {
      var state = liveContractState(doc || w.document);
      return {
        activeUrl: safePath(w.location && w.location.href),
        detectedStage: state.stage === "loading" ? "investigation_menu" : state.stage,
        liveContractStage: state.stage,
        viewReportRows: state.stage === "report_list" ? state.rowCount : 0,
        printReportRows: state.stage === "report_list" ? state.rowCount : 0,
        crSearchFormFound: state.stage === "cr_search",
        recommendedNextStep: state.stage === "cr_search" ? "Enter the CR number in NIMS." :
          state.stage === "report_list" ? "Analyze Current Results." :
          state.stage === "loading" ? "Wait for the CR page to finish loading." : "Open CR-wise reports."
      };
    }

    w.NimsAndroidNavigation = {
      navigateToCrWiseReports: navigateToCrWiseReports,
      navigateCurrentDocumentStep: navigateToCrWiseReports,
      diagnosePage: diagnosePage,
      liveContractState: liveContractState
    };

    function isContentDocumentRace(error) {
      var message = String(error && error.message || error || "");
      return /contentDocument/i.test(message) && /undefined|null|cannot read|not an object/i.test(message);
    }

    function parameterNames(fn) {
      var source = String(fn || "");
      var match = source.match(/^[^(]*\(([^)]*)\)/);
      return match ? match[1].split(",").map(function (name) { return name.trim(); }) : [];
    }

    function normalizeResponseArgs(fn, args) {
      var copy = Array.prototype.slice.call(args || []);
      var names = parameterNames(fn);
      var index = names.findIndex(function (name) { return /^response$/i.test(name); });
      if (index < 0) return { args: copy, changed: false };
      var value = copy[index];
      if (value && typeof value.filter === "function") return { args: copy, changed: false };
      var jq = null;
      try { jq = w.jQuery || w.$; } catch (e) { jq = null; }
      if (typeof jq !== "function") return { args: copy, changed: false };
      try {
        if (value && typeof value.responseText === "string") copy[index] = jq(value.responseText);
        else if (typeof value === "string") copy[index] = jq(value);
        else if (value && value.nodeType) copy[index] = jq(value);
        else return { args: copy, changed: false };
        return { args: copy, changed: true };
      } catch (e) {
        return { args: copy, changed: false };
      }
    }

    function wrapAjaxCompleteTab(fn) {
      if (typeof fn !== "function" || fn.__nimsSafeAjaxCompleteTab) return fn;
      var wrapped = function () {
        var receiver = this;
        var normalized = normalizeResponseArgs(fn, arguments);
        var callArgs = normalized.args;
        try {
          return fn.apply(receiver, callArgs);
        } catch (error) {
          var message = String(error && error.message || error || "");
          if (/response\.filter\s+is\s+not\s+a\s+function/i.test(message) && !normalized.changed) {
            var retryArgs = normalizeResponseArgs(fn, arguments);
            if (retryArgs.changed) return fn.apply(receiver, retryArgs.args);
          }
          if (isContentDocumentRace(error) && typeof w.setTimeout === "function") {
            report("NAV ajaxCompleteTab deferred once");
            w.setTimeout(function () {
              try { fn.apply(receiver, callArgs); }
              catch (retryError) {
                report("NAV ajaxCompleteTab retry stopped: " + String(retryError && retryError.message || "error").slice(0, 90));
                repairCrTab("ajaxCompleteTab_retry_failed");
              }
            }, 500);
            return undefined;
          }
          throw error;
        }
      };
      wrapped.__nimsSafeAjaxCompleteTab = true;
      wrapped.__nimsOriginal = fn;
      return wrapped;
    }

    function ticketedCrUrl(value) {
      try {
        var parsed = new URL(String(value || ""), w.location && w.location.href || "https://www.nimsts.edu.in/");
        var allowed = parsed.hostname === "nimsts.edu.in" || parsed.hostname === "www.nimsts.edu.in";
        var correct = parsed.pathname === CR_ENDPOINT;
        return allowed && correct && parsed.search.length > 1 ? parsed.href : "";
      } catch (e) {
        return "";
      }
    }

    function inspectTicket(value, depth) {
      if (depth > 3 || value == null) return "";
      if (typeof value === "string") return ticketedCrUrl(value);
      if (Array.isArray(value) || typeof value.length === "number") {
        for (var i = 0; i < value.length; i += 1) {
          var fromList = inspectTicket(value[i], depth + 1);
          if (fromList) return fromList;
        }
      }
      if (typeof value === "object") {
        var keys = ["url", "href", "src", "path", "menuUrl", "tabUrl"];
        for (var j = 0; j < keys.length; j += 1) {
          var fromObject = inspectTicket(value[keys[j]], depth + 1);
          if (fromObject) return fromObject;
        }
      }
      return "";
    }

    function captureTicketFromDocument() {
      var doc = topDocument(w.document);
      try {
        var frames = Array.prototype.slice.call(doc.querySelectorAll("iframe[src],frame[src]"));
        for (var i = 0; i < frames.length; i += 1) {
          var found = ticketedCrUrl(frames[i].getAttribute("src") || frames[i].src || "");
          if (found) {
            capturedTicketedCrUrl = found;
            return found;
          }
        }
      } catch (e) { /* ignore */ }
      return "";
    }

    function repairCrTab(reason) {
      var generation = ++repairGeneration;
      var delays = [200, 700, 1500, 3000];
      delays.forEach(function (delay, index) {
        if (typeof w.setTimeout !== "function") return;
        w.setTimeout(function () {
          if (generation !== repairGeneration) return;
          captureTicketFromDocument();
          var top = topDocument(w.document);
          var frame = exactFrame(top, OUTER_REPORT_FRAME_ID);
          var state = liveContractState(top);
          if (state.stage === "cr_search" || state.stage === "report_list") {
            repairGeneration += 1;
            return;
          }
          if (frame && capturedTicketedCrUrl) {
            try {
              var current = frame.getAttribute ? frame.getAttribute("src") || "" : "";
              if (!current || current === "about:blank") {
                frame.setAttribute("src", capturedTicketedCrUrl);
                report("TAB repair applied ticketed iframe URL");
              }
            } catch (e) { /* ignore */ }
          }
          if (index === delays.length - 1 && capturedTicketedCrUrl && hasCrTabHeader(top) && !frame) {
            try {
              var topWin = top.defaultView || topWindow();
              if (topWin.location && typeof topWin.location.assign === "function") {
                report("TAB repair opened ticketed CR page directly");
                repairGeneration += 1;
                topWin.location.assign(capturedTicketedCrUrl);
              }
            } catch (e) {
              report("TAB repair unavailable: " + reason);
            }
          }
        }, delay);
      });
    }

    function wrapTabCreator(name, fn) {
      if (typeof fn !== "function" || fn.__nimsTicketCapture) return fn;
      var wrapped = function () {
        var found = inspectTicket(arguments, 0);
        if (found) capturedTicketedCrUrl = found;
        try { return fn.apply(this, arguments); }
        finally { if (found) repairCrTab("after_" + name); }
      };
      wrapped.__nimsTicketCapture = true;
      wrapped.__nimsOriginal = fn;
      return wrapped;
    }

    function patchAvailableFunctions() {
      try {
        if (typeof w.ajaxCompleteTab === "function" && !w.ajaxCompleteTab.__nimsSafeAjaxCompleteTab) {
          w.ajaxCompleteTab = wrapAjaxCompleteTab(w.ajaxCompleteTab);
        }
      } catch (e) { /* ignore */ }
      ["addTab", "addNewTab", "addTabNew", "createTab"].forEach(function (name) {
        try {
          if (typeof w[name] === "function" && !w[name].__nimsTicketCapture) w[name] = wrapTabCreator(name, w[name]);
        } catch (e) { /* ignore */ }
      });
    }

    patchAvailableFunctions();
    if (typeof w.setInterval === "function") {
      var functionChecks = 0;
      var functionTimer = w.setInterval(function () {
        functionChecks += 1;
        patchAvailableFunctions();
        if (functionChecks >= 1200) w.clearInterval(functionTimer);
      }, 50);
    }

    function isTopWindow() {
      try { return w.top === w; } catch (e) { return false; }
    }

    function clearStoredReport(reason) {
      if (!isTopWindow()) return;
      bridgePost({ type: "nims_report_frame", href: safePath(w.location && w.location.href), rowCount: 0, rows: [], clearReason: reason || "navigation" });
    }

    function isInvestigationClick(node) {
      for (var element = node, depth = 0; element && depth < 6; element = element.parentElement, depth += 1) {
        var onclick = element.getAttribute ? element.getAttribute("onclick") || "" : "";
        if (/menuSelected\s*\(\s*['"]Investigation['"]\s*,\s*true\s*\)/i.test(onclick) || elementText(element) === "Investigation") return true;
      }
      return false;
    }

    function isCrSubmit(node) {
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

    function installListeners() {
      var doc = w.document;
      if (!doc || doc.__nimsSafeNavigationInstalled || typeof doc.addEventListener !== "function") return;
      doc.__nimsSafeNavigationInstalled = true;
      doc.addEventListener("click", function (event) {
        if (isCrSubmit(event && event.target)) clearStoredReport("cr_submit");
        if (isTopWindow() && isInvestigationClick(event && event.target)) clearStoredReport("investigation_click");
      }, true);
      doc.addEventListener("submit", function (event) {
        if (isCrSubmit(event && event.target)) clearStoredReport("cr_submit");
      }, true);
    }

    if (w.document && typeof w.setTimeout === "function") {
      var reportAttempts = 0;
      function initialReport() {
        reportAttempts += 1;
        if (!report() && reportAttempts < 5) w.setTimeout(initialReport, 1000);
      }
      if (w.document.readyState === "loading" && typeof w.addEventListener === "function") {
        w.addEventListener("DOMContentLoaded", function () { installListeners(); w.setTimeout(initialReport, 700); }, { once: true });
      } else {
        installListeners();
        w.setTimeout(initialReport, 700);
      }
    }
  } catch (error) {
    if (w.console && typeof w.console.error === "function") w.console.error("NIMS WebView shim failed", error);
  }
})(typeof window !== "undefined" ? window : null);
