(() => {
  'use strict';
  if (window.top !== window) return;
  if (window.__KBP_NIMS_UI_BRIDGE__) return;
  window.__KBP_NIMS_UI_BRIDGE__ = true;

  const NIMS_ORIGINS = new Set(['https://nimsts.edu.in', 'https://www.nimsts.edu.in']);
  let notifyTimer = null;

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
      Boolean(document.querySelector('#frmMainMenu'));
    return {
      href,
      loginForm,
      sessionExpired,
      authenticated: Boolean(!loginForm && !sessionExpired && !loginRoute && (protectedRoute || shellEvidence)),
      trendsReady: Boolean(document.getElementById('Cr No Wise Investigation Trends_iframe'))
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

    const existingFrame = document.getElementById('Cr No Wise Investigation Trends_iframe');
    try {
      if (existingFrame?.contentDocument?.querySelector('#patCrNo')) return true;
    } catch {}

    let menu = null;
    try { menu = document.querySelector('#frmMainMenu')?.contentDocument || null; } catch {}

    if (!menu || !findText(menu, 'a', 'Cr No Wise Investigation Trends')) {
      const tab = findText(document, 'a', 'Investigation') ||
        findText(document, '[role="tab"]', 'Investigation') ||
        findText(document, 'button', 'Investigation');
      if (!tab) throw new Error('NIMS Investigation menu was not found.');
      tab.click();
    }

    const link = await waitFor(() => {
      try {
        menu = document.querySelector('#frmMainMenu')?.contentDocument || null;
        return menu ? findText(menu, 'a', 'Cr No Wise Investigation Trends') : null;
      } catch { return null; }
    }, 'CR trends service', 20000);

    link.click();

    await waitFor(() => {
      try {
        const frame = document.getElementById('Cr No Wise Investigation Trends_iframe');
        return frame?.contentDocument?.querySelector('#patCrNo') ? frame : null;
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

    const trendFrame = document.getElementById('Cr No Wise Investigation Trends_iframe');
    const trendDocument = () => {
      try { return trendFrame?.contentDocument || null; } catch { return null; }
    };
    const input = await waitFor(() => trendDocument()?.querySelector('#patCrNo'), 'CR input');

    input.value = crNo;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));

    const go = findText(trendDocument(), 'button', 'Go') ||
      findText(trendDocument(), 'input[type="button"],input[type="submit"]', 'Go');
    if (!go) throw new Error('NIMS CR search button was not found.');

    const currentBar = trendDocument()?.querySelector('#labTrendsPatientBar');
    if (!visible(currentBar) || !compact(currentBar.textContent).includes(crNo)) go.click();

    await waitFor(() => {
      const bar = trendDocument()?.querySelector('#labTrendsPatientBar');
      return visible(bar) && compact(bar.textContent).includes(crNo) ? bar : null;
    }, 'requested CR results', 30000);

    const patientText = String(trendDocument()?.body?.innerText || '');
    if (!patientText.includes(crNo)) {
      throw new Error('NIMS did not confirm the requested CR. Results were not loaded.');
    }

    const custom = await waitFor(() => {
      const button = trendDocument()?.querySelector('#labTrendsCustomBtn');
      return visible(button) ? button : null;
    }, 'Custom Sheet control', 30000);

    if (compact(custom.textContent) === 'Custom Sheet') custom.click();

    await waitFor(() => {
      const panel = trendDocument()?.querySelector('#labTrendsMetabolicPanel');
      return visible(panel) ? panel : null;
    }, 'Custom Sheet panel');

    const testChecks = await waitFor(() => {
      const list = [...(trendDocument()?.querySelectorAll('#labTrendsMetabolicPanel .labTrendsMetaTestChk') || [])];
      return list.length ? list : null;
    }, 'Custom Sheet tests');

    for (const check of testChecks) if (!check.checked) check.click();

    const dateChecks = [...(trendDocument()?.querySelectorAll('.labTrendsMetaDateChk') || [])];
    for (const check of dateChecks) if (!check.checked) check.click();

    if (trendDocument().querySelectorAll('.labTrendsMetaTestChk:checked').length !== testChecks.length ||
        (dateChecks.length && trendDocument().querySelectorAll('.labTrendsMetaDateChk:checked').length !== dateChecks.length)) {
      throw new Error('NIMS did not select all Custom Sheet tests and dates.');
    }

    const applies = [...trendDocument().querySelectorAll('#labTrendsMetabolicPanel button,input[type="button"],input[type="submit"]')]
      .filter(button => compact(button.textContent || button.value) === 'Apply' && visible(button));

    if (applies.length !== 1) throw new Error('NIMS Custom Sheet Apply control was ambiguous.');

    const targetOrigin = NIMS_ORIGINS.has(location.origin) ? location.origin : 'https://www.nimsts.edu.in';
    try {
      trendFrame.contentWindow.postMessage(
        { __nimsBulkCaptureArm: true, requestId: String(requestId || '') },
        targetOrigin
      );
    } catch {}

    applies[0].click();

    return {
      patient: parsePatient(patientText, crNo),
      testCount: testChecks.length,
      dateCount: dateChecks.length
    };
  }

  window.addEventListener('message', event => {
    if (!NIMS_ORIGINS.has(event.origin) || event.data?.__nimsBulkCapture !== true) return;
    const trendWindow = document.getElementById('Cr No Wise Investigation Trends_iframe')?.contentWindow;
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
