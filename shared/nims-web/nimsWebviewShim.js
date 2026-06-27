// Android-only compatibility and native CR-search bridge for legacy NIMS pages.
(function (w) {
  if (!w) return;

  var CR_ENDPOINT = "/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt";
  var CR_MENU_ID = "Cr_No_Wise_Result_Report_Printing_New";
  var CR_MENU_LABEL = "Cr No Wise Result Report Printing New";
  var REPORT_FRAME_ID = "Cr No Wise Result Report Printing New_iframe";
  var openSequence = 0;

  try {
    if (typeof w.date_time === "undefined") w.date_time = function () { return ""; };

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

    function hookJquery(name) {
      var value;
      try { value = w[name]; } catch (e) { value = undefined; }
      if (value && patchOffset(value)) return true;
      try {
        Object.defineProperty(w, name, {
          configurable: true,
          enumerable: true,
          get: function () { return value; },
          set: function (next) { value = next; patchOffset(next); }
        });
        return true;
      } catch (e) {
        return false;
      }
    }

    var hookedJquery = hookJquery("jQuery");
    var hookedDollar = hookJquery("$");
    if ((!hookedJquery || !hookedDollar) && typeof w.setInterval === "function") {
      var jqueryChecks = 0;
      var jqueryTimer = w.setInterval(function () {
        jqueryChecks += 1;
        if (patchOffset(w.jQuery) || patchOffset(w.$) || jqueryChecks > 200) w.clearInterval(jqueryTimer);
      }, 50);
    }

    var capturedErrors = [];
    w.__nimsShimErrors = capturedErrors;
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

    function safePath() {
      try {
        var url = new URL(w.location.href);
        return url.hostname + url.pathname;
      } catch (e) {
        return "";
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

    function report(extra) {
      try {
        var doc = w.document;
        var body = doc && doc.body;
        var list = capturedErrors.slice(0, 6);
        if (extra) list.unshift(extra);
        return bridgePost({
          type: "nims_frame_debug",
          url: safePath(),
          children: body ? body.querySelectorAll("*").length : 0,
          textLen: body && body.innerText ? body.innerText.trim().length : 0,
          height: body ? body.scrollHeight || 0 : 0,
          errors: list.slice(0, 6)
        });
      } catch (e) {
        return false;
      }
    }

    function isTopWindow() {
      try { return w.top === w; } catch (e) { return false; }
    }

    function clearStoredReport(reason) {
      if (!isTopWindow()) return;
      bridgePost({
        type: "nims_report_frame",
        href: safePath(),
        rowCount: 0,
        rows: [],
        clearReason: reason || "navigation"
      });
    }

    function contentDocumentRace(error) {
      var message = String(error && error.message || error || "");
      return /contentDocument/i.test(message) && /undefined|null|cannot read/i.test(message);
    }

    function wrapRaceFunction(name) {
      var original;
      try { original = w[name]; } catch (e) { return false; }
      if (typeof original !== "function" || original.__nimsContentDocumentGuard) return Boolean(original && original.__nimsContentDocumentGuard);

      var wrapped = function () {
        var receiver = this;
        var args = arguments;
        var attempts = 0;
        function invoke() {
          try {
            return original.apply(receiver, args);
          } catch (error) {
            attempts += 1;
            if (!contentDocumentRace(error) || attempts >= 4 || typeof w.setTimeout !== "function") throw error;
            report("NAV " + name + " deferred contentDocument attempt=" + attempts);
            w.setTimeout(invoke, 120 * attempts);
            return undefined;
          }
        }
        return invoke();
      };
      wrapped.__nimsContentDocumentGuard = true;
      wrapped.__nimsOriginal = original;
      try {
        w[name] = wrapped;
        return w[name] === wrapped;
      } catch (e) {
        return false;
      }
    }

    function installTabRaceGuards() {
      wrapRaceFunction("addTab");
      wrapRaceFunction("callMenu");
      if (typeof w.setInterval !== "function") return;
      var checks = 0;
      var timer = w.setInterval(function () {
        checks += 1;
        var addTabReady = wrapRaceFunction("addTab");
        var callMenuReady = wrapRaceFunction("callMenu");
        if ((addTabReady && callMenuReady) || checks > 400) w.clearInterval(timer);
      }, 50);
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
        return (doc.getElementById && doc.getElementById("frmMainMenu")) ||
          (doc.querySelector && doc.querySelector('iframe[name="frmMainMenu"],frame[name="frmMainMenu"]')) || null;
      } catch (e) {
        return null;
      }
    }

    function exactCrAnchor(doc) {
      if (!doc) return null;
      try {
        var exact = doc.getElementById && doc.getElementById(CR_MENU_ID);
        if (exact) return exact;
        var nodes = doc.querySelectorAll ? Array.prototype.slice.call(doc.querySelectorAll("[onclick],a,button")) : [];
        for (var i = 0; i < nodes.length; i += 1) {
          var node = nodes[i];
          var onclick = node.getAttribute ? node.getAttribute("onclick") || "" : "";
          var label = String(node.innerText || node.textContent || node.value || "").replace(/\s+/g, " ").trim();
          if (onclick.indexOf(CR_ENDPOINT) >= 0 || onclick.indexOf(CR_MENU_ID) >= 0 || label === CR_MENU_LABEL) return node;
        }
      } catch (e) { /* ignore */ }
      return null;
    }

    function clickElement(element) {
      if (!element) return false;
      try {
        if (typeof element.click === "function") {
          element.click();
          return true;
        }
      } catch (e) { /* ignore */ }
      return false;
    }

    function reportFrameExists(doc) {
      try { return Boolean(doc && doc.getElementById && doc.getElementById(REPORT_FRAME_ID)); } catch (e) { return false; }
    }

    function waitForReportFrame(sequence, action) {
      var checks = 0;
      function check() {
        if (sequence !== openSequence) return;
        checks += 1;
        if (reportFrameExists(w.document)) {
          report("NAV native_cr_open action=" + action + " report_iframe=ready");
          return;
        }
        if (checks < 24 && typeof w.setTimeout === "function") {
          w.setTimeout(check, 250);
        } else {
          report("NAV native_cr_open action=" + action + " error=report_iframe_missing");
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
          var frame = menuFrame(w.document);
          var childDoc = frame && frame.contentDocument;
          var childWindow = frame && frame.contentWindow;
          var ready = childDoc && (childDoc.readyState === "interactive" || childDoc.readyState === "complete");
          var anchor = ready ? exactCrAnchor(childDoc) : null;

          if (anchor && clickElement(anchor)) {
            report("NAV native_cr_open action=clicked_exact_cr_anchor");
            waitForReportFrame(sequence, "clicked_exact_cr_anchor");
            return;
          }

          // Do not invoke callMenu against the old Home-menu document. Give the
          // Investigation menu enough time to finish loading and expose its anchor.
          if (checks >= 20 && ready && childWindow && typeof childWindow.callMenu === "function") {
            childWindow.callMenu(CR_ENDPOINT, CR_MENU_ID);
            report("NAV native_cr_open action=called_child_callMenu");
            waitForReportFrame(sequence, "called_child_callMenu");
            return;
          }

          if (checks >= 24 && typeof w.callMenu === "function") {
            w.callMenu(CR_ENDPOINT, CR_MENU_LABEL);
            report("NAV native_cr_open action=called_top_callMenu");
            waitForReportFrame(sequence, "called_top_callMenu");
            return;
          }
        } catch (error) {
          if (contentDocumentRace(error) && checks < 32 && typeof w.setTimeout === "function") {
            report("NAV native_cr_open deferred contentDocument attempt=" + checks);
            w.setTimeout(attempt, 250);
            return;
          }
          report("NAV native_cr_open error=" + String(error && error.message || "unknown").slice(0, 100));
          return;
        }

        if (checks < 32 && typeof w.setTimeout === "function") {
          w.setTimeout(attempt, 250);
        } else {
          report("NAV native_cr_open error=investigation_menu_not_ready");
        }
      }

      if (typeof w.setTimeout === "function") w.setTimeout(attempt, 250);
      else attempt();
    }

    function install() {
      var doc = w.document;
      installTabRaceGuards();
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
