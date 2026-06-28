// Android-only compatibility and safe NIMS navigation adapter.
(function (w) {
  if (!w) return;

  var CR_ENDPOINT = "/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt";
  var CR_RESULT_ENDPOINT = "/HISInvestigationG5/new_investigation/invResultReportPrintingCRNoWise.cnt";
  var CR_MENU_ID = "Cr_No_Wise_Result_Report_Printing_New";
  var CR_LABEL = "Cr No Wise Result Report Printing New";
  var OUTER_FRAME_ID = CR_LABEL + "_iframe";
  var INNER_FRAME_ID = "Cr No Wise Result Report Printing_iframe";
  var CR_FORM_NAME = "InvResultReportPrintingFB";
  var lastClickAt = 0;
  var ticketedCrUrl = "";
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

    function post(payload) {
      try {
        var bridge = w.nimsAndroidBridge;
        if (!bridge || typeof bridge.postMessage !== "function") return false;
        bridge.postMessage(JSON.stringify(payload));
        return true;
      } catch (e) {
        return false;
      }
    }

    var errors = [];
    w.__nimsShimErrors = errors;

    function report(note) {
      var doc = w.document;
      var body = doc && doc.body;
      var list = errors.slice(0, 4);
      if (note) list.unshift(note);
      return post({
        type: "nims_frame_debug",
        url: safePath(doc && doc.location ? doc.location.href : w.location && w.location.href),
        children: body && body.querySelectorAll ? body.querySelectorAll("*").length : 0,
        textLen: body && body.innerText ? String(body.innerText).trim().length : 0,
        height: body ? body.scrollHeight || 0 : 0,
        errors: list.slice(0, 5)
      });
    }

    if (typeof w.addEventListener === "function") {
      w.addEventListener("error", function (event) {
        if (errors.length >= 10) return;
        errors.push(
          String(event && event.message || "error") + " @" +
          String(event && event.filename || "").split("/").pop() + ":" +
          String(event && event.lineno || "?")
        );
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

    function topWindow() {
      try { return w.top || w; } catch (e) { return w; }
    }

    function topDocument(doc) {
      var current = doc || w.document;
      try {
        var top = current.defaultView && current.defaultView.top;
        if (top && top.document) return top.document;
      } catch (e) { /* ignore */ }
      try { return topWindow().document || current; } catch (e) { return current; }
    }

    function visible(element) {
      if (!element) return true;
      try {
        if (element.hidden || element.getAttribute && element.getAttribute("aria-hidden") === "true") return false;
        var owner = element.ownerDocument && element.ownerDocument.defaultView;
        var style = owner && owner.getComputedStyle ? owner.getComputedStyle(element) : null;
        return !style || (style.display !== "none" && style.visibility !== "hidden" && Number(style.opacity) !== 0);
      } catch (e) {
        return true;
      }
    }

    function visibleTree(element) {
      for (var current = element; current; current = current.parentElement) if (!visible(current)) return false;
      return true;
    }

    function documents(startDoc, maxDepth) {
      var output = [];
      var seen = [];
      var limit = typeof maxDepth === "number" ? maxDepth : 7;
      function alreadySeen(doc) {
        for (var i = 0; i < seen.length; i += 1) if (seen[i] === doc) return true;
        seen.push(doc);
        return false;
      }
      function visit(doc, depth, frame, parentVisible) {
        if (!doc || depth > limit || alreadySeen(doc)) return;
        var itemVisible = parentVisible !== false && visible(frame);
        output.push({ doc: doc, depth: depth, frame: frame || null, visible: itemVisible });
        var frames = [];
        try { frames = Array.prototype.slice.call(doc.querySelectorAll("iframe, frame")); } catch (e) { frames = []; }
        for (var i = 0; i < frames.length; i += 1) {
          var child = null;
          try { child = frames[i].contentDocument || null; } catch (e) { child = null; }
          if (child) visit(child, depth + 1, frames[i], itemVisible);
        }
      }
      visit(startDoc || w.document, 0, null, true);
      return output;
    }

    function exactFrame(doc, id) {
      try {
        return (doc.getElementById && doc.getElementById(id)) ||
          (doc.querySelector && doc.querySelector('iframe[name="' + id + '"],frame[name="' + id + '"]')) || null;
      } catch (e) {
        return null;
      }
    }

    function text(element) {
      return String(element && (element.innerText || element.textContent || element.value) || "").replace(/\s+/g, " ").trim();
    }

    function hasTabHeader(doc) {
      try {
        return Array.prototype.slice.call(doc.querySelectorAll("a,li,div,span,button")).some(function (node) {
          return visibleTree(node) && text(node) === CR_LABEL;
        });
      } catch (e) {
        return false;
      }
    }

    function oneArgPrintReport(code) {
      var match = String(code || "").match(/^\s*printReport\s*\(([^()]*)\)\s*;?\s*$/);
      return Boolean(match && match[1].trim() && match[1].indexOf(",") < 0);
    }

    function reportRows(doc) {
      if (!doc || !doc.querySelectorAll) return [];
      var found = [];
      try {
        Array.prototype.slice.call(doc.querySelectorAll("tr")).forEach(function (row) {
          if (!visibleTree(row)) return;
          var controls = Array.prototype.slice.call(row.querySelectorAll("[onclick]"));
          if (controls.some(function (node) { return visibleTree(node) && oneArgPrintReport(node.getAttribute("onclick") || ""); })) found.push(row);
        });
      } catch (e) { /* ignore */ }
      return found;
    }

    function crForm(doc) {
      if (!doc || !doc.querySelectorAll) return false;
      try {
        var inputs = Array.prototype.slice.call(doc.querySelectorAll("input,textarea,select"));
        var input = inputs.find(function (node) {
          var key = String(node.id || "") + " " + String(node.name || "");
          return /patcrno|cr\s*(no|number)|crno|crnumber/i.test(key) && String(node.type || "").toLowerCase() !== "hidden" && visibleTree(node);
        });
        if (!input) return false;
        return Array.prototype.slice.call(doc.querySelectorAll("form")).some(function (form) {
          var name = String(form.name || form.id || "");
          var action = safePath(form.getAttribute && form.getAttribute("action") || "");
          return name === CR_FORM_NAME || action.indexOf("viewcrnowisereportprocess.cnt") >= 0 || action.indexOf("invResultReportPrintingCRNoWise.cnt") >= 0;
        });
      } catch (e) {
        return false;
      }
    }

    function contract(entry) {
      try {
        var path = safePath(entry.doc.location && entry.doc.location.href);
        if (path.indexOf("viewcrnowisereportprocess.cnt") >= 0 || path.indexOf("invResultReportPrintingCRNoWise.cnt") >= 0) return true;
        var frameName = entry.frame ? String(entry.frame.id || entry.frame.name || "") : "";
        return frameName === OUTER_FRAME_ID || frameName === INNER_FRAME_ID;
      } catch (e) {
        return false;
      }
    }

    function liveState(doc) {
      var top = topDocument(doc || w.document);
      var entries = documents(top, 7).filter(function (item) { return item.visible !== false; });
      for (var i = 0; i < entries.length; i += 1) {
        if (!contract(entries[i])) continue;
        var rows = reportRows(entries[i].doc);
        if (rows.length) return { stage: "report_list", done: true, depth: entries[i].depth, rowCount: rows.length };
      }
      for (var j = 0; j < entries.length; j += 1) {
        if (contract(entries[j]) && crForm(entries[j].doc)) return { stage: "cr_search", done: true, depth: entries[j].depth, rowCount: 0 };
      }
      if (exactFrame(top, OUTER_FRAME_ID) || hasTabHeader(top)) return { stage: "loading", done: false, depth: -1, rowCount: 0 };
      return { stage: "absent", done: false, depth: -1, rowCount: 0 };
    }

    function loginForm(doc) {
      return documents(topDocument(doc || w.document), 5).some(function (entry) {
        try {
          return Boolean(entry.doc.querySelector('input[type="password"]') && entry.doc.querySelector('input[type="text"],input[name*="user" i],input[id*="user" i],input[name*="login" i],input[id*="login" i]'));
        } catch (e) {
          return false;
        }
      });
    }

    function findCrAnchor(doc) {
      var entries = documents(topDocument(doc || w.document), 6).sort(function (a, b) { return a.depth - b.depth; });
      for (var i = 0; i < entries.length; i += 1) {
        if (entries[i].visible === false) continue;
        try {
          var exact = entries[i].doc.getElementById && entries[i].doc.getElementById(CR_MENU_ID);
          if (exact && visibleTree(exact)) return exact;
          var nodes = Array.prototype.slice.call(entries[i].doc.querySelectorAll("[onclick],a,button"));
          var found = nodes.find(function (node) {
            var onclick = node.getAttribute ? node.getAttribute("onclick") || "" : "";
            return visibleTree(node) && (onclick.indexOf(CR_ENDPOINT) >= 0 || onclick.indexOf(CR_MENU_ID) >= 0 || text(node) === CR_LABEL);
          });
          if (found) return found;
        } catch (e) { /* ignore */ }
      }
      return null;
    }

    function findInvestigation(doc) {
      var entries = documents(topDocument(doc || w.document), 4).sort(function (a, b) { return a.depth - b.depth; });
      for (var i = 0; i < entries.length; i += 1) {
        if (entries[i].visible === false) continue;
        try {
          var nodes = Array.prototype.slice.call(entries[i].doc.querySelectorAll("[onclick],a,button,[role='button']"));
          var found = nodes.find(function (node) {
            var onclick = node.getAttribute ? node.getAttribute("onclick") || "" : "";
            return visibleTree(node) && (/menuSelected\s*\(\s*['"]Investigation['"]\s*,\s*true\s*\)/i.test(onclick) || text(node) === "Investigation");
          });
          if (found) return found;
        } catch (e) { /* ignore */ }
      }
      return null;
    }

    function clickResult(element, action, stage) {
      var now = Date.now ? Date.now() : new Date().getTime();
      if (now - lastClickAt < 5000) return { ok: true, stage: stage, action: "cooldown", done: false, errorCode: "" };
      try {
        if (element && typeof element.click === "function") {
          lastClickAt = now;
          element.click();
          return { ok: true, stage: stage, action: action, done: false, errorCode: "" };
        }
      } catch (e) { /* ignore */ }
      return { ok: false, stage: stage, action: "none", done: false, errorCode: "native_click_failed" };
    }

    function navigate(doc) {
      var state = liveState(doc || w.document);
      if (state.stage === "cr_search" || state.stage === "report_list") return { ok: true, stage: state.stage, action: "none", done: true, errorCode: "", frameDepth: state.depth };
      if (state.stage === "loading") {
        repair("waiting");
        return { ok: true, stage: "investigation_menu", action: "waiting_for_report_frame", done: false, errorCode: "" };
      }
      if (loginForm(doc || w.document)) return { ok: false, stage: "login", action: "none", done: false, errorCode: "manual_login_required" };
      var anchor = findCrAnchor(doc || w.document);
      if (anchor) return clickResult(anchor, "clicked_cr_wise_menu", "investigation_menu");
      var investigation = findInvestigation(doc || w.document);
      if (investigation) return clickResult(investigation, "selected_investigation", "home");
      return { ok: true, stage: "home", action: "waiting_for_shell", done: false, errorCode: "" };
    }

    function diagnose(doc) {
      var state = liveState(doc || w.document);
      return {
        activeUrl: safePath(w.location && w.location.href),
        detectedStage: state.stage === "loading" ? "investigation_menu" : state.stage,
        liveContractStage: state.stage,
        viewReportRows: state.stage === "report_list" ? state.rowCount : 0,
        printReportRows: state.stage === "report_list" ? state.rowCount : 0,
        crSearchFormFound: state.stage === "cr_search",
        recommendedNextStep: state.stage === "cr_search" ? "Enter the CR number in NIMS." : state.stage === "report_list" ? "Analyze Current Results." : state.stage === "loading" ? "Wait for the CR page to finish loading." : "Open CR-wise reports."
      };
    }

    w.NimsAndroidNavigation = {
      navigateToCrWiseReports: navigate,
      navigateCurrentDocumentStep: navigate,
      diagnosePage: diagnose,
      liveContractState: liveState
    };

    function patchCore(core) {
      if (!core || typeof core !== "object") return core;
      core.navigateToCrWiseReports = navigate;
      core.navigateCurrentDocumentStep = navigate;
      core.diagnosePage = diagnose;
      core.__nimsAndroidSafeNavigation = true;
      return core;
    }

    function installCoreHook() {
      var current;
      try { current = patchCore(w.NimsReportCore); } catch (e) { current = w.NimsReportCore; }
      try {
        var descriptor = Object.getOwnPropertyDescriptor(w, "NimsReportCore");
        if (descriptor && descriptor.configurable === false) {
          w.NimsReportCore = patchCore(w.NimsReportCore);
          return;
        }
        Object.defineProperty(w, "NimsReportCore", {
          configurable: true,
          enumerable: true,
          get: function () { return current; },
          set: function (next) { current = patchCore(next); }
        });
        if (current) w.NimsReportCore = current;
      } catch (e) {
        try { w.NimsReportCore = patchCore(w.NimsReportCore); } catch (ignored) { /* ignore */ }
      }
    }

    function parameterNames(fn) {
      var match = String(fn || "").match(/^[^(]*\(([^)]*)\)/);
      return match ? match[1].split(",").map(function (name) { return name.trim(); }) : [];
    }

    function normalizedArgs(fn, args) {
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

    function frameRace(error) {
      var message = String(error && error.message || error || "");
      return /contentDocument/i.test(message) && /undefined|null|cannot read|not an object/i.test(message);
    }

    function wrapAjax(fn) {
      if (typeof fn !== "function" || fn.__nimsSafeAjaxCompleteTab) return fn;
      var wrapped = function () {
        var receiver = this;
        var normalized = normalizedArgs(fn, arguments);
        try {
          return fn.apply(receiver, normalized.args);
        } catch (error) {
          if (frameRace(error) && typeof w.setTimeout === "function") {
            report("NAV ajaxCompleteTab deferred once");
            w.setTimeout(function () {
              try { fn.apply(receiver, normalized.args); }
              catch (retryError) {
                report("NAV ajaxCompleteTab retry stopped: " + String(retryError && retryError.message || "error").slice(0, 90));
                repair("ajax_retry_failed");
              }
            }, 500);
            return undefined;
          }
          throw error;
        }
      };
      wrapped.__nimsSafeAjaxCompleteTab = true;
      return wrapped;
    }

    function ticket(value) {
      try {
        var url = new URL(String(value || ""), w.location && w.location.href || "https://www.nimsts.edu.in/");
        var allowed = url.hostname === "nimsts.edu.in" || url.hostname === "www.nimsts.edu.in";
        return allowed && url.pathname === CR_ENDPOINT && url.search.length > 1 ? url.href : "";
      } catch (e) {
        return "";
      }
    }

    function inspect(value, depth) {
      if (depth > 3 || value == null) return "";
      if (typeof value === "string") return ticket(value);
      if (Array.isArray(value) || typeof value.length === "number") {
        for (var i = 0; i < value.length; i += 1) {
          var listValue = inspect(value[i], depth + 1);
          if (listValue) return listValue;
        }
      }
      if (typeof value === "object") {
        var keys = ["url", "href", "src", "path", "menuUrl", "tabUrl"];
        for (var j = 0; j < keys.length; j += 1) {
          var objectValue = inspect(value[keys[j]], depth + 1);
          if (objectValue) return objectValue;
        }
      }
      return "";
    }

    function wrapTabCreator(name, fn) {
      if (typeof fn !== "function" || fn.__nimsTicketCapture) return fn;
      var wrapped = function () {
        var found = inspect(arguments, 0);
        if (found) ticketedCrUrl = found;
        try { return fn.apply(this, arguments); }
        finally { if (found) repair("after_" + name); }
      };
      wrapped.__nimsTicketCapture = true;
      return wrapped;
    }

    function captureFrameTicket() {
      var doc = topDocument(w.document);
      try {
        var frames = Array.prototype.slice.call(doc.querySelectorAll("iframe[src],frame[src]"));
        for (var i = 0; i < frames.length; i += 1) {
          var found = ticket(frames[i].getAttribute("src") || frames[i].src || "");
          if (found) {
            ticketedCrUrl = found;
            return;
          }
        }
      } catch (e) { /* ignore */ }
    }

    function repair(reason) {
      var generation = ++repairGeneration;
      [200, 700, 1500, 3000].forEach(function (delay, index, list) {
        if (typeof w.setTimeout !== "function") return;
        w.setTimeout(function () {
          if (generation !== repairGeneration) return;
          captureFrameTicket();
          var top = topDocument(w.document);
          var frame = exactFrame(top, OUTER_FRAME_ID);
          var state = liveState(top);
          if (state.stage === "cr_search" || state.stage === "report_list") {
            repairGeneration += 1;
            return;
          }
          if (frame && ticketedCrUrl) {
            try {
              var current = frame.getAttribute ? frame.getAttribute("src") || "" : "";
              if (!current || current === "about:blank") {
                frame.setAttribute("src", ticketedCrUrl);
                report("TAB repair applied ticketed iframe URL");
              }
            } catch (e) { /* ignore */ }
          }
          if (index === list.length - 1 && ticketedCrUrl && hasTabHeader(top) && !frame) {
            try {
              var topWin = top.defaultView || topWindow();
              if (topWin.location && typeof topWin.location.assign === "function") {
                report("TAB repair opened ticketed CR page directly");
                repairGeneration += 1;
                topWin.location.assign(ticketedCrUrl);
              }
            } catch (e) {
              report("TAB repair unavailable: " + reason);
            }
          }
        }, delay);
      });
    }

    function patchFunctions() {
      try { patchOffset(w.jQuery); } catch (e) { /* ignore */ }
      try { patchOffset(w.$); } catch (e) { /* ignore */ }
      try {
        if (typeof w.ajaxCompleteTab === "function" && !w.ajaxCompleteTab.__nimsSafeAjaxCompleteTab) w.ajaxCompleteTab = wrapAjax(w.ajaxCompleteTab);
      } catch (e) { /* ignore */ }
      ["addTab", "addNewTab", "addTabNew", "createTab"].forEach(function (name) {
        try {
          if (typeof w[name] === "function" && !w[name].__nimsTicketCapture) w[name] = wrapTabCreator(name, w[name]);
        } catch (e) { /* ignore */ }
      });
    }

    installCoreHook();
    patchFunctions();
    if (typeof w.setInterval === "function") {
      var checks = 0;
      var timer = w.setInterval(function () {
        checks += 1;
        patchFunctions();
        if (checks >= 1200) w.clearInterval(timer);
      }, 50);
    }

    function isTop() {
      try { return w.top === w; } catch (e) { return false; }
    }

    function clearRows(reason) {
      if (!isTop()) return;
      post({ type: "nims_report_frame", href: safePath(w.location && w.location.href), rowCount: 0, rows: [], clearReason: reason });
    }

    function crSubmit(node) {
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
        if (crSubmit(event && event.target)) clearRows("cr_submit");
      }, true);
      doc.addEventListener("submit", function (event) {
        if (crSubmit(event && event.target)) clearRows("cr_submit");
      }, true);
    }

    if (w.document && typeof w.setTimeout === "function") {
      if (w.document.readyState === "loading" && typeof w.addEventListener === "function") {
        w.addEventListener("DOMContentLoaded", function () { installListeners(); w.setTimeout(function () { report(); }, 700); }, { once: true });
      } else {
        installListeners();
        w.setTimeout(function () { report(); }, 700);
      }
    }
  } catch (error) {
    if (w.console && typeof w.console.error === "function") w.console.error("NIMS WebView shim failed", error);
  }
})(typeof window !== "undefined" ? window : null);
