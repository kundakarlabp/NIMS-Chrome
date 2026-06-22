(function (root) {
  const NIMS_ALLOWED_HOSTS = new Set(["nimsts.edu.in", "www.nimsts.edu.in"]);
  const CR_WISE_MENU_ID = "Cr_No_Wise_Result_Report_Printing_New";
  const CR_WISE_ENDPOINT = "/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt";
  const CR_WISE_MENU_LABEL = "Cr No Wise Result Report Printing New";
  const MENU_FRAME_ID = "frmMainMenu";
  const REPORT_FRAME_ID = "Cr No Wise Result Report Printing New_iframe";
  const NIMS_PAGE_STAGE = Object.freeze({ LOGIN: "login", HOME: "home", INVESTIGATION_MENU: "investigation_menu", CR_SEARCH: "cr_search", REPORT_LIST: "report_list", REPORT_VIEWER: "report_viewer", SESSION_EXPIRED: "session_expired", UNKNOWN: "unknown" });
  const NAVIGATION_ACTION_COOLDOWN_MS = 4500;
  const NAVIGATION_UNCHANGED_CHECKS_REQUIRED = 3;

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
    const cr = hasCrSearchEvidence(doc, safeUrl);
    if (cr.present) return { stage: NIMS_PAGE_STAGE.CR_SEARCH, safePath: safeUrl || "", evidence: cr.evidence };
    if (findCrWiseReportMenuTargetInDocument(doc).ok) return { stage: NIMS_PAGE_STAGE.INVESTIGATION_MENU, safePath: safeUrl || "", evidence: ["cr_wise_menu_id"] };
    if (findInvestigationModuleTargetInDocument(doc).ok || plausibleHomeWithMenuFunction(doc) || hasLoggedInShellEvidence(doc)) return { stage: NIMS_PAGE_STAGE.HOME, safePath: safeUrl || "", evidence: ["investigation_module"] };
    if (hasLoginEvidence(doc, safeUrl)) return { stage: NIMS_PAGE_STAGE.LOGIN, safePath: safeUrl || "", evidence: ["login_form"] };
    return { stage: NIMS_PAGE_STAGE.UNKNOWN, safePath: safeUrl || "", evidence };
  }

  function hasCrSearchEvidence(doc, safeUrl) {
    const evidence = [];
    const href = String(safeUrl || safeDocumentHref(doc)).toLowerCase();
    if (href.includes("viewcrnowisereportprocess.cnt")) evidence.push("target_endpoint");
    const forms = Array.from(doc.querySelectorAll("form"));
    const inputs = Array.from(doc.querySelectorAll("input, textarea, select"));
    const crInputs = inputs.filter((el) => /patcrno|cr\s*(no|number)|crno|crnumber/i.test(`${el.id || ""} ${el.name || ""}`));
    const livePatCrNo = crInputs.some((el) => /patcrno/i.test(`${el.id || ""} ${el.name || ""}`) && (!el.maxLength || Number(el.maxLength) === 15 || String(el.getAttribute("maxlength") || "") === "15"));
    const form = forms.find((el) => /viewExternalInvFB/i.test(el.name || el.id || "") || String(el.getAttribute("action") || "").includes(CR_WISE_ENDPOINT));
    const hmode = inputs.some((el) => /hmode/i.test(el.name || el.id || "") && String(el.type || "").toLowerCase() === "hidden");
    const labelText = compactText(Array.from(doc.querySelectorAll("label, th, td, h1, h2, h3, legend, span, div")).map(textOf).join(" "));
    if (crInputs.length) evidence.push(livePatCrNo ? "pat_cr_no_input" : "cr_input_present");
    if (form) evidence.push("cr_form");
    if (hmode) evidence.push("hmode_field");
    if (/CR\s*No|CR\s*Number|CR\s*Wise\s*Result\s*Report\s*Printing/i.test(labelText)) evidence.push("cr_context_present");
    // A patCrNo input that has actually rendered is the essential, reliable
    // signal. The endpoint URL alone is NOT a ready CR form: the dynamic iframe
    // receives the URL before its form has loaded, so accepting target_endpoint
    // here would declare "CR page ready" while the frame is still blank.
    const present = Boolean(livePatCrNo && (Boolean(form) || hmode || evidence.includes("cr_context_present")));
    return { present, evidence };
  }

  function hasLoginEvidence(doc, safeUrl) {
    if (hasLoggedInShellEvidence(doc)) return false;
    const password = doc.querySelector('input[type="password"]');
    const user = doc.querySelector('input[name*="user" i], input[id*="user" i], input[name*="login" i], input[id*="login" i], input[type="text"]');
    const formText = compactText(Array.from(doc.querySelectorAll('form, label, button, input[type="submit"]')).map((el) => `${textOf(el)} ${el.value || ""}`).join(" "));
    return Boolean(password && user && /login|sign\s*in|password|user/i.test(formText));
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
      return label === "Investigation" && label.length <= 24 && !/Enquiry|Report/i.test(label) && isUsableClickable(el);
    });
    return fallback ? { ok: true, method: "exact_text", element: fallback } : { ok: false, reason: "investigation_module_not_found" };
  }

  function plausibleHomeWithMenuFunction(doc) { return Boolean(doc.defaultView && typeof doc.defaultView.menuSelected === "function") && /module|menu|home/i.test(compactText(textOf(doc.body || doc)).slice(0, 1000)); }

  // Recognise the authenticated e-Sushrut G-5 shell by stable visible markers,
  // independent of the exact menu DOM (which varies and broke onclick-based
  // home detection). The live shell shows "Home Menu", the module menu bar, and
  // a "Welcome,"/e-Sushrut banner — none of which appear on the login page.
  // Distinctive e-Sushrut shell tokens, compared after stripping ALL whitespace
  // so letter-spaced labels in the live DOM ("O P D", "M I S Reports", "H E M S")
  // still match ("opd", "misreports", "hems").
  const SHELL_TOKENS = ["homemenu", "registration", "investigation", "misreports", "tariffsearch", "inventory", "esushrut", "cashinhand", "opd", "adt", "pis", "ipd", "hems"];
  // Aggregate visible text across the whole accessible frame tree. e-Sushrut G-5
  // is a frameset: the header ("Welcome"/e-Sushrut), the module bar and "Home
  // Menu" live in SEPARATE same-origin frames, so no single document carries all
  // the evidence. Collecting the subtree lets the top document recognise the
  // shell the way the extension's all_frames injection does per-frame.
  function collectFrameTreeText(doc) {
    let docs;
    try { docs = accessibleDocumentsRecursive(doc || root.document); } catch { docs = [{ doc }]; }
    return docs.map((entry) => { try { return textOf((entry.doc && entry.doc.body) || entry.doc || ""); } catch { return ""; } }).join("  ");
  }
  function hasReadableLoginForm(doc) {
    let docs;
    try { docs = accessibleDocumentsRecursive(doc || root.document); } catch { docs = [{ doc }]; }
    return docs.some((entry) => {
      try {
        const d = entry.doc; if (!d) return false;
        if (!d.querySelector('input[type="password"]')) return false;
        const user = d.querySelector('input[type="text"], input[name*="user" i], input[id*="user" i], input[name*="login" i], input[id*="login" i]');
        const txt = compactText(textOf(d.body || d)).toLowerCase();
        return Boolean(user) && /login|sign\s*in|password/.test(txt);
      } catch { return false; }
    });
  }
  function hasUnreadableFrames(doc) {
    let frames = [];
    try { frames = Array.from((doc || root.document).querySelectorAll("iframe, frame")); } catch { return false; }
    if (!frames.length) return false;
    return frames.some((frame) => {
      try { const cd = frame.contentDocument; if (!cd || !cd.body) return true; return compactText(textOf(cd.body)).length === 0; } catch { return true; }
    });
  }
  function onNimsContext(doc) {
    const href = safeDocumentHref(doc) || (root.location && root.location.href) || "";
    try { return NIMS_ALLOWED_HOSTS.has(new URL(href).hostname); } catch { return /nimsts\.edu\.in/i.test(href); }
  }
  function hasLoggedInShellEvidence(doc) {
    const text = compactText(collectFrameTreeText(doc)).toLowerCase();
    const squished = text.replace(/[\s\u00a0]+/g, "");
    const hits = SHELL_TOKENS.reduce((n, token) => (squished.includes(token) ? n + 1 : n), 0);
    const welcome = /welcome/.test(text) || /nizam.?s\s+institute\s+of\s+medical\s+sciences/.test(text);
    if (hits >= 4 || (welcome && hits >= 2)) return true;
    // Cross-origin / unintrospectable frameset on a NIMS context with no readable
    // login form: treat as the logged-in shell so navigation can jump straight to
    // the CR endpoint (the session cookie is valid; if it is not, NIMS redirects
    // to login and the next poll re-detects login). The stage cascade only reaches
    // this check after report/cr/investigation stages, so it never overrides a
    // page that is already useful.
    if (onNimsContext(doc) && hasUnreadableFrames(doc) && !hasReadableLoginForm(doc)) return true;
    return false;
  }

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
    const candidates = Array.from(doc.querySelectorAll("[onclick], a, button, [role='button']")).filter((el) => isUsableClickable(el));
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
    return navigateNimsContract(doc || root.document);
  }

  // Drive navigation through the real e-Sushrut contract:
  //   top menuSelected("Investigation", true)  -> refreshes #frmMainMenu
  //   #frmMainMenu anchor #Cr_No_Wise_Result_Report_Printing_New (callMenu)
  //   -> parent.callMenu attaches the SSO ticket + addTab() builds the result
  //      iframe #"Cr No Wise Result Report Printing New_iframe".
  // Never assign the endpoint URL directly: that bypasses the SSO ticket, NIMS
  // URL validation and addTab(), landing on a blank/login/wrong frame.
  function navigateNimsContract(doc) {
    const topDoc = resolveTopDocument(doc);
    const topWindow = (topDoc && topDoc.defaultView) || root.window || root;

    if (detectSessionExpiredInTree(topDoc)) return navigationResult(false, NIMS_PAGE_STAGE.SESSION_EXPIRED, "none", false, "session_expired");

    let entries;
    try { entries = accessibleDocumentsRecursive(topDoc); } catch { entries = [{ doc: topDoc }]; }
    const docs = entries.map((entry) => entry.doc).filter(Boolean);

    // 1. Result list (genuine printReport rows) has the highest priority: the CR
    //    form and the result list share URL and form name, so only DOM evidence
    //    separates them.
    if (docs.some((d) => hasGenuineViewReportRows(d))) { clearProvisionalNavigation(); return navigationResult(true, NIMS_PAGE_STAGE.REPORT_LIST, "none", true); }
    // 2. CR-number form: a patCrNo input that is not hidden. (Rows already claimed
    //    report_list above, so a remaining patCrNo means the form, not results.)
    if (docs.some((d) => hasVisibleCrNumberForm(d))) { clearProvisionalNavigation(); return navigationResult(true, NIMS_PAGE_STAGE.CR_SEARCH, "none", true); }
    // 2b. A genuine, readable credential form means the session is not authenticated
    //     - respect it before attempting any menu navigation, even if stale shell
    //     elements linger in the DOM.
    if (hasReadableLoginForm(topDoc)) return navigationResult(false, NIMS_PAGE_STAGE.LOGIN, "none", false, "manual_login_required");
    // The exact result iframe exists but has not populated yet: wait, do not click.
    if (frameDocById(topDoc, REPORT_FRAME_ID)) return navigationResult(true, NIMS_PAGE_STAGE.INVESTIGATION_MENU, "waiting_for_report_frame", false);

    // 3. Click the exact CR-wise anchor (preferred in #frmMainMenu). This runs the
    //    native callMenu -> parent.callMenu (SSO ticket) -> addTab() workflow.
    const anchor = findExactCrWiseAnchor(topDoc, docs);
    if (anchor) {
      if (!canPerformNavigationAction(actionCooldownKey(NIMS_PAGE_STAGE.INVESTIGATION_MENU, "clicked_cr_wise_menu"))) return navigationResult(true, NIMS_PAGE_STAGE.INVESTIGATION_MENU, "cooldown", false);
      if (safeClick(anchor)) { rememberProvisionalNavigation(NIMS_PAGE_STAGE.INVESTIGATION_MENU, "clicked_cr_wise_menu"); return navigationResult(true, NIMS_PAGE_STAGE.INVESTIGATION_MENU, "clicked_cr_wise_menu", false); }
    }

    // 4. Menu-frame callMenu when the anchor is not present.
    const menuDoc = frameDocById(topDoc, MENU_FRAME_ID);
    if (menuDoc && menuDoc.defaultView && typeof menuDoc.defaultView.callMenu === "function") {
      try { menuDoc.defaultView.callMenu(CR_WISE_ENDPOINT, CR_WISE_MENU_ID); rememberProvisionalNavigation(NIMS_PAGE_STAGE.INVESTIGATION_MENU, "called_child_menu_function"); return navigationResult(true, NIMS_PAGE_STAGE.INVESTIGATION_MENU, "called_child_menu_function", false); } catch { }
    }

    // 5. Top shell: select Investigation (refreshes #frmMainMenu so the anchor
    //    appears next poll). Cooldown-gated to avoid re-selecting every poll.
    if (topWindow && typeof topWindow.menuSelected === "function") {
      if (!canPerformNavigationAction(actionCooldownKey(NIMS_PAGE_STAGE.HOME, "selected_investigation"))) return navigationResult(true, NIMS_PAGE_STAGE.HOME, "cooldown", false);
      try { topWindow.menuSelected("Investigation", true); rememberProvisionalNavigation(NIMS_PAGE_STAGE.HOME, "selected_investigation"); return navigationResult(true, NIMS_PAGE_STAGE.HOME, "selected_investigation", false); } catch { }
    }

    // 6. Native top callMenu as last resort (still attaches SSO ticket + addTab).
    if (topWindow && typeof topWindow.callMenu === "function") {
      try { topWindow.callMenu(CR_WISE_ENDPOINT, CR_WISE_MENU_LABEL); return navigationResult(true, NIMS_PAGE_STAGE.INVESTIGATION_MENU, "called_top_menu_function", false); } catch { }
    }

    // 7. Only a genuine, readable credential form is manual-login.
    if (hasReadableLoginForm(topDoc)) return navigationResult(false, NIMS_PAGE_STAGE.LOGIN, "none", false, "manual_login_required");
    // 8. Authenticated shell present but native hooks not ready yet: keep waiting.
    if (hasLoggedInShellEvidence(topDoc)) return navigationResult(true, NIMS_PAGE_STAGE.HOME, "waiting_for_shell", false);
    return navigationResult(false, NIMS_PAGE_STAGE.UNKNOWN, "none", false, "navigation_contract_not_found");
  }

  function findExactCrWiseAnchor(topDoc, docs) {
    const menuDoc = frameDocById(topDoc, MENU_FRAME_ID);
    const ordered = menuDoc ? [menuDoc].concat(docs) : docs;
    for (const d of ordered) {
      try { const el = d && d.getElementById && d.getElementById(CR_WISE_MENU_ID); if (el && isUsableClickable(el)) return el; } catch { }
    }
    return null;
  }

  function resolveTopDocument(doc) {
    const current = doc || root.document;
    try { const top = current.defaultView && current.defaultView.top; if (top && top.document) return top.document; } catch { }
    return current;
  }

  function frameDocById(topDoc, id) {
    try { const el = topDoc.getElementById(id); if (!el) return null; try { return el.contentDocument || null; } catch { return null; } } catch { return null; }
  }

  function hasGenuineViewReportRows(doc) {
    if (!doc) return false;
    try { return extractReportRows(doc, safeHostPath(safeDocumentHref(doc))).some((row) => row.onclick_function_name === "printReport"); } catch { return false; }
  }

  function hasVisibleCrNumberForm(doc) {
    if (!doc) return false;
    try {
      const pat = doc.querySelector('input[name="patCrNo"], input[id="patCrNo"]');
      if (!pat) return false;
      if (String(pat.type || "").toLowerCase() === "hidden") return false;
      if (pat.hidden || pat.getAttribute("aria-hidden") === "true") return false;
      const win = doc.defaultView;
      try { const st = win && win.getComputedStyle ? win.getComputedStyle(pat) : null; if (st && (st.display === "none" || st.visibility === "hidden")) return false; } catch { }
      return true;
    } catch { return false; }
  }

  function detectSessionExpiredInTree(topDoc) {
    let docs;
    try { docs = accessibleDocumentsRecursive(topDoc); } catch { docs = [{ doc: topDoc }]; }
    return docs.some((entry) => { try { const text = compactText((entry.doc.body && textOf(entry.doc.body)) || "").toLowerCase(); return /session\s+expired|invalid\s+session|login\s+required|session\s+timeout|timed\s*out/.test(text); } catch { return false; } });
  }

  function navigationResult(ok, stage, action, done, errorCode, extra) {
    const result = { ok: Boolean(ok), stage, action, done: Boolean(done) };
    if (errorCode) result.errorCode = errorCode;
    const state = getNavigationState();
    result.canonicalFallbackAttempted = Boolean(extra && extra.canonicalFallbackAttempted);
    result.transitionObserved = Boolean(done || (state.lastStage && state.lastStage !== stage));
    if (extra && extra.safePath) result.safePath = extra.safePath;
    if (extra && Number.isFinite(extra.frameDepth)) result.frameDepth = extra.frameDepth;
    state.lastStage = stage;
    state.lastAction = action;
    return result;
  }

  function getNavigationState() {
    const scope = root.window || root;
    if (!scope.__NIMS_CR_NAVIGATION_STATE__) scope.__NIMS_CR_NAVIGATION_STATE__ = {};
    return scope.__NIMS_CR_NAVIGATION_STATE__;
  }

  function shouldUseCanonicalFallback(stage, action) {
    const state = getNavigationState();
    return state.provisionalAction === action && state.provisionalStage === stage;
  }

  function rememberProvisionalNavigation(stage, action) {
    const state = getNavigationState();
    state.provisionalStage = stage;
    state.provisionalAction = action;
  }

  function clearProvisionalNavigation() {
    const state = getNavigationState();
    state.provisionalStage = "";
    state.provisionalAction = "";
  }

  function navigateCanonicalCrWiseEndpoint(doc, reason) {
    const target = findCanonicalNavigationDocument(doc || root.document);
    if (!target.ok) return navigationResult(false, NIMS_PAGE_STAGE.INVESTIGATION_MENU, "none", false, target.errorCode || reason || "canonical_endpoint_rejected", { canonicalFallbackAttempted: true });
    try {
      target.win.location.assign(target.url);
      clearProvisionalNavigation();
      return navigationResult(true, NIMS_PAGE_STAGE.INVESTIGATION_MENU, "canonical_endpoint_fallback", false, "", { canonicalFallbackAttempted: true, safePath: safeHostPath(target.url), frameDepth: target.depth });
    } catch {
      return navigationResult(false, NIMS_PAGE_STAGE.INVESTIGATION_MENU, "none", false, "canonical_navigation_failed", { canonicalFallbackAttempted: true, safePath: safeHostPath(target.url), frameDepth: target.depth });
    }
  }

  function findCanonicalNavigationDocument(doc) {
    const accessible = accessibleDocumentsRecursive(doc || root.document).filter((item) => item.visibleThroughAncestors && item.win);
    const ranked = accessible
      .map((item) => ({ item, diagnostic: getCurrentDocumentNavigationDiagnostic(item.doc, item.depth, item.visibleThroughAncestors) }))
      .filter((entry) => entry.diagnostic.stage === NIMS_PAGE_STAGE.INVESTIGATION_MENU || entry.diagnostic.stage === NIMS_PAGE_STAGE.HOME || entry.diagnostic.hasCrWiseMenu || /HISInvestigationG5/i.test(entry.diagnostic.safePath || ""));
    ranked.sort((a, b) => (b.item.depth - a.item.depth) || navigationStageScore(b.diagnostic) - navigationStageScore(a.diagnostic));
    const selected = ranked[0];
    if (!selected) return { ok: false, errorCode: "investigation_context_not_confirmed" };
    // The CR endpoint is an absolute path, so any approved-origin base resolves
    // it. Draw the base from the selected frame, any accessible frame, or the
    // top window, so a frame with an unusable (about:blank / mid-load) href does
    // not block navigation.
    const baseHrefs = [safeDocumentHref(selected.item.doc)]
      .concat(accessible.map((item) => safeDocumentHref(item.doc)))
      .concat([(root.location && root.location.href) || "", safeTopHref()]);
    let resolved = { ok: false, errorCode: "canonical_endpoint_rejected" };
    for (const base of baseHrefs) {
      if (!base) continue;
      const candidate = resolveCanonicalCrWiseUrl(base);
      if (candidate.ok) { resolved = candidate; break; }
    }
    if (!resolved.ok) return resolved;
    return { ok: true, win: selected.item.win, url: resolved.url, depth: selected.item.depth };
  }

  function safeTopHref() {
    try { return (root.window && root.window.top && root.window.top.location && root.window.top.location.href) || ""; } catch { return ""; }
  }

  function resolveCanonicalCrWiseUrl(baseHref) {
    try {
      const url = new URL(CR_WISE_ENDPOINT, baseHref);
      if (url.protocol !== "https:") return { ok: false, errorCode: "canonical_endpoint_rejected" };
      if (!NIMS_ALLOWED_HOSTS.has(url.hostname)) return { ok: false, errorCode: "canonical_endpoint_rejected" };
      if (url.port) return { ok: false, errorCode: "canonical_endpoint_rejected" };
      if (url.pathname !== CR_WISE_ENDPOINT) return { ok: false, errorCode: "canonical_endpoint_rejected" };
      url.search = "";
      url.hash = "";
      return { ok: true, url: url.href };
    } catch {
      return { ok: false, errorCode: "canonical_endpoint_rejected" };
    }
  }

  function navigateCurrentDocumentStep(doc) {
    return navigateNimsContract(doc || root.document);
  }

  function performNavigationTarget(stage, action, target, missingCode) {
    if (!target || !target.ok) return navigationResult(false, stage, "none", false, missingCode);
    const key = actionCooldownKey(stage, action);
    if (!canPerformNavigationAction(key)) return navigationResult(true, stage, "cooldown", false);
    let clicked = false;
    if (target.element) clicked = safeClick(target.element);
    else if (target.win && action === "clicked_investigation_module" && typeof target.win.menuSelected === "function") { target.win.menuSelected("Investigation", true); clicked = true; }
    if (clicked) { rememberProvisionalNavigation(stage, action); return navigationResult(true, stage, action, false); }
    return navigationResult(false, stage, "none", false, "click_failed");
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
    if (style && (style.display === "none" || style.visibility === "hidden" || style.visibility === "collapse" || style.opacity !== "" && Number(style.opacity) === 0)) return false;
    if (!/^(IFRAME|FRAME)$/i.test(element.tagName || "")) {
      try { if (element.getClientRects && element.getClientRects().length === 0) return false; } catch { }
    }
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

  const api = { diagnosePage, collectFrames, rowsFromBestFrame, extractReportRows, discoverSetPdfTemplate, getTransientReportPayload, transientPayloadForRow, clickFirstReportForMode, buildReportUrl, selectRowsForMode, parseFunctionArgs, safeHostPath, NIMS_PAGE_STAGE, accessibleDocumentsRecursive, detectNimsPageStage, detectCurrentDocumentStage, getCurrentDocumentNavigationDiagnostic, navigateCurrentDocumentStep, findInvestigationModuleTarget, findCrWiseReportMenuTarget, resolveCanonicalCrWiseUrl, navigateToCrWiseReports };
  root.NimsReportCore = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : window);
