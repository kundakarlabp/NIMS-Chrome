(() => {
  'use strict';
  if (window.AndroidNimsStructured) return;

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

  const compact = value => String(value || '').replace(/\s+/g, ' ').trim();
  const visible = element => {
    if (!element) return false;
    if (element.hidden || element.getAttribute?.('aria-hidden') === 'true') return false;
    try {
      const style = element.ownerDocument?.defaultView?.getComputedStyle(element);
      if (style && style.display === 'none') return false;
      if (style && ['hidden', 'collapse'].includes(style.visibility)) return false;
    } catch {}
    return true;
  };
  const findText = (root, selector, exact) =>
    [...(root?.querySelectorAll?.(selector) || [])].find(element => compact(element.textContent || element.value) === exact);
  const findTextAny = (root, selector, labels) =>
    labels.map(label => findText(root, selector, label)).find(Boolean) || null;

  const waitFor = async (find, label, timeoutMs = 22000) => {
    const until = Date.now() + timeoutMs;
    while (Date.now() < until) {
      const value = (() => { try { return find(); } catch { return null; } })();
      if (value) return value;
      await new Promise(resolve => setTimeout(resolve, 150));
    }
    throw new Error('NIMS ' + label + ' did not load.');
  };

  function recursiveText(doc, depth = 0, seen = new Set()) {
    if (!doc || depth > 6 || seen.has(doc)) return '';
    seen.add(doc);
    let text = '';
    try { text += ' ' + (doc.body?.innerText || doc.body?.textContent || ''); } catch {}
    let frames = [];
    try { frames = [...doc.querySelectorAll('iframe,frame')]; } catch {}
    for (const frame of frames) {
      try {
        const child = frame.contentDocument || frame.contentWindow?.document;
        if (child) text += ' ' + recursiveText(child, depth + 1, seen);
      } catch {}
    }
    return compact(text).slice(0, 30000);
  }

  function probe() {
    const href = String(location.href || '');
    const text = recursiveText(document);
    let password = false;
    try {
      password = [...document.querySelectorAll('input[type="password"]')].some(visible);
      if (!password) {
        for (const frame of [...document.querySelectorAll('iframe,frame')]) {
          try {
            if ([...frame.contentDocument.querySelectorAll('input[type="password"]')].some(visible)) {
              password = true;
              break;
            }
          } catch {}
        }
      }
    } catch {}
    const loginForm = password && /login|sign\s*in|captcha|user\s*name|user\s*id/i.test(text);
    const sessionExpired = /session\s*(?:has\s*)?expired|invalid\s*session|not a authenticated user|please\s*login\s*again|session\s*timeout/i.test(text);
    let pathname = '';
    try { pathname = new URL(href).pathname || ''; } catch {}
    const loginRoute = /\/AHIMSG5\/hissso\/loginLogin\.action$/i.test(pathname);
    const protectedRoute = /\/(?:HISInvestigationG5|HIS|hislogin|HISUtilities|HBIMS)(?:\/|$)/i.test(pathname)
      || /\/AHIMSG5\/hislogin\/transactions(?:\/|$)/i.test(pathname);
    let menuFrame = false;
    try { menuFrame = Boolean(document.querySelector(UI.menuFrame)); } catch {}
    const shellEvidence = /home\s*menu|investigation|welcome|e-?sushrut/i.test(text) || menuFrame;
    return {
      href,
      loginForm,
      sessionExpired,
      authenticated: Boolean(!loginForm && !sessionExpired && !loginRoute && (protectedRoute || shellEvidence)),
      menuFramePresent: menuFrame,
      trendFramePresent: Boolean(document.getElementById(UI.trendFrameId))
    };
  }

  async function openTrends() {
    const state = probe();
    if (state.sessionExpired || state.loginForm) throw new Error('NIMS authentication required.');

    const existingFrame = document.getElementById(UI.trendFrameId);
    try {
      if (existingFrame?.contentDocument?.querySelector(UI.crInput)) return existingFrame;
    } catch {}

    let menu = null;
    try { menu = document.querySelector(UI.menuFrame)?.contentDocument || null; } catch {}

    if (!menu || !findTextAny(menu, 'a', LABELS.trends)) {
      const tab = findTextAny(document, 'a', LABELS.investigation)
        || findTextAny(document, '[role="tab"]', LABELS.investigation)
        || findTextAny(document, 'button', LABELS.investigation);
      if (!tab) throw new Error('NIMS Investigation menu was not found.');
      tab.click();
    }

    const link = await waitFor(() => {
      try {
        menu = document.querySelector(UI.menuFrame)?.contentDocument || null;
        return menu ? findTextAny(menu, 'a', LABELS.trends) : null;
      } catch { return null; }
    }, 'CR trends service', 22000);

    link.click();

    return await waitFor(() => {
      try {
        const frame = document.getElementById(UI.trendFrameId);
        return frame?.contentDocument?.querySelector(UI.crInput) ? frame : null;
      } catch { return null; }
    }, 'CR trends page', 28000);
  }

  function bodyContainsMarker(body) {
    if (body == null) return false;
    if (/GETMETABOLICDATA/i.test(String(body))) return true;
    try {
      if (body instanceof URLSearchParams) {
        return [...body.entries()].some(([key, value]) => /GETMETABOLICDATA/i.test(key + '=' + value));
      }
    } catch {}
    try {
      if (body instanceof FormData) {
        return [...body.entries()].some(([key, value]) => /GETMETABOLICDATA/i.test(key + '=' + String(value)));
      }
    } catch {}
    return false;
  }

  function installCapture(frame, requestId) {
    const w = frame.contentWindow;
    if (!w) throw new Error('NIMS Trends window unavailable.');
    w.__KBP_ANDROID_ACTIVE_REQUEST = requestId;
    if (w.__KBP_ANDROID_BULK_CAPTURE__) return;
    w.__KBP_ANDROID_BULK_CAPTURE__ = true;
    const sink = window.AndroidNims;
    const isBulk = (url, body) => /GETMETABOLICDATA/i.test(String(url || '')) || bodyContainsMarker(body);
    const send = value => {
      try {
        const req = String(w.__KBP_ANDROID_ACTIVE_REQUEST || '');
        if (!req || value == null) return;
        const text = typeof value === 'string' ? value : JSON.stringify(value);
        if (text) sink.onBulkCapture(req, text);
      } catch {}
    };

    const originalFetch = w.fetch;
    if (typeof originalFetch === 'function') {
      w.fetch = async function(input, init) {
        const url = typeof input === 'string' ? input : input?.url || '';
        const body = init?.body || '';
        const response = await originalFetch.apply(this, arguments);
        if (isBulk(url, body)) {
          try {
            const clone = response.clone();
            clone.text().then(send).catch(() => {});
          } catch {}
        }
        return response;
      };
    }

    const XHR = w.XMLHttpRequest;
    if (XHR && XHR.prototype) {
      const originalOpen = XHR.prototype.open;
      const originalSend = XHR.prototype.send;
      XHR.prototype.open = function(method, url) {
        this.__kbpBulkUrl = String(url || '');
        return originalOpen.apply(this, arguments);
      };
      XHR.prototype.send = function(body) {
        const candidate = isBulk(this.__kbpBulkUrl, body);
        if (candidate) {
          this.addEventListener('loadend', () => {
            try {
              if (this.responseType === 'json' && this.response && typeof this.response === 'object') send(this.response);
              else send(this.responseText);
            } catch {}
          }, { once: true });
        }
        return originalSend.apply(this, arguments);
      };
    }
  }

  function parsePatient(text, crNo) {
    const name = text.match(/Name:\s*(.*?)(?=(?:CR\s*No|Gender|Sex|Age):|\n|$)/is)?.[1]?.trim() || '';
    const age = text.match(/Age:\s*([^\n|]+)/i)?.[1]?.trim() || '';
    const sex = text.match(/(?:Gender|Sex):\s*([^\n|]+)/i)?.[1]?.trim() || '';
    return { name, crNo, age, sex };
  }

  async function run(crNo, requestId) {
    try {
      crNo = String(crNo || '').replace(/\D/g, '');
      requestId = String(requestId || '').slice(0, 128);
      if (!/^\d{15}$/.test(crNo)) throw new Error('Enter the 15-digit NIMS CR number.');
      if (!requestId) throw new Error('Missing retrieval request ID.');

      const trendFrame = await openTrends();
      const trendDocument = () => {
        try { return trendFrame?.contentDocument || null; } catch { return null; }
      };
      const input = await waitFor(() => trendDocument()?.querySelector(UI.crInput), 'CR input');
      input.value = crNo;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));

      const go = findTextAny(trendDocument(), 'button', LABELS.go)
        || findTextAny(trendDocument(), 'input[type="button"],input[type="submit"]', LABELS.go);
      if (!go) throw new Error('NIMS CR search button was not found.');

      const currentBar = trendDocument()?.querySelector(UI.patientBar);
      if (!visible(currentBar) || !compact(currentBar.textContent).includes(crNo)) go.click();

      await waitFor(() => {
        const bar = trendDocument()?.querySelector(UI.patientBar);
        return visible(bar) && compact(bar.textContent).includes(crNo) ? bar : null;
      }, 'requested CR results', 32000);

      const patientText = String(trendDocument()?.body?.innerText || '');
      if (!patientText.includes(crNo)) throw new Error('NIMS did not confirm the requested CR. Results were not loaded.');

      const custom = await waitFor(() => {
        const button = trendDocument()?.querySelector(UI.customButton);
        return visible(button) ? button : null;
      }, 'Custom Sheet control', 26000);
      if (LABELS.custom.includes(compact(custom.textContent))) custom.click();

      await waitFor(() => {
        const panel = trendDocument()?.querySelector(UI.customPanel);
        return visible(panel) ? panel : null;
      }, 'Custom Sheet panel', 22000);

      const testChecks = await waitFor(() => {
        const list = [...(trendDocument()?.querySelectorAll(UI.testChecks) || [])];
        return list.length ? list : null;
      }, 'Custom Sheet tests', 22000);
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

      installCapture(trendFrame, requestId);
      window.AndroidNims.onStructuredReady(requestId, JSON.stringify(parsePatient(patientText, crNo)));
      applies[0].click();
    } catch (error) {
      try { window.AndroidNims.onStructuredError(String(requestId || ''), String(error?.message || error || 'NIMS structured retrieval failed.')); } catch {}
    }
  }

  window.AndroidNimsStructured = { probe, run };
})();