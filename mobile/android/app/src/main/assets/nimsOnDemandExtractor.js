(function () {
  "use strict";

  var REPORT_PATH = "/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt";
  var allowedHosts = { "nimsts.edu.in": true, "www.nimsts.edu.in": true };
  var rows = [];
  var seen = {};
  var documents = [];
  var reachableDocuments = 0;
  var blockedFrames = 0;
  var loginDetected = false;
  var crInputDetected = false;

  function compact(value) {
    return String(value || "").replace(/\s+/g, " ").trim();
  }

  function textOf(node) {
    return compact(node && (node.innerText || node.textContent || node.value || ""));
  }

  function visible(node) {
    if (!node || node.hidden || node.isConnected === false) return false;
    try {
      var style = node.ownerDocument.defaultView.getComputedStyle(node);
      if (style.display === "none" || style.visibility === "hidden") return false;
    } catch (_error) { /* best effort only */ }
    return true;
  }

  function safeLocation(doc) {
    try {
      var url = new URL(doc.location.href);
      if (url.protocol !== "https:" || !allowedHosts[url.hostname]) return "";
      return url.hostname + url.pathname;
    } catch (_error) {
      return "";
    }
  }

  function unquote(value) {
    var text = String(value || "").trim();
    if ((text.charAt(0) === "'" && text.charAt(text.length - 1) === "'") ||
        (text.charAt(0) === '"' && text.charAt(text.length - 1) === '"')) {
      return text.slice(1, -1).replace(/\\(['"\\])/g, "$1");
    }
    return text;
  }

  function printToken(node) {
    if (!node || !node.getAttribute) return "";
    var onclick = String(node.getAttribute("onclick") || "");
    var match = onclick.match(/^\s*(?:return\s+)?printReport\s*\(\s*(["'][\s\S]*?["'])\s*\)\s*;?\s*$/i);
    if (!match) return "";
    var token = unquote(match[1]);
    if (token.length < 5 || token.length > 256) return "";
    if (token.indexOf("..") >= 0 || /[\\/\u0000-\u001f\u007f]/.test(token)) return "";
    return /^[A-Za-z0-9_-]+\.pdf$/i.test(token) ? token : "";
  }

  function reportButton(row) {
    var candidates = Array.prototype.slice.call(row.querySelectorAll("[onclick], a, button, input[type='button'], input[type='submit']"));
    for (var i = 0; i < candidates.length; i += 1) {
      if (printToken(candidates[i])) return candidates[i];
    }
    return null;
  }

  function dateFrom(text) {
    var match = String(text || "").match(/\b(?:\d{1,2}[-\/.]\d{1,2}[-\/.](?:\d{2}|\d{4})|(?:\d{4})[-\/.]\d{1,2}[-\/.]\d{1,2})\b/);
    return match ? match[0] : "";
  }

  function tagsFor(text) {
    var lower = String(text || "").toLowerCase();
    var tags = [];
    function add(tag, pattern) { if (pattern.test(lower) && tags.indexOf(tag) < 0) tags.push(tag); }
    add("culture", /culture|sensitivity|susceptibility|microbiology|blood c\/s|urine c\/s|pus c\/s/);
    add("cbc", /complete blood|haemogram|hemogram|cbc|platelet|hemoglobin|haemoglobin/);
    add("rft", /renal|kidney|urea|creatinine|rft/);
    add("lft", /liver|bilirubin|sgot|sgpt|ast|alt|alkaline phosphatase|lft/);
    add("electrolytes", /electrolyte|sodium|potassium|chloride|calcium|magnesium|phosph/);
    add("inflammatory", /crp|procalcitonin|esr|ferritin|inflammatory/);
    if (!tags.length) tags.push("other");
    return tags;
  }

  function reportName(cells, rowText) {
    var candidates = cells.filter(function (cell) {
      return cell && !/view\s*report/i.test(cell) && !/^\d+$/.test(cell) && !dateFrom(cell);
    });
    candidates.sort(function (a, b) { return b.length - a.length; });
    return (candidates[0] || rowText.replace(/view\s*report/ig, "").trim()).slice(0, 180);
  }

  function department(cells) {
    for (var i = 0; i < cells.length; i += 1) {
      if (/biochem|patholog|microbiolog|haemat|hemat|radiolog|medicine|surgery|immunolog/i.test(cells[i])) {
        return cells[i].slice(0, 120);
      }
    }
    return "";
  }

  function addRows(doc) {
    var tableRows = Array.prototype.slice.call(doc.querySelectorAll("tr"));
    for (var index = 0; index < tableRows.length; index += 1) {
      var row = tableRows[index];
      if (!visible(row)) continue;
      var button = reportButton(row);
      if (!button) continue;
      var token = printToken(button);
      if (!token || seen[token]) continue;
      seen[token] = true;
      var cells = Array.prototype.slice.call(row.cells || []).map(textOf);
      var rowText = textOf(row);
      var tags = tagsFor(cells.join(" ") + " " + rowText);
      rows.push({
        row_index: index,
        date_sent: dateFrom(cells.join(" ") + " " + rowText),
        department: department(cells),
        report_name: reportName(cells, rowText),
        report_type: tags[0],
        report_tags: tags,
        transientPrintReportArg: token,
        frame_path: safeLocation(doc)
      });
    }
  }

  function scan(doc, depth) {
    if (!doc || depth > 8) return;
    var path = safeLocation(doc);
    if (!path) return;
    reachableDocuments += 1;
    documents.push(doc);
    if (doc.querySelector("input[type='password']")) loginDetected = true;
    if (doc.querySelector("input[name='patCrNo'], input[id='patCrNo'], input[name*='crno' i], input[id*='crno' i], input[name*='cr_no' i], input[id*='cr_no' i]")) crInputDetected = true;
    addRows(doc);

    var frames = Array.prototype.slice.call(doc.querySelectorAll("iframe, frame"));
    for (var i = 0; i < frames.length; i += 1) {
      try {
        var child = frames[i].contentDocument;
        if (child && child.documentElement) scan(child, depth + 1);
        else blockedFrames += 1;
      } catch (_error) {
        blockedFrames += 1;
      }
    }
  }

  function verifiedTemplate() {
    for (var i = 0; i < documents.length; i += 1) {
      var doc = documents[i];
      try {
        var frame = doc.querySelector("iframe#setPdf");
        if (frame) {
          var src = new URL(frame.getAttribute("src") || "", doc.location.href);
          if (src.protocol === "https:" && allowedHosts[src.hostname] && src.pathname === REPORT_PATH &&
              src.searchParams.get("hmode") === "PRINTREPORT" && src.searchParams.has("fileName")) {
            return {
              origin: src.origin,
              pathname: REPORT_PATH,
              modeParamName: "hmode",
              modeParamValue: "PRINTREPORT",
              argumentParameterName: "fileName"
            };
          }
        }
      } catch (_error) { /* continue */ }
      try {
        var fn = doc.defaultView && doc.defaultView.printReport;
        if (typeof fn === "function") {
          var source = Function.prototype.toString.call(fn);
          if (source.indexOf("invDuplicateResultReportPrinting.cnt") >= 0 &&
              source.indexOf("PRINTREPORT") >= 0 && source.indexOf("fileName") >= 0) {
            return {
              origin: new URL(doc.location.href).origin,
              pathname: REPORT_PATH,
              modeParamName: "hmode",
              modeParamValue: "PRINTREPORT",
              argumentParameterName: "fileName"
            };
          }
        }
      } catch (_error2) { /* continue */ }
    }
    return null;
  }

  try {
    scan(document, 0);
    var pageKind = rows.length ? "cr_results" : (loginDetected ? "login" : (crInputDetected ? "cr_search" : "portal"));
    return JSON.stringify({
      ok: true,
      pageKind: pageKind,
      path: safeLocation(document),
      reachableDocuments: reachableDocuments,
      blockedFrames: blockedFrames,
      rows: rows,
      template: rows.length ? verifiedTemplate() : null
    });
  } catch (error) {
    return JSON.stringify({
      ok: false,
      error: String(error && error.message || error || "Extraction failed"),
      rows: [],
      reachableDocuments: reachableDocuments,
      blockedFrames: blockedFrames
    });
  }
})();
