(() => {
  'use strict';
  if (window.NativeNimsEngine) return;

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
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
  const visible = node => {
    if (!node || node.hidden || node.getAttribute?.('aria-hidden') === 'true') return false;
    try {
      const style = node.ownerDocument?.defaultView?.getComputedStyle(node);
      if (style && (style.display === 'none' || ['hidden','collapse'].includes(style.visibility))) return false;
    } catch {}
    return true;
  };
  const textExact = (root, selector, labels) => {
    const nodes = [...(root?.querySelectorAll?.(selector) || [])];
    for (const label of labels) {
      const found = nodes.find(node => compact(node.textContent || node.value) === label);
      if (found) return found;
    }
    return null;
  };
  const waitFor = async (fn, label, timeout = 24000) => {
    const until = Date.now() + timeout;
    while (Date.now() < until) {
      let value = null;
      try { value = fn(); } catch {}
      if (value) return value;
      await sleep(150);
    }
    throw new Error('NIMS ' + label + ' did not load.');
  };

  function collectDocuments(doc = document, depth = 0, seen = new Set(), out = []) {
    if (!doc || depth > 5 || seen.has(doc)) return out;
    seen.add(doc);
    out.push(doc);
    let frames = [];
    try { frames = [...doc.querySelectorAll('iframe,frame')]; } catch {}
    for (const frame of frames) {
      try { collectDocuments(frame.contentDocument || frame.contentWindow?.document, depth + 1, seen, out); } catch {}
    }
    return out;
  }

  function probe() {
    const docs = collectDocuments();
    let combined = '';
    let passwordVisible = false;
    let menuPresent = false;
    for (const doc of docs) {
      try {
        combined += ' ' + (doc.body?.innerText || doc.body?.textContent || '');
        if ([...doc.querySelectorAll('input[type="password"]')].some(visible)) passwordVisible = true;
        if (doc.querySelector(UI.menuFrame)) menuPresent = true;
      } catch {}
    }
    const text = compact(combined).slice(0, 40000);
    const href = String(location.href || '');
    let pathname = '';
    try { pathname = new URL(href).pathname || ''; } catch {}
    const loginRoute = /\/AHIMSG5\/hissso\/loginLogin\.action$/i.test(pathname);
    const loginForm = passwordVisible && /login|sign\s*in|captcha|user\s*name|user\s*id/i.test(text);
    const expired = /session\s*(?:has\s*)?expired|invalid\s*session|not a authenticated user|please\s*login\s*again|session\s*timeout/i.test(text);
    const protectedRoute = /\/(?:HISInvestigationG5|HIS|hislogin|HISUtilities|HBIMS)(?:\/|$)/i.test(pathname) ||
      /\/AHIMSG5\/hislogin\/transactions(?:\/|$)/i.test(pathname);
    const welcomeEvidence = /welcome\s*,|welcome\s+[^\n]{2,80}/i.test(text);
    const navEvidence = /\bregistration\b.*\bopd\b.*\binvestigation\b/i.test(text);
    const shell = menuPresent || welcomeEvidence || navEvidence || /home\s*menu|logout|sign\s*out/i.test(text);

    // NIMS can keep the top-level URL at loginLogin.action even after replacing
    // the page with the authenticated e-Sushrut frameset. Therefore URL alone
    // must never override strong authenticated-shell evidence.
    const authenticated = Boolean(!loginForm && !expired && (protectedRoute || shell));

    return {
      href,
      pathname,
      loginRoute,
      loginForm,
      sessionExpired: expired,
      authenticated,
      shellEvidence: shell,
      welcomeEvidence,
      navEvidence,
      menuPresent,
      documentCount: docs.length,
      trendFramePresent: Boolean(document.getElementById(UI.trendFrameId))
    };
  }

  function parsePatient(text, crNo) {
    const name = text.match(/Name:\s*(.*?)(?=(?:CR\s*No|Gender|Sex|Age):|\n|$)/is)?.[1]?.trim() || '';
    const age = text.match(/Age:\s*([^\n|]+)/i)?.[1]?.trim() || '';
    const sex = text.match(/(?:Gender|Sex):\s*([^\n|]+)/i)?.[1]?.trim() || '';
    return { name, crNo, age, sex };
  }

  async function openTrends() {
    const state = probe();
    if (!state.authenticated) throw new Error('NIMS authentication required.');

    let frame = document.getElementById(UI.trendFrameId);
    try {
      if (frame?.contentDocument?.querySelector(UI.crInput)) return frame;
    } catch {}

    const docs = () => collectDocuments();
    const menuDocument = () => {
      for (const doc of docs()) {
        try {
          const frame = doc.querySelector(UI.menuFrame);
          if (frame?.contentDocument) return frame.contentDocument;
        } catch {}
      }
      return null;
    };
    const findAcrossDocs = (selector, labels) => {
      for (const doc of docs()) {
        try {
          const found = textExact(doc, selector, labels);
          if (found) return found;
        } catch {}
      }
      return null;
    };

    let menuDoc = menuDocument();
    let trendLink = menuDoc ? textExact(menuDoc, 'a', LABELS.trends) : null;
    if (!trendLink) {
      const investigation =
        findAcrossDocs('a', LABELS.investigation) ||
        findAcrossDocs('[role="tab"]', LABELS.investigation) ||
        findAcrossDocs('button', LABELS.investigation);
      if (!investigation) throw new Error('NIMS Investigation menu was not found.');
      investigation.click();

      trendLink = await waitFor(() => {
        menuDoc = menuDocument();
        return menuDoc ? textExact(menuDoc, 'a', LABELS.trends) : null;
      }, 'CR trends service', 22000);
    }

    trendLink.click();

    frame = await waitFor(() => {
      try {
        const candidate = document.getElementById(UI.trendFrameId);
        return candidate?.contentDocument?.querySelector(UI.crInput) ? candidate : null;
      } catch { return null; }
    }, 'CR trends page', 28000);

    return frame;
  }

  function bodyIsBulk(body) {
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

  // Custom Sheet offers dated checkboxes, not a documented date-pagination
  // API. Choose the latest two weeks first when those labels are readable,
  // then fetch older 14-day date windows one at a time after Android
  // acknowledges that the previous response was parsed and displayed.
  const DAY_MS = 24 * 60 * 60 * 1000;
  let staged = null;
  function checkDate(check) {
    const candidates = [
      check.getAttribute('data-date'), check.value,
      check.closest('label')?.textContent, check.parentElement?.textContent,
      check.getAttribute('aria-label')
    ].filter(Boolean);
    const months = {jan:0,feb:1,mar:2,apr:3,may:4,jun:5,
      jul:6,aug:7,sep:8,oct:9,nov:10,dec:11};
    for (const value of candidates) {
      const text = String(value);
      let m = text.match(/\b(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})\b/);
      let y, mo, d;
      if (m) { y = +m[1]; mo = +m[2] - 1; d = +m[3]; }
      else {
        m = text.match(/\b(\d{1,2})[-/. ]([A-Za-z]{3,9}|\d{1,2})[-/. ](\d{2,4})\b/);
        if (!m) continue;
        d = +m[1]; mo = /^\d+$/.test(m[2]) ? +m[2] - 1 :
          months[m[2].slice(0,3).toLowerCase()];
        y = +m[3]; if (y < 100) y += 2000;
      }
      if (!Number.isInteger(mo) || y < 2000 || y > 2100) continue;
      const time = Date.UTC(y,mo,d);
      const date = new Date(time);
      if (date.getUTCFullYear() === y && date.getUTCMonth() === mo &&
          date.getUTCDate() === d) return time;
    }
    return null;
  }
  function planDateBatches(checks) {
    if (!checks.length) return [null];
    const dated = checks.map(checkDate);
    // Unparseable dates cannot be paginated safely. Preserve the existing
    // complete single-shot behavior rather than silently losing results.
    if (dated.some(value => value == null)) return [null];
    const newest = Math.max(...dated);
    const windows = new Map();
    dated.forEach((time, index) => {
      const win = Math.floor((newest - time) / (14 * DAY_MS));
      if (!windows.has(win)) windows.set(win, []);
      windows.get(win).push(index);
    });
    return [...windows.keys()].sort((a,b) => a - b)
      .flatMap(win => {
        const ids = windows.get(win);
        const groups = [];
        for (let i = 0; i < ids.length; i += 24) groups.push(ids.slice(i,i+24));
        return groups;
      });
  }
  function applyNextBatch(requestId) {
    const st = staged;
    if (!st || st.requestId !== requestId || st.waitingAck || st.running) return;
    if (st.index >= st.batches.length) {
      staged = null;
      AndroidNimsBridge.onStage(requestId, 'bulk_complete');
      return;
    }
    const doc = st.frame.contentDocument;
    if (!doc || !compact(doc.querySelector(UI.patientBar)?.textContent).includes(st.cr)) {
      staged = null;
      AndroidNimsBridge.onError(requestId, 'NIMS patient context changed during history fetch.');
      return;
    }
    const checks = [...doc.querySelectorAll(UI.dateChecks)];
    const chosen = st.batches[st.index];
    if (chosen !== null && checks.length !== st.dateCount) {
      staged = null;
      AndroidNimsBridge.onError(requestId, 'NIMS Custom Sheet dates changed; older results not fetched.');
      return;
    }
    checks.forEach((box, i) => {
      const wanted = chosen === null || chosen.includes(i);
      if (box.checked !== wanted) box.click();
    });
    const selected = doc.querySelectorAll(UI.checkedDates).length;
    if (checks.length && selected !== (chosen === null ? checks.length : chosen.length)) {
      staged = null;
      AndroidNimsBridge.onError(requestId, 'NIMS Custom Sheet date selection did not apply.');
      return;
    }
    const button = [...doc.querySelectorAll('#labTrendsMetabolicPanel button,input[type="button"],input[type="submit"]')]
      .filter(node => LABELS.apply.includes(compact(node.textContent || node.value)) && visible(node));
    if (button.length !== 1) {
      staged = null;
      AndroidNimsBridge.onError(requestId, 'NIMS Custom Sheet Apply control changed.');
      return;
    }
    st.running = true;
    st.index += 1;
    st.frame.contentWindow.__NIMS_NATIVE_REQUEST_ID__ = requestId;
    st.frame.contentWindow.__NIMS_NATIVE_BATCH_PENDING__ = true;
    st.timer = setTimeout(() => {
      if (staged !== st || st.waitingAck || !st.running) return;
      staged = null;
      st.frame.contentWindow.__NIMS_NATIVE_BATCH_PENDING__ = false;
      if (st.index === 1) AndroidNimsBridge.onError(
        requestId, 'NIMS first Custom Sheet date window timed out.');
      else AndroidNimsBridge.onStage(requestId, 'bulk_partial_timeout');
    }, 30000);
    AndroidNimsBridge.onStage(requestId,
      st.index === 1 ? 'capturing_results' :
      'fetching_older_results:' + st.index + '/' + st.batches.length);
    button[0].click();
  }
  function continueRun(requestId) {
    const st = staged;
    if (!st || st.requestId !== requestId || !st.waitingAck) return;
    st.waitingAck = false;
    st.running = false;
    // One batch in flight. Android parsed and accepted the previous chunk.
    setTimeout(() => applyNextBatch(requestId), 150);
  }

  function installCapture(frame, requestId) {
    const w = frame.contentWindow;
    if (!w) throw new Error('NIMS trends window is unavailable.');
    w.__NIMS_NATIVE_REQUEST_ID__ = String(requestId || '');
    if (w.__NIMS_NATIVE_CAPTURE_INSTALLED__) return;
    w.__NIMS_NATIVE_CAPTURE_INSTALLED__ = true;

    const isBulk = (url, body) => /GETMETABOLICDATA/i.test(String(url || '')) || bodyIsBulk(body);
    const emit = value => {
      try {
        const req = String(w.__NIMS_NATIVE_REQUEST_ID__ || '');
        if (!req || value == null || !w.__NIMS_NATIVE_BATCH_PENDING__) return;
        const text = typeof value === 'string' ? value : JSON.stringify(value);
        if (!text.trim()) return;
        w.__NIMS_NATIVE_BATCH_PENDING__ = false;
        if (staged?.requestId === req) {
          clearTimeout(staged.timer);
          staged.waitingAck = true;
          staged.running = false;
        }
        AndroidNimsBridge.onBulk(req, text);
      } catch {}
    };

    const originalFetch = w.fetch;
    if (typeof originalFetch === 'function') {
      w.fetch = async function(input, init) {
        const url = typeof input === 'string' ? input : input?.url || '';
        const body = init?.body || '';
        const response = await originalFetch.apply(this, arguments);
        if (isBulk(url, body)) {
          try { response.clone().text().then(emit).catch(() => {}); } catch {}
        }
        return response;
      };
    }

    const XHR = w.XMLHttpRequest;
    if (XHR?.prototype) {
      const open = XHR.prototype.open;
      const send = XHR.prototype.send;
      XHR.prototype.open = function(method, url) {
        this.__nimsNativeUrl = String(url || '');
        return open.apply(this, arguments);
      };
      XHR.prototype.send = function(body) {
        if (isBulk(this.__nimsNativeUrl, body)) {
          this.addEventListener('loadend', () => {
            try {
              if (this.responseType === 'json' && this.response && typeof this.response === 'object') emit(this.response);
              else emit(this.responseText);
            } catch {}
          }, { once: true });
        }
        return send.apply(this, arguments);
      };
    }
  }

  async function run(crNo, requestId) {
    try {
      crNo = String(crNo || '').replace(/\D/g, '');
      requestId = String(requestId || '').slice(0, 128);
      if (!/^\d{15}$/.test(crNo)) throw new Error('Enter the 15-digit NIMS CR number.');
      if (!requestId) throw new Error('Missing request ID.');

      AndroidNimsBridge.onStage(requestId, 'opening_trends');
      const frame = await openTrends();
      const doc = () => {
        try { return frame.contentDocument || null; } catch { return null; }
      };

      AndroidNimsBridge.onStage(requestId, 'loading_cr');
      const input = await waitFor(() => doc()?.querySelector(UI.crInput), 'CR input');
      input.value = crNo;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));

      const go = textExact(doc(), 'button', LABELS.go) ||
        textExact(doc(), 'input[type="button"],input[type="submit"]', LABELS.go);
      if (!go) throw new Error('NIMS CR search button was not found.');

      const current = doc()?.querySelector(UI.patientBar);
      if (!visible(current) || !compact(current.textContent).includes(crNo)) go.click();

      await waitFor(() => {
        const bar = doc()?.querySelector(UI.patientBar);
        return visible(bar) && compact(bar.textContent).includes(crNo) ? bar : null;
      }, 'requested CR results', 32000);

      const patientText = String(doc()?.body?.innerText || '');
      if (!patientText.includes(crNo)) throw new Error('NIMS did not confirm the requested CR.');
      AndroidNimsBridge.onPatient(requestId, JSON.stringify(parsePatient(patientText, crNo)));

      AndroidNimsBridge.onStage(requestId, 'preparing_custom_sheet');
      const custom = await waitFor(() => {
        const button = doc()?.querySelector(UI.customButton);
        return visible(button) ? button : null;
      }, 'Custom Sheet', 26000);
      if (LABELS.custom.includes(compact(custom.textContent))) custom.click();

      await waitFor(() => {
        const panel = doc()?.querySelector(UI.customPanel);
        return visible(panel) ? panel : null;
      }, 'Custom Sheet panel', 22000);

      const testChecks = await waitFor(() => {
        const list = [...(doc()?.querySelectorAll(UI.testChecks) || [])];
        return list.length ? list : null;
      }, 'Custom Sheet tests', 22000);
      testChecks.forEach(check => { if (!check.checked) check.click(); });

      const dateChecks = [...(doc()?.querySelectorAll(UI.dateChecks) || [])];
      const batches = planDateBatches(dateChecks);
      if (!batches.length) throw new Error('NIMS returned no selectable dates.');
      AndroidNimsBridge.onStage(requestId,
        batches.length === 1 && batches[0] === null ? 'bulk_mode_single' :
          'bulk_mode_staged:' + batches.length);
      installCapture(frame, requestId);
      staged = {
        requestId, cr: crNo, frame, dateCount: dateChecks.length,
        batches, index: 0, running: false, waitingAck: false
      };
      applyNextBatch(requestId);
    } catch (error) {
      const message = String(error?.message || error || 'NIMS retrieval failed.');
      AndroidNimsBridge.onError(String(requestId || ''), message);
    }
  }

  window.NativeNimsEngine = { probe, run, continueRun };
})();