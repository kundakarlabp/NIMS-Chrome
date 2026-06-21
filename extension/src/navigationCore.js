(function (root) {
  const NIMS_ALLOWED_HOSTS = new Set(["nimsts.edu.in", "www.nimsts.edu.in"]);
  const CR_WISE_MENU_ID = "Cr_No_Wise_Result_Report_Printing_New";
  const CR_WISE_ENDPOINT = "/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt";
  const NIMS_PAGE_STAGE = Object.freeze({ LOGIN: "login", HOME: "home", INVESTIGATION_MENU: "investigation_menu", CR_SEARCH: "cr_search", REPORT_LIST: "report_list", REPORT_VIEWER: "report_viewer", SESSION_EXPIRED: "session_expired", UNKNOWN: "unknown" });
  const NAVIGATION_ACTION_COOLDOWN_MS = 1500;

  function diagnosePage(doc) {
    const frames = collectFrames(doc || root.document);
    const best = frames.slice().sort((a, b) => frameScore(b) - frameScore(a))[0] || null;
    const stage = detectNimsPageStage(doc || root.document);
    return {
      activeUrl: safeHostPath(root.location && root.location.href),
      framesChecked: stage.framesChecked || frames.length,
      detectedStage: stage.stage,
      evidence: stage.evidence || [],
      recommendedNextStep: recommendedNextStep(stage.stage),
      crWiseMenuFound: frames.some((f) => f.hasCrWiseMenu),
      investigationModuleFound: frames.some((f) => f.hasInvestigationModule),
      crSearchFormFound: frames.some((f) => f.hasCrSearchForm),
      bestFramePath: best ? best.url : "",
      viewReportRows: best ? best.viewReportRows : 0,
      printReportRows: best ? best.printReportRows : 0,
      setPdfTemplateDiscovered: Boolean(best && best.setPdfTemplate && best.setPdfTemplate.discovered)
    };
  }

  function bestReportDocument(doc) {
    return accessibleDocuments(doc || root.document)
      .map((item) => ({ doc: item.doc, rows: extractReportRows(item.doc, item.safeUrl || item.url) }))
      .sort((a, b) => b.rows.length - a.rows.length)[0] || { doc: doc || root.document, rows: [] };
  }

  function collectFrames(doc) {
    return accessibleDocuments(doc || root.document).map((item) => frameDiagnostic(item.doc, item.safeUrl || item.url, item.depth, item.visibleThroughAncestors));
  }

  function safeDocumentHref(doc) {
    try { return doc && doc.location ? doc.location.href : ""; } catch { return ""; }
  }

  function accessibleDocumentsRecursive(startDoc, maxDepth = 6) {
    const output = [];
    const visited = new Set();
    function visit(currentDoc, depth, frameElement, parentVisible) {
      if (!currentDoc || depth > maxDepth || visited.has(currentDoc)) return;
      visited.add(currentDoc);
      const visibleThroughAncestors = parentVisible && isElementVisible(frameElement);
      output.push({ doc: currentDoc, win: currentDoc.defaultView || null, depth, frameElement: frameElement || null, safeUrl: safeHostPath(safeDocumentHref(currentDoc)), visibleThroughAncestors });
      let frames = [];
      try { frames = Array.from(currentDoc.querySelectorAll("iframe, frame")); } catch { frames = []; }
      for (const frame of frames) {
        try { if (frame.contentDocument) visit(frame.contentDocument, depth + 1, frame, visibleThroughAncestors); } catch { }
      }
    }
    visit(startDoc || root.document, 0, null, true);
    return output;
  }

  function accessibleDocuments(doc) {
    return accessibleDocumentsRecursive(doc || root.document);
  }

  function frameDiagnostic(doc, url, depth, visibleThroughAncestors = true) {
    const rows = extractReportRows(doc, url);
    const stage = detectSingleDocumentStage(doc, url, rows);
    const crWise = findCrWiseReportMenuTargetInDocument(doc);
    const investigation = findInvestigationModuleTargetInDocument(doc);
    const setPdfTemplate = getSafeSetPdfTemplate(doc);
    return {
      safePath: safeHostPath(url || safeDocumentHref(doc)),
      url: safeHostPath(url || safeDocumentHref(doc)),
      title: compactText(doc.title || "").slice(0, 80),
      depth: Number(depth || 0),
      visibleThroughAncestors: Boolean(visibleThroughAncestors),
      visible: Boolean(visibleThroughAncestors),
      detectedStage: stage.stage,
      stage: stage.stage,
      evidence: stage.evidence || [],
      actionable: Boolean(crWise.ok || investigation.ok),
      targetMethod: crWise.ok ? crWise.method : (investigation.ok ? investigation.method : ""),
      hasCrWiseMenu: Boolean(crWise.ok),
      hasInvestigationModule: Boolean(investigation.ok),
      hasCrSearchForm: hasCrSearchEvidence(doc, url).present,
      viewReportRows: rows.length,
      printReportRows: rows.filter((row) => row.onclick_function_name === "printReport").length,
      hasSetPdfTemplate: Boolean(setPdfTemplate && setPdfTemplate.discovered),
      setPdfTemplate
    };
  }


  function detectNimsPageStage(doc) {
    const docs = accessibleDocumentsRecursive(doc || root.document);
    const candidates = (docs.some((item) => item.visibleThroughAncestors) ? docs.filter((item) => item.visibleThroughAncestors) : docs)
      .map((item) => ({ item, diagnostic: getCurrentDocumentNavigationDiagnostic(item.doc, item.depth, item.visibleThroughAncestors) }))
      .filter((entry) => navigationStageScore(entry.diagnostic) > 0);
    if (!candidates.length) return { stage: NIMS_PAGE_STAGE.UNKNOWN, safePath: "", framesChecked: docs.length, evidence: [] };
    candidates.sort((a, b) => compareStageDiagnostics(a.diagnostic, b.diagnostic));
    const best = candidates[0].diagnostic;
    return { stage: best.stage, safePath: best.safePath, framesChecked: docs.length, evidence: best.evidence || [] };
  }

  function detectCurrentDocumentStage(doc) {
    const currentDoc = doc || root.document;
    return detectSingleDocumentStage(currentDoc, safeHostPath(safeDocumentHref(currentDoc)), extractReportRows(currentDoc, safeDocumentHref(currentDoc)));
  }

  function getCurrentDocumentNavigationDiagnostic(doc, depth = 0, visibleThroughAncestors = true) {
    const currentDoc = doc || root.document;
    const safePath = safeHostPath(safeDocumentHref(currentDoc));
    const rows = extractReportRows(currentDoc, safePath);
    const stage = detectSingleDocumentStage(currentDoc, safePath, rows);
    const crWise = findCrWiseReportMenuTargetInDocument(currentDoc);
    const investigation = findInvestigationModuleTargetInDocument(currentDoc);
    const crSearch = hasCrSearchEvidence(currentDoc, safePath);
    const action = stage.stage === NIMS_PAGE_STAGE.INVESTIGATION_MENU && crWise.ok
      ? "clicked_cr_wise_menu"
      : stage.stage === NIMS_PAGE_STAGE.HOME && investigation.ok
        ? "clicked_investigation_module"
        : "none";
    return {
      stage: stage.stage,
      actionable: action !== "none",
      action,
      visible: Boolean(visibleThroughAncestors) && isDocumentVisible(currentDoc),
      depth: Number(depth || 0),
      safePath,
      evidence: stage.evidence || [],
      targetMethod: crWise.ok ? crWise.method : (investigation.ok ? investigation.method : ""),
      hasInvestigationModule: Boolean(investigation.ok),
      hasCrWiseMenu: Boolean(crWise.ok),
      hasCrSearchForm: crSearch.present,
      viewReportRows: rows.length,
      hasSetPdfTemplate: Boolean(getSafeSetPdfTemplate(currentDoc))
    };
  }

  function detectSingleDocumentStage(doc, safeUrl, rows) {
    const evidence = [];
    const text = compactText((doc.body && textOf(doc.body)) || "").toLowerCase();
    if (/session\s+expired|invalid\s+session|login\s+required|session\s+timeout|timed\s*out/.test(text)) return { stage: NIMS_PAGE_STAGE.SESSION_EXPIRED, safePath: safeUrl || "", evidence: ["session_text"] };
    if ((rows || []).length > 0) return { stage: NIMS_PAGE_STAGE.REPORT_LIST, safePath: safeUrl || "", evidence: ["view_report_rows"] };
    if (getSafeSetPdfTemplate(doc)) return { stage: NIMS_PAGE_STAGE.REPORT_VIEWER, safePath: safeUrl || "", evidence: ["set_pdf_template"] };
    if (findCrWiseReportMenuTargetInDocument(doc).ok) return { stage: NIMS_PAGE_STAGE.INVESTIGATION_MENU, safePath: safeUrl || "", evidence: ["cr_wise_menu_id"] };
    const cr = hasCrSearchEvidence(doc, safeUrl);
    if (cr.present) return { stage: NIMS_PAGE_STAGE.CR_SEARCH, safePath: safeUrl || "", evidence: cr.evidence };
    if (findInvestigationModuleTargetInDocument(doc).ok || plausibleHomeWithMenuFunction(doc)) return { stage: NIMS_PAGE_STAGE.HOME, safePath: safeUrl || "", evidence: ["investigation_module"] };
    if (hasLoginEvidence(doc, safeUrl)) return { stage: NIMS_PAGE_STAGE.LOGIN, safePath: safeUrl || "", evidence: ["login_form"] };
    return { stage: NIMS_PAGE_STAGE.UNKNOWN, safePath: safeUrl || "", evidence };
  }

  function hasCrSearchEvidence(doc, safeUrl) {
    const evidence = [];
    if (String(safeUrl || safeDocumentHref(doc)).toLowerCase().includes("viewcrnowisereportprocess.cnt")) evidence.push("target_endpoint");
    const inputs = Array.from(doc.querySelectorAll("input, textarea, select"));
    const crInput = inputs.some((el) => /cr\s*(no|number)|crno|crnumber/i.test(`${el.id || ""} ${el.name || ""}`));
    const labelText = compactText(Array.from(doc.querySelectorAll("label, th, td, h1, h2, h3, legend")).map(textOf).join(" "));
    if (crInput) evidence.push("cr_input_present");
    if (/CR\s*No|CR\s*Number|CR\s*Wise\s*Result\s*Report\s*Printing/i.test(labelText)) evidence.push("cr_context_present");
    return { present: evidence.includes("target_endpoint") || (crInput && evidence.includes("cr_context_present")), evidence };
  }

  function hasLoginEvidence(doc, safeUrl) {
    if (String(safeUrl || "").toLowerCase().includes("login")) return true;
    return Boolean(doc.querySelector('input[type="password"]')) && Boolean(doc.querySelector('input[type="text"], input[name*="user" i], input[id*="user" i]'));
  }

  function findInvestigationModuleTarget(doc) {
    const docs = accessibleDocumentsRecursive(doc || root.document).sort((a, b) => Number(!a.visibleThroughAncestors) - Number(!b.visibleThroughAncestors) || a.depth - b.depth);
    for (const item of docs) {
      if (!item.visibleThroughAncestors) continue;
      const found = findInvestigationModuleTargetInDocument(item.doc);
      if (found.ok) return { ...found, doc: item.doc, win: item.win };
      if (plausibleHomeWithMenuFunction(item.doc) && item.win && typeof item.win.menuSelected === "function") return { ok: true, method: "frame_function", doc: item.doc, win: item.win };
    }
    return { ok: false, reason: "investigation_module_not_found" };
  }

  function findInvestigationModuleTargetInDocument(doc) {
    const nodes = Array.from(doc.querySelectorAll("[onclick]"));
    const exact = nodes.find((el) => /menuSelected\s*\(\s*['"]Investigation['"]\s*,\s*true\s*\)/.test(el.getAttribute("onclick") || "") && isUsableClickable(el));
    if (exact) return { ok: true, method: "exact_onclick", element: exact };
    const fallback = Array.from(doc.querySelectorAll("a, button, input, [role='button'], [onclick]")).find((el) => {
      const label = compactText(textOf(el) || el.value || "");
      return label === "Investigation" && label.length <= 24 && !/Enquiry|Report/i.test(label) && !el.closest("table") && isUsableClickable(el);
    });
    return fallback ? { ok: true, method: "exact_text", element: fallback } : { ok: false, reason: "investigation_module_not_found" };
  }

  function plausibleHomeWithMenuFunction(doc) { return Boolean(doc.defaultView && typeof doc.defaultView.menuSelected === "function") && /module|menu|home/i.test(compactText(textOf(doc.body || doc)).slice(0, 1000)); }

  function findCrWiseReportMenuTarget(doc) {
    const docs = accessibleDocumentsRecursive(doc || root.document).sort((a, b) => Number(!a.visibleThroughAncestors) - Number(!b.visibleThroughAncestors) || a.depth - b.depth);
    for (const item of docs) {
      if (!item.visibleThroughAncestors) continue;
      const found = findCrWiseReportMenuTargetInDocument(item.doc);
      if (found.ok) return { ...found, doc: item.doc, win: item.win };
    }
    return { ok: false, reason: "cr_wise_menu_not_found" };
  }

  function findCrWiseReportMenuTargetInDocument(doc) {
    const exact = doc.getElementById(CR_WISE_MENU_ID);
    if (exact && isUsableClickable(exact) && crWiseElementLooksValid(exact)) return { ok: true, method: "exact_id", element: exact };
    const candidates = Array.from(doc.querySelectorAll("[onclick], a, button, [role='button']")).filter((el) => isUsableClickable(el) && !el.closest("table"));
    const endpoint = candidates.find((el) => (el.getAttribute("onclick") || "").includes(CR_WISE_ENDPOINT));
    if (endpoint) return { ok: true, method: "exact_endpoint", element: endpoint };
    const menuId = candidates.find((el) => (el.getAttribute("onclick") || "").includes(CR_WISE_MENU_ID));
    if (menuId) return { ok: true, method: "exact_menu_id", element: menuId };
    const label = candidates.find((el) => compactText(textOf(el) || el.value || "") === "Cr No Wise Result Report Printing New");
    if (label) return { ok: true, method: "exact_label", element: label };
    return { ok: false, reason: "cr_wise_menu_not_found" };
  }

  function crWiseElementLooksValid(el) {
    const onclick = el.getAttribute("onclick") || "";
    return onclick.includes(CR_WISE_ENDPOINT) || onclick.includes(CR_WISE_MENU_ID) || compactText(textOf(el)) === "Cr No Wise Result Report Printing New";
  }

  function navigateToCrWiseReports(doc) {
    const detected = detectNimsPageStage(doc || root.document);
    const stage = detected.stage;
    if (stage === NIMS_PAGE_STAGE.REPORT_LIST || stage === NIMS_PAGE_STAGE.CR_SEARCH) return { ok: true, stage, action: "none", done: true };
    if (stage === NIMS_PAGE_STAGE.LOGIN) return { ok: false, stage, action: "none", done: false, errorCode: "manual_login_required" };
    if (stage === NIMS_PAGE_STAGE.SESSION_EXPIRED) return { ok: false, stage, action: "none", done: false, errorCode: "session_expired" };
    if (stage === NIMS_PAGE_STAGE.INVESTIGATION_MENU) {
      const target = findCrWiseReportMenuTarget(doc || root.document);
      return performNavigationTarget(stage, "clicked_cr_wise_menu", target, "cr_wise_menu_not_found");
    }
    if (stage === NIMS_PAGE_STAGE.HOME) {
      const target = findInvestigationModuleTarget(doc || root.document);
      return performNavigationTarget(stage, "clicked_investigation_module", target, "investigation_module_not_found");
    }
    return { ok: false, stage: NIMS_PAGE_STAGE.UNKNOWN, action: "none", done: false, errorCode: "navigation_target_not_found" };
  }

  function navigateCurrentDocumentStep(doc) {
    const currentDoc = doc || root.document;
    const diagnostic = getCurrentDocumentNavigationDiagnostic(currentDoc, 0, true);
    const stage = diagnostic.stage;
    if (stage === NIMS_PAGE_STAGE.REPORT_LIST || stage === NIMS_PAGE_STAGE.CR_SEARCH) return { ok: true, stage, action: "none", done: true };
    if (stage === NIMS_PAGE_STAGE.LOGIN) return { ok: false, stage, action: "none", done: false, errorCode: "manual_login_required" };
    if (stage === NIMS_PAGE_STAGE.SESSION_EXPIRED) return { ok: false, stage, action: "none", done: false, errorCode: "session_expired" };
    if (stage === NIMS_PAGE_STAGE.INVESTIGATION_MENU) return performNavigationTarget(stage, "clicked_cr_wise_menu", findCrWiseReportMenuTargetInDocument(currentDoc), "cr_wise_menu_not_found");
    if (stage === NIMS_PAGE_STAGE.HOME) return performNavigationTarget(stage, "clicked_investigation_module", findInvestigationModuleTargetInDocument(currentDoc), "investigation_module_not_found");
    return { ok: false, stage: NIMS_PAGE_STAGE.UNKNOWN, action: "none", done: false, errorCode: "navigation_target_not_found" };
  }

  function performNavigationTarget(stage, action, target, missingCode) {
    if (!target || !target.ok) return { ok: false, stage, action: "none", done: false, errorCode: missingCode };
    const key = actionCooldownKey(stage, action);
    if (!canPerformNavigationAction(key)) return { ok: true, stage, action: "cooldown", done: false };
    let clicked = false;
    if (target.element) clicked = safeClick(target.element);
    else if (target.win && action === "clicked_investigation_module" && typeof target.win.menuSelected === "function") { target.win.menuSelected("Investigation", true); clicked = true; }
    return clicked ? { ok: true, stage, action, done: false } : { ok: false, stage, action: "none", done: false, errorCode: "click_failed" };
  }

  function safeClick(target) {
    if (!target) return false;
    try { target.scrollIntoView({ block: "center", inline: "nearest", behavior: "auto" }); } catch { }
    try { target.click(); return true; } catch { }
    try {
      const view = target.ownerDocument && target.ownerDocument.defaultView;
      const event = new view.MouseEvent("click", { bubbles: true, cancelable: true, view });
      return target.dispatchEvent(event);
    } catch { return false; }
  }


  function actionCooldownKey(stage, action) {
    return `${stage}:${action}`;
  }

  function canPerformNavigationAction(key) {
    const now = Date.now();
    const scope = root.window || root;
    const previous = scope.__NIMS_LAST_NAVIGATION_ACTION__;
    if (previous && previous.key === key && now - previous.time < NAVIGATION_ACTION_COOLDOWN_MS) return false;
    scope.__NIMS_LAST_NAVIGATION_ACTION__ = { key, time: now };
    return true;
  }

  function isDocumentVisible(doc) {
    try {
      if (!doc || !doc.defaultView) return true;
      if (doc.defaultView.frameElement) return isElementVisible(doc.defaultView.frameElement);
      return true;
    } catch { return true; }
  }

  function isElementVisible(element) {
    if (!element) return true;
    if (element.hidden || element.getAttribute("aria-hidden") === "true") return false;
    const win = element.ownerDocument && element.ownerDocument.defaultView;
    let style = null;
    try { style = win && win.getComputedStyle ? win.getComputedStyle(element) : null; } catch { style = null; }
    if (style && (style.display === "none" || style.visibility === "hidden" || style.visibility === "collapse" || Number(style.opacity) === 0)) return false;
    try { if (element.getClientRects && element.getClientRects().length === 0) return false; } catch { }
    return true;
  }

  function navigationStageScore(frame) {
    const scores = { session_expired: 9000, report_list: 8000, cr_search: 7000, report_viewer: 6000, investigation_menu: 5000, home: 4000, login: 3000, unknown: 0 };
    return scores[frame && frame.stage] || scores[frame && frame.detectedStage] || 0;
  }

  function compareStageDiagnostics(a, b) {
    const scoreDiff = navigationStageScore(b) - navigationStageScore(a);
    if (scoreDiff) return scoreDiff;
    if (Boolean(b.actionable) !== Boolean(a.actionable)) return Number(Boolean(b.actionable)) - Number(Boolean(a.actionable));
    const depthDiff = Number(a.depth || 0) - Number(b.depth || 0);
    if (depthDiff) return depthDiff;
    const evidenceDiff = ((b.evidence || []).length) - ((a.evidence || []).length);
    if (evidenceDiff) return evidenceDiff;
    return 0;
  }

  function isUsableClickable(el) {
    if (!el || !el.isConnected || el.disabled || el.getAttribute("aria-disabled") === "true") return false;
    const style = el.ownerDocument.defaultView && el.ownerDocument.defaultView.getComputedStyle ? el.ownerDocument.defaultView.getComputedStyle(el) : null;
    if (style && (style.display === "none" || style.visibility === "hidden")) return false;
    return Boolean(el.getAttribute("onclick") || el.tagName === "A" || el.tagName === "BUTTON" || el.getAttribute("role") === "button" || typeof el.onclick === "function");
  }

  function frameScore(frame) {
    const base = { report_list: 5000, cr_search: 4000, investigation_menu: 3000, home: 2000, login: 1000 }[frame.detectedStage] || 0;
    return base + Number(frame.viewReportRows || 0) + (frame.setPdfTemplate && frame.setPdfTemplate.discovered ? 10 : 0) - Number(frame.depth || 0);
  }

  function recommendedNextStep(stage) {
    return ({ home: "Open CR Reports", investigation_menu: "Open CR Reports", cr_search: "Enter the CR number in NIMS.", report_list: "Discover Mapping.", login: "Login manually.", session_expired: "Login again." })[stage] || "Open CR Reports";
  }

  function extractReportRows(doc, baseUrl) {
    const rows = [];
    const buttons = Array.from((doc || root.document).querySelectorAll("a, button, input[type='button'], input[type='submit']"))
      .filter((node) => /view\s*report/i.test(textOf(node) || node.value || ""));
    Array.from((doc || root.document).querySelectorAll("tr")).forEach((tr, index) => {
      const rowText = compactText(textOf(tr));
      if (!/view\s*report/i.test(rowText)) return;
      const cells = Array.from(tr.cells || []).map((cell) => compactText(textOf(cell)));
      const button = Array.from(tr.querySelectorAll("[onclick]"))[0];
      const onclick = button ? button.getAttribute("onclick") || "" : "";
      const parsed = parseFunctionArgs(onclick);
      const tags = inferReportTags(`${guessReportName(cells, rowText)} ${rowText}`);
      rows.push({
        row_index: index,
        view_report_button_index: buttons.indexOf(button),
        date_sent: guessDate(cells, rowText),
        report_name: guessReportName(cells, rowText),
        department: guessDepartment(cells),
        report_tags: tags,
        report_type: tags[0] || "other",
        onclick_function_name: parsed.functionName,
        onclick_arg_count: parsed.args.length,
        has_print_report_arg: parsed.functionName === "printReport" && parsed.args.length === 1,
        source_host_path: safeHostPath(baseUrl || "")
      });
    });
    return selectLatestRows(rows);
  }

  function discoverSetPdfTemplate(doc) {
    const best = bestReportDocument(doc || root.document);
    return getSafeSetPdfTemplate(best.doc);
  }

  function getSafeSetPdfTemplate(doc) {
    const frame = (doc || root.document).querySelector("iframe#setPdf");
    const src = frame ? frame.getAttribute("src") || "" : "";
    if (!src) return null;
    const resolved = resolveUrl(src, root.location && root.location.href);
    try {
      const parsed = new URL(resolved);
      const names = Array.from(parsed.searchParams.keys());
      const modeParamName = names.find((name) => /^hmode$/i.test(name) && parsed.searchParams.get(name) === "PRINTREPORT") || "";
      const argumentParameterName = names.find((name) => /^filename$/i.test(name)) || "";
      if (!NIMS_ALLOWED_HOSTS.has(parsed.hostname) || !modeParamName || !argumentParameterName) return null;
      return {
        discovered: true,
        endpoint: `${parsed.hostname}${parsed.pathname}`,
        origin: parsed.origin,
        pathname: parsed.pathname,
        queryParamNames: names,
        modeParamName,
        modeParamValue: "PRINTREPORT",
        argumentParameterName
      };
    } catch {
      return null;
    }
  }

  function rowsFromBestFrame(doc) {
    return bestReportDocument(doc || root.document).rows;
  }

  function clickFirstReportForMode(mode, doc) {
    const best = bestReportDocument(doc || root.document);
    const rowInfo = selectRowsForMode(best.rows, mode || "test_direct")[0];
    if (!rowInfo) return { ok: false, error: "No View Report button found for row" };
    const row = findReportRow(rowInfo, best.doc);
    if (!row) return { ok: false, error: "No View Report button found for row" };
    const button = Array.from(row.querySelectorAll("[onclick]")).find((node) => {
      const parsed = parseFunctionArgs(node.getAttribute("onclick") || "");
      return parsed.functionName === "printReport" && parsed.args.length === 1;
    });
    if (!button) return { ok: false, error: "No View Report button found for row" };
    button.click();
    return { ok: true, row: rowInfo };
  }

  function transientPayloadForRow(rowInfo, doc) {
    const best = bestReportDocument(doc || root.document);
    return getTransientReportPayload(rowInfo, best.doc);
  }

  function getTransientReportPayload(rowInfo, doc) {
    const row = findReportRow(rowInfo, doc || root.document);
    if (!row) return { ok: false, error: "No View Report button found for row" };
    const button = Array.from(row.querySelectorAll("[onclick]")).find((node) => {
      const parsed = parseFunctionArgs(node.getAttribute("onclick") || "");
      return parsed.functionName === "printReport" && parsed.args.length === 1;
    });
    if (!button) return { ok: false, error: "No View Report button found for row" };
    const parsed = parseFunctionArgs(button.getAttribute("onclick") || "");
    return { ok: true, row: rowInfo, transientPrintReportArg: parsed.args[0] || "" };
  }

  function buildReportUrl(template, transientArg) {
    if (!template || !template.origin || !template.pathname || !template.argumentParameterName) return "";
    const url = new URL(template.pathname, template.origin);
    url.searchParams.set(template.modeParamName || "hmode", template.modeParamValue || "PRINTREPORT");
    url.searchParams.set(template.argumentParameterName, transientArg || "");
    return url.href;
  }

  function selectRowsForMode(rows, mode) {
    const sorted = selectLatestRows(rows || []);
    if (mode === "bulk_full") return sorted;
    if (mode === "bulk_cultures_only") return sorted.filter((row) => (row.report_tags || []).includes("culture"));
    if (mode === "test_direct") return sorted.filter((row) => (row.report_tags || []).includes("cbc")).slice(0, 1).concat(sorted.slice(0, 1)).slice(0, 1);
    const selected = [];
    const counts = { cbc: 0, combined: 0 };
    for (const row of sorted) {
      if (selected.length >= 20) break;
      const tags = row.report_tags || [];
      if (tags.includes("culture") || tags.includes("inflammatory")) selected.push(row);
      else if (tags.includes("cbc") && counts.cbc < 3) {
        counts.cbc += 1;
        selected.push(row);
      } else if ((tags.includes("rft") || tags.includes("lft") || tags.includes("electrolytes")) && counts.combined < 3) {
        counts.combined += 1;
        selected.push(row);
      }
    }
    return selected;
  }

  function parseFunctionArgs(onclick) {
    const match = String(onclick || "").match(/([A-Za-z_$][\w$]*)\s*\(([\s\S]*)\)/);
    if (!match) return { functionName: "", args: [] };
    return { functionName: match[1], args: splitArgs(match[2]).map(unquoteArg) };
  }

  function splitArgs(text) {
    const args = [];
    let current = "";
    let quote = "";
    for (let i = 0; i < String(text || "").length; i += 1) {
      const char = text[i];
      if (quote) {
        current += char;
        if (char === quote && text[i - 1] !== "\\") quote = "";
      } else if (char === "'" || char === '"') {
        quote = char;
        current += char;
      } else if (char === ",") {
        args.push(current.trim());
        current = "";
      } else {
        current += char;
      }
    }
    if (current.trim()) args.push(current.trim());
    return args;
  }

  function unquoteArg(value) {
    const text = String(value || "").trim();
    if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith('"') && text.endsWith('"'))) return text.slice(1, -1);
    return text;
  }

  function safeHostPath(url) {
    try {
      const parsed = new URL(url || "");
      return `${parsed.hostname}${parsed.pathname}`;
    } catch {
      return "";
    }
  }

  function inferReportTags(text) {
    const lower = String(text || "").toLowerCase();
    const tags = [];
    if (/culture|sensitivity|microbiology|organism|no growth/.test(lower)) tags.push("culture");
    if (/cbc|hemogram|blood count|hemoglobin|haemoglobin|platelet|tlc|wbc/.test(lower)) tags.push("cbc");
    if (/rft|renal|urea|creatinine/.test(lower)) tags.push("rft");
    if (/electrolyte|sodium|potassium|chloride/.test(lower)) tags.push("electrolytes");
    if (/lft|liver|bilirubin|sgot|sgpt|ast|alt|albumin/.test(lower)) tags.push("lft");
    if (/crp|c reactive protein|procalcitonin/.test(lower)) tags.push("inflammatory");
    return tags.length ? Array.from(new Set(tags)) : ["other"];
  }

  function selectLatestRows(rows) {
    return [...rows].sort((a, b) => parseDateValue(b.date_sent) - parseDateValue(a.date_sent));
  }

  function parseDateValue(value) {
    const month = { jan: 0, feb: 1, mar: 2, apr: 3, may: 4, jun: 5, jul: 6, aug: 7, sep: 8, oct: 9, nov: 10, dec: 11 };
    const match = String(value || "").match(/^(\d{1,2})-([A-Za-z]{3})-(\d{2,4})$/);
    return match ? Date.UTC(Number(match[3]) < 100 ? 2000 + Number(match[3]) : Number(match[3]), month[match[2].toLowerCase()], Number(match[1])) : 0;
  }

  function findReportRow(rowInfo, doc) {
    const rows = Array.from((doc || root.document).querySelectorAll("tr"));
    const index = Number(rowInfo && rowInfo.row_index);
    return Number.isFinite(index) ? rows[index] : null;
  }

  function guessReportName(cells, text) {
    return cells.find((cell) => /cbc|blood|renal|rft|liver|lft|culture|electrolyte|crp|procalcitonin/i.test(cell)) || text.slice(0, 80);
  }

  function guessDate(cells, text) {
    return cells.find((cell) => /\b\d{1,2}-[A-Za-z]{3}-\d{2,4}\b/.test(cell)) || (text.match(/\b\d{1,2}-[A-Za-z]{3}-\d{2,4}\b/) || [""])[0];
  }

  function guessDepartment(cells) {
    return cells.find((cell) => /pathology|microbiology|biochemistry|hematology|radiology/i.test(cell)) || "";
  }

  function resolveUrl(value, baseUrl) {
    try {
      return new URL(value || "", baseUrl || root.location.href).href;
    } catch {
      return "";
    }
  }

  function textOf(node) {
    return node.innerText || node.textContent || "";
  }

  function compactText(value) {
    return String(value || "").replace(/\s+/g, " ").trim();
  }

  const api = { diagnosePage, collectFrames, rowsFromBestFrame, extractReportRows, discoverSetPdfTemplate, getTransientReportPayload, transientPayloadForRow, clickFirstReportForMode, buildReportUrl, selectRowsForMode, parseFunctionArgs, safeHostPath, NIMS_PAGE_STAGE, accessibleDocumentsRecursive, detectNimsPageStage, detectCurrentDocumentStage, getCurrentDocumentNavigationDiagnostic, navigateCurrentDocumentStep, findInvestigationModuleTarget, findCrWiseReportMenuTarget, navigateToCrWiseReports };
  root.NimsReportCore = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : window);
