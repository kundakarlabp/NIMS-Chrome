(() => {
  'use strict';
  if (window.top !== window) return;
  if (window.__KBP_NIMS_UI_BRIDGE__) return;
  window.__KBP_NIMS_UI_BRIDGE__ = true;

  const NIMS_ORIGINS = new Set(['https://nimsts.edu.in', 'https://www.nimsts.edu.in']);
  const UI = {
    trendFrameId: 'Cr No Wise Investigation Trends_iframe',
    menuFrame: '#frmMainMenu',
    crInput: '#patCrNo',
    patientBar: '#labTrendsPatientBar',
    customButton: '#labTrendsCustomBtn',
    customPanel: '#labTrendsMetabolicPanel',
    testChecks: '#labTrendsMetabolicPanel .labTrendsMetaTestChk',
    checkedTests: '.labTrendsMetaTestChk:checked',
    dateChecks: '.labTrendsMetaDateChk',
    checkedDates: '.labTrendsMetaDateChk:checked'
  };
  const LABELS = {
    investigation: ['Investigation'],
    trends: ['Cr No Wise Investigation Trends', 'CR No Wise Investigation Trends'],
    go: ['Go'],
    custom: ['Custom Sheet'],
    apply: ['Apply']
  };
  let notifyTimer = null;

  function verifiedFrameOrigin(frame) {
    try {
      const direct = String(frame?.contentWindow?.location?.origin || '');
      if (NIMS_ORIGINS.has(direct)) return direct;
    } catch {}
    try {
      const srcOrigin = new URL(String(frame?.src || ''), location.href).origin;
      if (NIMS_ORIGINS.has(srcOrigin)) return srcOrigin;
    } catch {}
    return '';
  }

  const waitFor = async (find, label, timeoutMs = 20000) => {
    const until = Date.now() + timeoutMs;
    while (Date.now() < until) {
      const value = (() => { try { return find(); } catch { return null; } })();
      if (value) return value;
      await new Promise(resolve => setTimeout(resolve, 150));
    }
    throw new Error(`NIMS ${label} did not load.`);
  };

  const visible = element => {
    if (!element) return false;
    if (element.hidden || element.getAttribute?.('aria-hidden') === 'true') return false;
    try {
      const style = element.ownerDocument?.defaultView?.getComputedStyle(element);
      if (style && ['none'].includes(style.display)) return false;
      if (style && ['hidden', 'collapse'].includes(style.visibility)) return false;
    } catch {}
    return true;
  };

  const compact = value => String(value || '').replace(/\s+/g, ' ').trim();
  const findText = (root, selector, exact) =>
    [...(root?.querySelectorAll?.(selector) || [])].find(element => compact(element.textContent || element.value) === exact);
  const findTextAny = (root, selector, labels) =>
    labels.map(label => findText(root, selector, label)).find(Boolean) || null;

  function bodyText() {
    try { return compact(document.body?.innerText || document.body?.textContent || '').slice(0, 12000); }
    catch { return ''; }
  }

  function probe() {
    const href = String(location.href || '');
    const text = bodyText();
    const loginForm = Boolean(document.querySelector('input[type="password"]')) &&
      /login|sign\s*in|captcha|user\s*name|user\s*id/i.test(text);
    const sessionExpired = /session\s*(?:has\s*)?expired|invalid\s*session|not a authenticated user|please\s*login\s*again|session\s*timeout/i.test(text);
    let pathname = '';
    try { pathname = new URL(href).pathname || ''; } catch {}
    const loginRoute = /\/AHIMSG5\/hissso\/loginLogin\.action$/i.test(pathname);
    const protectedRoute = /\/(?:HISInvestigationG5|HIS|hislogin|HISUtilities|HBIMS)(?:\/|$)/i.test(pathname) ||
      /\/AHIMSG5\/hislogin\/transactions(?:\/|$)/i.test(pathname);
    const shellEvidence = /home\s*menu|investigation|welcome|e-?sushrut/i.test(text) ||
      Boolean(document.querySelector(UI.menuFrame));
    return {
      href,
      loginForm,
      sessionExpired,
      authenticated: Boolean(!loginForm && !sessionExpired && !loginRoute && (protectedRoute || shellEvidence)),
      trendsReady: Boolean(document.getElementById(UI.trendFrameId)),
      uiCompatibility: {
        selectorVersion: 'v1',
        menuFramePresent: Boolean(document.querySelector(UI.menuFrame)),
        trendFramePresent: Boolean(document.getElementById(UI.trendFrameId))
      }
    };
  }

  function notify() {
    clearTimeout(notifyTimer);
    notifyTimer = setTimeout(() => {
      try {
        chrome.runtime.sendMessage({ type: 'NIMS_SESSION_STATE', state: probe() }).catch(() => {});
      } catch {}
    }, 100);
  }

  async function openTrends() {
    if (probe().sessionExpired) throw new Error('NIMS session expired. Sign in again.');

    const existingFrame = document.getElementById(UI.trendFrameId);
    try {
      if (existingFrame?.contentDocument?.querySelector(UI.crInput)) return true;
    } catch {}

    let menu = null;
    try { menu = document.querySelector(UI.menuFrame)?.contentDocument || null; } catch {}

    if (!menu || !findTextAny(menu, 'a', LABELS.trends)) {
      const tab = findTextAny(document, 'a', LABELS.investigation) ||
        findTextAny(document, '[role="tab"]', LABELS.investigation) ||
        findTextAny(document, 'button', LABELS.investigation);
      if (!tab) throw new Error('NIMS Investigation menu was not found.');
      tab.click();
    }

    const link = await waitFor(() => {
      try {
        menu = document.querySelector(UI.menuFrame)?.contentDocument || null;
        return menu ? findTextAny(menu, 'a', LABELS.trends) : null;
      } catch { return null; }
    }, 'CR trends service', 20000);

    link.click();

    await waitFor(() => {
      try {
        const frame = document.getElementById(UI.trendFrameId);
        return frame?.contentDocument?.querySelector(UI.crInput) ? frame : null;
      } catch { return null; }
    }, 'CR trends page', 25000);

    return true;
  }

  function parsePatient(text, crNo) {
    const name = text.match(/Name:\s*(.*?)(?=(?:CR\s*No|Gender|Sex|Age):|\n|$)/is)?.[1]?.trim() || '';
    const age = text.match(/Age:\s*([^\n|]+)/i)?.[1]?.trim() || '';
    const sex = text.match(/(?:Gender|Sex):\s*([^\n|]+)/i)?.[1]?.trim() || '';
    return { name, crNo, age, sex };
  }

  async function runCustom(crNo, requestId) {
    if (!/^\d{15}$/.test(String(crNo || ''))) throw new Error('Enter the 15-digit NIMS CR number.');
    await openTrends();

    const trendFrame = document.getElementById(UI.trendFrameId);
    const trendDocument = () => {
      try { return trendFrame?.contentDocument || null; } catch { return null; }
    };
    const input = await waitFor(() => trendDocument()?.querySelector(UI.crInput), 'CR input');

    input.value = crNo;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));

    const go = findTextAny(trendDocument(), 'button', LABELS.go) ||
      findTextAny(trendDocument(), 'input[type="button"],input[type="submit"]', LABELS.go);
    if (!go) throw new Error('NIMS CR search button was not found.');

    const currentBar = trendDocument()?.querySelector(UI.patientBar);
    if (!visible(currentBar) || !compact(currentBar.textContent).includes(crNo)) go.click();

    await waitFor(() => {
      const bar = trendDocument()?.querySelector(UI.patientBar);
      return visible(bar) && compact(bar.textContent).includes(crNo) ? bar : null;
    }, 'requested CR results', 30000);

    const patientText = String(trendDocument()?.body?.innerText || '');
    if (!patientText.includes(crNo)) {
      throw new Error('NIMS did not confirm the requested CR. Results were not loaded.');
    }

    const custom = await waitFor(() => {
      const button = trendDocument()?.querySelector(UI.customButton);
      return visible(button) ? button : null;
    }, 'Custom Sheet control', 30000);

    if (LABELS.custom.includes(compact(custom.textContent))) custom.click();

    await waitFor(() => {
      const panel = trendDocument()?.querySelector(UI.customPanel);
      return visible(panel) ? panel : null;
    }, 'Custom Sheet panel');

    const testChecks = await waitFor(() => {
      const list = [...(trendDocument()?.querySelectorAll(UI.testChecks) || [])];
      return list.length ? list : null;
    }, 'Custom Sheet tests');

    for (const check of testChecks) if (!check.checked) check.click();

    const dateChecks = [...(trendDocument()?.querySelectorAll(UI.dateChecks) || [])];
    for (const check of dateChecks) if (!check.checked) check.click();

    if (trendDocument().querySelectorAll(UI.checkedTests).length !== testChecks.length ||
        (dateChecks.length && trendDocument().querySelectorAll(UI.checkedDates).length !== dateChecks.length)) {
      throw new Error('NIMS did not select all Custom Sheet tests and dates.');
    }

    const applies = [...trendDocument().querySelectorAll('#labTrendsMetabolicPanel button,input[type="button"],input[type="submit"]')]
      .filter(button => LABELS.apply.includes(compact(button.textContent || button.value)) && visible(button));

    if (applies.length !== 1) throw new Error('NIMS Custom Sheet Apply control was ambiguous.');

    const targetOrigin = verifiedFrameOrigin(trendFrame);
    if (!targetOrigin) {
      throw new Error('NIMS Trends frame origin could not be verified. Reload NIMS and retry.');
    }
    try {
      trendFrame.contentWindow.postMessage(
        { __nimsBulkCaptureArm: true, requestId: String(requestId || '') },
        targetOrigin
      );
    } catch (error) {
      throw new Error('NIMS Trends frame could not be armed for structured retrieval.');
    }

    applies[0].click();

    return {
      patient: parsePatient(patientText, crNo),
      testCount: testChecks.length,
      dateCount: dateChecks.length
    };
  }

  window.addEventListener('message', event => {
    if (!NIMS_ORIGINS.has(event.origin) || event.data?.__nimsBulkCapture !== true) return;
    const trendWindow = document.getElementById(UI.trendFrameId)?.contentWindow;
    if (trendWindow && event.source !== trendWindow) return;
    try {
      chrome.runtime.sendMessage({
        type: 'NIMS_BULK_CAPTURE',
        requestId: String(event.data.requestId || ''),
        body: event.data.body
      }).catch(() => {});
    } catch {}
  });

  chrome.runtime.onMessage.addListener((message, _sender, reply) => {
    if (!message || !message.type) return false;

    if (message.type === 'NIMS_BULK_PROBE') {
      reply({ ok: true, state: probe() });
      return false;
    }

    if (message.type === 'NIMS_BULK_OPEN_TRENDS') {
      Promise.resolve(openTrends())
        .then(() => reply({ ok: true }))
        .catch(error => reply({ ok: false, error: String(error?.message || 'Unable to open NIMS Investigation Trends.') }));
      return true;
    }

    if (message.type === 'NIMS_BULK_RUN_CUSTOM') {
      Promise.resolve(runCustom(String(message.crNo || ''), String(message.requestId || '')))
        .then(value => reply({ ok: true, value }))
        .catch(error => reply({ ok: false, error: String(error?.message || 'NIMS Custom Sheet retrieval failed.') }));
      return true;
    }

    return false;
  });

  if (document.documentElement) {
    const observer = new MutationObserver(notify);
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }
  notify();
})();
