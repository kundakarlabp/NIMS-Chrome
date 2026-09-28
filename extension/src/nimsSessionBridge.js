(function () {
  'use strict';
  if (window.__KBP_NIMS_SESSION_BRIDGE_INSTALLED__) return;
  window.__KBP_NIMS_SESSION_BRIDGE_INSTALLED__ = true;

  let notifyTimer = null;

  function text(node) {
    return String((node && (node.innerText || node.textContent || node.value)) || '')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function visible(node) {
    if (!node || node.hidden) return false;
    try {
      const style = window.getComputedStyle(node);
      return style.display !== 'none' && style.visibility !== 'hidden';
    } catch {
      return true;
    }
  }

  function findCrInput() {
    const inputs = Array.from(document.querySelectorAll('input,textarea'));
    return inputs.find(input => (
      !input.disabled
      && !input.readOnly
      && String(input.type || '').toLowerCase() !== 'hidden'
      && /\bpatcrno\b/i.test([input.id, input.name].join(' '))
    )) || inputs.find(input => (
      !input.disabled
      && !input.readOnly
      && String(input.type || '').toLowerCase() !== 'hidden'
      && /\bcr\s*(?:no|number)?\b|crno|crnum/i.test(
        [input.id, input.name, input.placeholder, input.title].join(' ')
      )
    )) || null;
  }

  function reportRows() {
    const core = window.NimsReportCore;
    if (!core || typeof core.extractReportRows !== 'function') return [];
    try {
      return core.extractReportRows(document, location.href) || [];
    } catch {
      return [];
    }
  }

  function reportRowsWithTokens() {
    const core = window.NimsReportCore;
    if (!core) return [];
    try {
      if (typeof core.selectRowsForModeFromDoc === 'function') {
        return core.selectRowsForModeFromDoc('bulk_full', document) || [];
      }
    } catch {}
    return reportRows();
  }

  function patientIdentity() {
    let crNo = '';
    const input = findCrInput();
    if (input && /patcrno/i.test(String(input.name || input.id || ''))) {
      crNo = String(input.value || '').replace(/\D/g, '');
    }

    let name = '';
    const cells = Array.from(document.querySelectorAll('td,th,label,span,div')).slice(0, 2000);
    for (const cell of cells) {
      const label = text(cell);
      if (!name && /^patient\s*name\s*:?$/i.test(label) && cell.nextElementSibling) {
        name = text(cell.nextElementSibling).replace(/^:\s*/, '').slice(0, 120);
      }
      if (!crNo && /^(?:cr\s*(?:no|number)|patient\s*cr)\s*:?$/i.test(label) && cell.nextElementSibling) {
        crNo = text(cell.nextElementSibling).replace(/\D/g, '');
      }
      if (name && crNo) break;
    }
    return { name, crNo };
  }

  function probe() {
    const href = String(location.href || '');
    const body = text(document.body).slice(0, 12000);
    const loginForm = Boolean(document.querySelector('input[type="password"]'))
      && /login|sign\s*in|captcha|user\s*name/i.test(body);
    const sessionExpired = /session\s*(?:has\s*)?expired|invalid\s*session|please\s*login\s*again|session\s*timeout/i.test(body);
    const rows = reportRows();
    const crFieldReady = Boolean(findCrInput());
    const protectedModule = /\/HISInvestigationG5\//i.test(href)
      || /\/AHIMSG5\/hislogin\/transactions\//i.test(href)
      || crFieldReady
      || rows.length > 0
      || /\blog\s*out\b/i.test(body);

    return {
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
      try {
        chrome.runtime.sendMessage({
          type: 'NIMS_SESSION_STATE',
          state: probe()
        }).catch(() => {});
      } catch {}
    }, 150);
  }

  function openCrWise() {
    const core = window.NimsReportCore;
    if (!core) return { ok: false, error: 'NIMS navigation core is not ready.' };

    try {
      if (typeof core.openCrWiseResultsDirect === 'function') {
        const direct = core.openCrWiseResultsDirect(document);
        if (direct && direct.ok) return direct;
      }
    } catch {}
    try {
      if (typeof core.navigateCurrentDocumentStep === 'function') {
        const step = core.navigateCurrentDocumentStep(document);
        if (step && step.ok) return step;
      }
    } catch {}
    try {
      if (typeof core.navigateToCrWiseReports === 'function') {
        return core.navigateToCrWiseReports(document);
      }
    } catch {}

    return {
      ok: false,
      error: 'CR-wise results navigation is not ready in this frame.'
    };
  }

  function setInputValue(input, value) {
    try {
      const prototype = Object.getPrototypeOf(input);
      const descriptor = prototype && Object.getOwnPropertyDescriptor(prototype, 'value');
      if (descriptor && typeof descriptor.set === 'function') {
        descriptor.set.call(input, value);
      } else {
        input.value = value;
      }
    } catch {
      input.value = value;
    }

    ['input', 'change', 'blur'].forEach(name => {
      try {
        input.dispatchEvent(new Event(name, { bubbles: true }));
      } catch {}
    });
  }

  function submitCrNumber(raw) {
    const crNo = String(raw || '').replace(/\D/g, '');
    if (!/^\d{15}$/.test(crNo)) {
      return { ok: false, error: 'Enter the 15-digit NIMS CR number.' };
    }

    const input = findCrInput();
    if (!input) return { ok: false, error: 'CR number field is not ready.' };
    setInputValue(input, crNo);

    const form = input.form || (input.closest && input.closest('form'));
    if (!form) return { ok: false, error: 'Unable to find the CR result form.' };

    const hmode = form.querySelector('input[name="hmode"],input#hmode');
    if (hmode) hmode.value = 'SHOWPATDETAILS';

    const actions = Array.from(
      form.querySelectorAll('button,input[type=button],input[type=submit],a')
    );
    const action = actions.find(node => (
      visible(node)
      && /^(?:go|search|submit|fetch\s*results?)$/i.test(text(node))
    )) || actions.find(node => (
      visible(node)
      && /\b(?:go|search|submit|fetch)\b/i.test(text(node))
    ));

    if (action) {
      action.click();
      return { ok: true };
    }

    try {
      if (typeof form.requestSubmit === 'function') form.requestSubmit();
      else form.submit();
      return { ok: true };
    } catch {
      return { ok: false, error: 'Unable to submit CR number.' };
    }
  }

  function extractDashboardData() {
    const rows = reportRowsWithTokens();
    const reports = rows.map((row, index) => ({
      id: row.report_id || ('browser-report-' + index),
      title: row.report_name || 'Investigation report',
      date: row.date_sent || '',
      department: row.department || '',
      token: String(
        row.transientPrintReportArg
        || row.transient_print_report_arg
        || ''
      )
    }));

    return {
      ok: true,
      patient: patientIdentity(),
      reports,
      reportCount: reports.length
    };
  }

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (!message || !/^NIMS_BRIDGE_/.test(String(message.type || ''))) return false;

    if (message.type === 'NIMS_BRIDGE_PROBE') {
      sendResponse({ ok: true, state: probe() });
      return false;
    }
    if (message.type === 'NIMS_BRIDGE_OPEN_CR') {
      sendResponse(openCrWise());
      setTimeout(scheduleNotify, 500);
      return false;
    }
    if (message.type === 'NIMS_BRIDGE_SUBMIT_CR') {
      sendResponse(submitCrNumber(message.crNo));
      setTimeout(scheduleNotify, 800);
      return false;
    }
    if (message.type === 'NIMS_BRIDGE_EXTRACT_DASHBOARD_DATA') {
      sendResponse(extractDashboardData());
      return false;
    }
    return false;
  });

  if (document.documentElement) {
    const observer = new MutationObserver(scheduleNotify);
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true
    });
  }
  scheduleNotify();
})();
