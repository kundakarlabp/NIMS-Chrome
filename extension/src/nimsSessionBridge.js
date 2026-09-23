(function () {
  "use strict";
  if (window.__KBP_NIMS_SESSION_BRIDGE_INSTALLED__) return;
  window.__KBP_NIMS_SESSION_BRIDGE_INSTALLED__ = true;

  let notifyTimer = null;

  function text(node) {
    return String((node && (node.innerText || node.textContent || node.value)) || "").replace(/\s+/g, " ").trim();
  }

  function visible(node) {
    if (!node || node.hidden) return false;
    try {
      const style = window.getComputedStyle(node);
      return style.display !== "none" && style.visibility !== "hidden";
    } catch {
      return true;
    }
  }

  function findCrInput() {
    const inputs = Array.from(document.querySelectorAll("input,textarea"));
    return inputs.find(input => !input.disabled && !input.readOnly && String(input.type || "").toLowerCase() !== "hidden" && /\bpatcrno\b/i.test([input.id,input.name].join(" ")))
      || inputs.find(input => !input.disabled && !input.readOnly && String(input.type || "").toLowerCase() !== "hidden" && /\bcr\s*(?:no|number)?\b|crno|crnum/i.test([input.id,input.name,input.placeholder,input.title].join(" ")))
      || null;
  }

  function reportRows() {
    try {
      const core = window.NimsReportCore;
      if (core && typeof core.extractReportRows === "function") return core.extractReportRows(document, location.href) || [];
      if (window.NimsFastSummaryUtils && typeof window.NimsFastSummaryUtils.extractReportRows === "function") {
        return window.NimsFastSummaryUtils.extractReportRows(document, location.href) || [];
      }
    } catch {}
    return [];
  }

  function patientIdentity() {
    let crNo = "";
    const input = findCrInput();
    if (input && /patcrno/i.test(String(input.name || input.id || ""))) crNo = String(input.value || "").replace(/\D/g, "");
    let name = "";
    const cells = Array.from(document.querySelectorAll("td,th,label,span,div")).slice(0, 2000);
    for (const cell of cells) {
      const label = text(cell);
      if (!name && /^patient\s*name\s*:?$/i.test(label)) {
        const sibling = cell.nextElementSibling;
        if (sibling) name = text(sibling).replace(/^:\s*/, "").slice(0, 120);
      }
      if (!crNo && /^(?:cr\s*(?:no|number)|patient\s*cr)\s*:?$/i.test(label)) {
        const sibling = cell.nextElementSibling;
        if (sibling) crNo = text(sibling).replace(/\D/g, "");
      }
      if (name && crNo) break;
    }
    return { name, crNo };
  }

  function probe() {
    const href = String(location.href || "");
    const body = text(document.body).slice(0, 12000);
    const loginForm = Boolean(document.querySelector('input[type="password"]')) && /login|sign\s*in|captcha|user\s*name/i.test(body);
    const sessionExpired = /session\s*(?:has\s*)?expired|invalid\s*session|please\s*login\s*again|session\s*timeout/i.test(body);
    const crFieldReady = Boolean(findCrInput());
    const rows = reportRows();
    const protectedModule = /\/HISInvestigationG5\//i.test(href)
      || /\/AHIMSG5\/hislogin\/transactions\//i.test(href)
      || crFieldReady
      || rows.length > 0
      || /\blog\s*out\b/i.test(body);
    return {
      href,
      loginForm,
      authenticated: Boolean(!loginForm && !sessionExpired && protectedModule),
      sessionExpired,
      crFieldReady,
      reportRows: rows.length,
      patient: patientIdentity()
    };
  }

  function scheduleNotify() {
    clearTimeout(notifyTimer);
    notifyTimer = setTimeout(() => {
      try { chrome.runtime.sendMessage({ type: "NIMS_SESSION_STATE", state: probe() }).catch(() => {}); } catch {}
    }, 120);
  }

  function openCrWise() {
    const core = window.NimsReportCore;
    if (!core) return { ok: false, error: "NIMS navigation core is not ready." };
    try {
      if (typeof core.openCrWiseResultsDirect === "function") {
        const direct = core.openCrWiseResultsDirect(document);
        if (direct && direct.ok) return direct;
      }
    } catch {}
    try {
      if (typeof core.navigateCurrentDocumentStep === "function") {
        const step = core.navigateCurrentDocumentStep(document);
        if (step && step.ok) return step;
      }
    } catch {}
    try {
      if (typeof core.navigateToCrWiseReports === "function") return core.navigateToCrWiseReports(document);
    } catch {}
    return { ok: false, error: "CR-wise results navigation is not ready in this frame." };
  }

  function setInputValue(input, value) {
    try {
      const proto = Object.getPrototypeOf(input);
      const descriptor = proto && Object.getOwnPropertyDescriptor(proto, "value");
      if (descriptor && typeof descriptor.set === "function") descriptor.set.call(input, value);
      else input.value = value;
    } catch {
      input.value = value;
    }
    ["input","change","blur"].forEach(name => {
      try { input.dispatchEvent(new Event(name, { bubbles: true })); } catch {}
    });
  }

  function submitCrNumber(raw) {
    const crNo = String(raw || "").replace(/\D/g, "");
    if (crNo.length < 6) return { ok: false, error: "Invalid CR number." };
    const input = findCrInput();
    if (!input) return { ok: false, error: "CR number field is not ready." };
    setInputValue(input, crNo);
    const form = input.form || (input.closest && input.closest("form"));
    if (form) {
      const hmode = form.querySelector('input[name="hmode"],input#hmode');
      if (hmode && !String(hmode.value || "").trim()) hmode.value = "SHOWPATDETAILS";
      const actions = Array.from(form.querySelectorAll("button,input[type=button],input[type=submit],a"));
      const action = actions.find(node => visible(node) && /^(?:go|search|submit|fetch\s*results?)$/i.test(text(node)))
        || actions.find(node => visible(node) && /\b(?:go|search|submit|fetch)\b/i.test(text(node)));
      if (action) {
        action.click();
        return { ok: true, action: "clicked_cr_submit", crNo };
      }
      try {
        if (typeof form.requestSubmit === "function") form.requestSubmit();
        else form.submit();
        return { ok: true, action: "submitted_cr_form", crNo };
      } catch {}
    }
    return { ok: false, error: "Unable to submit CR number." };
  }

  function extractDashboardData() {
    const core = window.NimsReportCore;
    const rows = reportRows();
    let template = null;
    try { if (core && typeof core.discoverSetPdfTemplate === "function") template = core.discoverSetPdfTemplate(document); } catch {}
    const reports = rows.map((row, index) => {
      let url = row.source_url || row.href || "";
      try {
        if (!url && core && template && typeof core.transientPayloadForRow === "function" && typeof core.buildReportUrl === "function") {
          const payload = core.transientPayloadForRow(row, document);
          const arg = payload && (payload.transientPrintReportArg || payload.transient_print_report_arg || payload.transient_print_report_arg);
          if (arg) url = core.buildReportUrl(template, arg) || "";
        }
      } catch {}
      return {
        id: row.report_id || ("report-" + index),
        title: row.report_name || "Investigation report",
        date: row.date_sent || "",
        department: row.department || "",
        url
      };
    });
    return { ok: true, patient: patientIdentity(), reports, reportCount: rows.length };
  }

  async function readSummaryState() {
    const data = await chrome.storage.local.get("nimsFastSummaryState");
    return data.nimsFastSummaryState || null;
  }

  async function mappingIsValidated() {
    try {
      const response = await chrome.runtime.sendMessage({ type: "NIMS_GET_MAPPING_SUMMARY" });
      const summary = response && response.summary;
      return Boolean(
        response && response.ok
        && summary
        && summary.status === "validated"
        && summary.lastTestDirectFetch
        && summary.lastTestDirectFetch.ok === true
        && summary.lastTestDirectFetch.parsed === true
      );
    } catch {
      return false;
    }
  }

  async function prepareDirectMapping() {
    if (await mappingIsValidated()) return { ok: true, reused: true };
    if (!window.NimsFastSummary || typeof window.NimsFastSummary.discoverMapping !== "function") {
      return { ok: false, error: "NIMS report mapping discovery is not ready." };
    }
    const discovery = await window.NimsFastSummary.discoverMapping();
    if (!discovery || discovery.ok === false) {
      return { ok: false, error: (discovery && discovery.error) || "Unable to learn the NIMS report request." };
    }
    await window.NimsFastSummary.runSummary("test_direct");
    const testState = await readSummaryState();
    if (!testState || /^Error:/i.test(String(testState.progress || ""))) {
      return {
        ok: false,
        error: testState && testState.progress
          ? testState.progress.replace(/^Error:\s*/i, "")
          : "NIMS direct report validation failed."
      };
    }
    if (!(await mappingIsValidated())) {
      const firstError = testState.parsedReports && testState.parsedReports[0] && testState.parsedReports[0].errors
        ? testState.parsedReports[0].errors[0]
        : "";
      return { ok: false, error: firstError || "NIMS report mapping could not be validated." };
    }
    return { ok: true, reused: false };
  }

  async function runSummary(mode) {
    if (!window.NimsFastSummary || typeof window.NimsFastSummary.runSummary !== "function") {
      return { ok: false, error: "NIMS result processor is not ready." };
    }
    const requestedMode = mode || "bulk_full";
    if (requestedMode === "bulk_fast" || requestedMode === "bulk_full" || requestedMode === "bulk_cultures_only") {
      const prepared = await prepareDirectMapping();
      if (!prepared.ok) return prepared;
    }
    await window.NimsFastSummary.runSummary(requestedMode);
    const state = await readSummaryState();
    if (!state || /^Error:/i.test(String(state.progress || ""))) {
      return { ok: false, error: state && state.progress ? state.progress.replace(/^Error:\s*/i, "") : "NIMS result processing failed.", state };
    }
    return { ok: true, state };
  }

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (!message || !/^NIMS_BRIDGE_/.test(String(message.type || ""))) return false;
    if (message.type === "NIMS_BRIDGE_PROBE") {
      sendResponse({ ok: true, state: probe() });
      return false;
    }
    if (message.type === "NIMS_BRIDGE_OPEN_CR") {
      sendResponse(openCrWise());
      setTimeout(scheduleNotify, 500);
      return false;
    }
    if (message.type === "NIMS_BRIDGE_SUBMIT_CR") {
      sendResponse(submitCrNumber(message.crNo));
      setTimeout(scheduleNotify, 800);
      return false;
    }
    if (message.type === "NIMS_BRIDGE_EXTRACT_DASHBOARD_DATA") {
      sendResponse(extractDashboardData());
      return false;
    }
    if (message.type === "NIMS_BRIDGE_RUN_SUMMARY") {
      runSummary(message.mode || "bulk_full").then(sendResponse);
      return true;
    }
    return false;
  });

  if (document.documentElement) {
    const observer = new MutationObserver(scheduleNotify);
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }
  scheduleNotify();
})();