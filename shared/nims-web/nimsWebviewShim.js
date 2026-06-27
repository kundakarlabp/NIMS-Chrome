// Android-only compatibility and native CR-search bridge for legacy NIMS pages.
(function (w) {
  if (!w) return;

  var CR_LABEL = "Cr No Wise Result Report Printing New";
  var CR_MENU_ID = "Cr_No_Wise_Result_Report_Printing_New";
  var CR_ENDPOINT = "/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt";
  var CR_FRAME_ID = CR_LABEL + "_iframe";
  var capturedCrUrl = "";
  var repairGeneration = 0;

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
      var jqAttempts = 0;
      var jqTimer = w.setInterval(function () {
        jqAttempts += 1;
        if (patchOffset(w.jQuery) || patchOffset(w.$) || jqAttempts > 200) w.clearInterval(jqTimer);
      }, 50);
    }

    var capturedErrors = [];
    w.__nimsShimErrors = capturedErrors;

    function safeHostPath(value) {
      try {
        var parsed = new URL(String(value || ""), w.location && w.location.href || "https://www.nimsts.edu.in/");
        return parsed.hostname + parsed.pathname;
      } catch (e) {
        return "";
      }
    }

    function report(extra) {
      try {
        var bridge = w.nimsAndroidBridge;
        var doc = w.document;
        var body = doc && doc.body;
        if (!bridge || typeof bridge.postMessage !== "function") return false;
        var notes = capturedErrors.slice(0, 5);
        if (extra) notes.unshift(extra);
        bridge.postMessage(JSON.stringify({
          type: "nims_frame_debug",
          url: safeHostPath(w.location && w.location.href),
          children: body && body.querySelectorAll ? body.querySelectorAll("*").length : 0,
          textLen: body && body.innerText ? String(body.innerText).trim().length : 0,
          height: body ? body.scrollHeight || 0 : 0,
          errors: notes.slice(0, 6)
        }));
        return true;
      } catch (e) {
        return false;
      }
    }

    function topWindow() {
      try { return w.top || w; } catch (e) { return w; }
    }

    function topDocument() {
      var top = topWindow();
      try { return top.document || w.document; } catch (e) { return w.document; }
    }

    function exactCrFrame(doc) {
      if (!doc) return null;
      try {
        return (doc.getElementById && doc.getElementById(CR_FRAME_ID)) ||
          (doc.querySelector && doc.querySelector('iframe[name="' + CR_FRAME_ID + '"],frame[name="' + CR_FRAME_ID + '"]')) || null;
      } catch (e) {
        return null;
      }
    }

    function frameHasUsableCrContent(frame) {
      if (!frame) return false;
      try {
        var doc = frame.contentDocument;
        if (!doc || !doc.body) return false;
        if (doc.querySelector('input[name="patCrNo"],input[id="patCrNo"]')) return true;
        var controls = Array.prototype.slice.call(doc.querySelectorAll("[onclick]"));
        if (controls.some(function (node) {
          return /^\s*printReport\s*\([^,()]+\)\s*;?\s*$/.test(node.getAttribute("onclick") || "");
        })) return true;
        return String(doc.body.innerText || "").trim().length > 20;
      } catch (e) {
        return false;
      }
    }

    function ticketedCrUrl(value) {
      try {
        var parsed = new URL(String(value || ""), w.location && w.location.href || "https://www.nimsts.edu.in/");
        var allowedHost = parsed.hostname === "nimsts.edu.in" || parsed.hostname === "www.nimsts.edu.in";
        var correctPath = parsed.pathname === CR_ENDPOINT;
        return allowedHost && correctPath && parsed.search.length > 1 ? parsed.href : "";
      } catch (e) {
        return "";
      }
    }

    function inspectValue(value, depth) {
      if (depth > 3 || value == null) return "";
      if (typeof value === "string") return ticketedCrUrl(value);
      if (Array.isArray(value) || typeof value.length === "number") {
        for (var i = 0; i < value.length; i += 1) {
          var fromArray = inspectValue(value[i], depth + 1);
          if (fromArray) return fromArray;
        }
      }
      if (typeof value === "object") {
        var keys = ["url", "href", "src", "path", "menuUrl", "tabUrl"];
        for (var k = 0; k < keys.length; k += 1) {
          var fromObject = inspectValue(value[keys[k]], depth + 1);
          if (fromObject) return fromObject;
        }
      }
      return "";
    }

    function rememberTicketedCrUrl(args) {
      var found = inspectValue(args, 0);
      if (found) capturedCrUrl = found;
      return Boolean(found);
    }

    function repairCrTab(reason) {
      var generation = ++repairGeneration;
      var top = topWindow();
      var delays = [100, 350, 800, 1600];
      delays.forEach(function (delay, index) {
        if (typeof w.setTimeout !== "function") return;
        w.setTimeout(function () {
          if (generation !== repairGeneration) return;
          var doc = topDocument();
          var frame = exactCrFrame(doc);
          if (frame) {
            try {
              if (!frame.id) frame.id = CR_FRAME_ID;
              if (!frame.name) frame.name = CR_FRAME_ID;
            } catch (e) { /* ignore */ }
            if (frameHasUsableCrContent(frame)) {
              report("TAB repair ready reason=" + reason);
              repairGeneration += 1;
              return;
            }
            if (capturedCrUrl) {
              try {
                var currentSrc = frame.getAttribute ? frame.getAttribute("src") || "" : "";
                if (!currentSrc || currentSrc === "about:blank") {
                  frame.setAttribute("src", capturedCrUrl);
                  report("TAB repair frame_src_applied reason=" + reason);
                }
              } catch (e) { /* ignore */ }
            }
          }
          if (index === delays.length - 1 && capturedCrUrl) {
            try {
              if (top.location && typeof top.location.assign === "function") {
                report("TAB repair top_navigation reason=" + reason);
                repairGeneration += 1;
                top.location.assign(capturedCrUrl);
              }
            } catch (e) {
              report("TAB repair failed reason=" + reason);
            }
          }
        }, delay);
      });
    }

    function wrapNavigationFunction(name, fn) {
      if (typeof fn !== "function" || fn.__nimsTabRaceWrapped) return fn;
      var wrapped = function () {
        var isCrCall = rememberTicketedCrUrl(arguments) || Array.prototype.some.call(arguments, function (arg) {
          return typeof arg === "string" && (arg.indexOf(CR_LABEL) >= 0 || arg.indexOf(CR_MENU_ID) >= 0 || arg.indexOf(CR_ENDPOINT) >= 0);
        });
        try {
          return fn.apply(this, arguments);
        } finally {
          if (isCrCall || capturedCrUrl) repairCrTab("after_" + name);
        }
      };
      wrapped.__nimsTabRaceWrapped = true;
      wrapped.__nimsOriginal = fn;
      return wrapped;
    }

    function hookNavigationFunction(name) {
      var current;
      try { current = w[name]; } catch (e) { current = undefined; }
      try {
        if (typeof current === "function") w[name] = wrapNavigationFunction(name, current);
      } catch (e) { /* ignore */ }
      try {
        var descriptor = Object.getOwnPropertyDescriptor(w, name);
        if (!descriptor || descriptor.configurable) {
          Object.defineProperty(w, name, {
            configurable: true,
            enumerable: true,
            get: function () { return current; },
            set: function (next) { current = wrapNavigationFunction(name, next); }
          });
          return true;
        }
      } catch (e) { /* ignore */ }
      return typeof w[name] === "function" && Boolean(w[name].__nimsTabRaceWrapped);
    }

    var navigationNames = ["addTab", "addNewTab", "addTabNew", "createTab"];
    navigationNames.forEach(hookNavigationFunction);
    if (typeof w.setInterval === "function") {
      var navAttempts = 0;
      var navTimer = w.setInterval(function () {
        navAttempts += 1;
        var wrapped = false;
        navigationNames.forEach(function (name) {
          try {
            if (typeof w[name] === "function" && !w[name].__nimsTabRaceWrapped) w[name] = wrapNavigationFunction(name, w[name]);
            wrapped = wrapped || Boolean(w[name] && w[name].__nimsTabRaceWrapped);
          } catch (e) { /* ignore */ }
        });
        if (wrapped || navAttempts > 600) w.clearInterval(navTimer);
      }, 50);
    }

    if (typeof w.addEventListener === "function") {
      w.addEventListener("error", function (event) {
        try {
          var source = String(event && event.filename || "").split("/").pop();
          var message = String(event && event.message || "error");
          if (capturedErrors.length < 12) capturedErrors.push(message + " @" + source + ":" + String(event && event.lineno || "?"));
          if (/contentDocument/i.test(message) && /tabmenu\.js/i.test(source)) repairCrTab("tabmenu_contentDocument_error");
        } catch (e) { /* ignore */ }
      });
    }

    function isInvestigation(node) {
      for (var current = node, depth = 0; current && depth < 7; current = current.parentElement, depth += 1) {
        try {
          var onclick = current.getAttribute ? current.getAttribute("onclick") || "" : "";
          var text = String(current.innerText || current.textContent || current.value || "").replace(/\s+/g, " ").trim();
          if (/menuSelected\s*\(\s*['"]Investigation['"]\s*,\s*true\s*\)/i.test(onclick) || /^Investigation$/i.test(text)) return true;
        } catch (e) { /* ignore */ }
      }
      return false;
    }

    function installNativeCrOpen() {
      var doc = w.document;
      if (!doc || doc.__nimsNativeCrOpenInstalled || typeof doc.addEventListener !== "function") return;
      doc.__nimsNativeCrOpenInstalled = true;
      doc.addEventListener("click", function (event) {
        if (!isInvestigation(event && event.target)) return;
        var attempts = 0;
        function open() {
          attempts += 1;
          try {
            var frame = (doc.getElementById && doc.getElementById("frmMainMenu")) ||
              (doc.querySelector && doc.querySelector('iframe[name="frmMainMenu"],frame[name="frmMainMenu"]'));
            var child = frame && frame.contentWindow;
            if (child && typeof child.callMenu === "function") {
              child.callMenu(CR_ENDPOINT, CR_MENU_ID);
              report("NAV native_cr_open action=called_child_callMenu");
              repairCrTab("native_child_callMenu");
              return;
            }
            if (typeof w.callMenu === "function") {
              w.callMenu(CR_ENDPOINT, CR_LABEL);
              report("NAV native_cr_open action=called_top_callMenu");
              repairCrTab("native_top_callMenu");
              return;
            }
          } catch (e) {
            report("NAV native_cr_open error=" + String(e && e.message || "unknown").slice(0, 100));
            repairCrTab("native_callMenu_error");
            return;
          }
          if (attempts < 4 && typeof w.setTimeout === "function") w.setTimeout(open, 500);
          else report("NAV native_cr_open error=native_callMenu_unavailable");
        }
        if (typeof w.setTimeout === "function") w.setTimeout(open, 250);
        else open();
      }, true);
    }

    function start() {
      installNativeCrOpen();
      var attempts = 0;
      function initialReport() {
        attempts += 1;
        if (!report() && attempts < 6) w.setTimeout(initialReport, 1000);
      }
      w.setTimeout(initialReport, 800);
    }

    if (w.document && typeof w.setTimeout === "function") {
      if (w.document.readyState === "loading" && typeof w.addEventListener === "function") {
        w.addEventListener("DOMContentLoaded", start, { once: true });
      } else {
        start();
      }
    }
  } catch (error) {
    if (w.console && typeof w.console.error === "function") w.console.error("NIMS WebView shim failed", error);
  }
})(typeof window !== "undefined" ? window : null);
