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

  // One complete NIMS Custom Sheet request; never substitute partial history.
  let staged = null;
  function continueRun(_requestId) { /* retained solely for older native bridge compatibility */ }

  function installCapture(frame, requestId) {
    const w = frame.contentWindow;
    if (!w) throw Error('NIMS trends frame unavailable.');
    if (w.__NIMS_NATIVE_CAPTURE_INSTALLED__) return;
    w.__NIMS_NATIVE_CAPTURE_INSTALLED__ = true;
    const isBulk = (url,body) => /GETMETABOLICDATA/i.test(String(url || '')) || bodyIsBulk(body);
    // Preserve the original request owner. A delayed response from another
    // patient cannot be delivered to the next patient's callback.
    const ownerAtStart = (url,body) => isBulk(url,body) && staged?.awaitingResponse
      ? staged.requestId : '';
    const emit = (owner,value) => {
      const run=staged;
      if(!owner || !run || run.requestId!==owner || !run.awaitingResponse) return;
      try {
        const bar=run.frame.contentDocument?.querySelector(UI.patientBar);
        if(!visible(bar) || !compact(bar.textContent).includes(run.cr))
          throw Error('NIMS patient changed while fetching results.');
        const raw=typeof value==='string'?value:JSON.stringify(value);
        if(!raw || !raw.trim()) throw Error('Empty Custom Sheet response.');
        clearTimeout(run.timer);staged=null;
        AndroidNimsBridge.onBulk(owner,raw);
      } catch(err) {
        clearTimeout(run.timer);staged=null;
        AndroidNimsBridge.onError(owner,String(err?.message || 'Unable to capture NIMS results.'));
      }
    };
    const oldFetch=w.fetch;
    if(typeof oldFetch==='function') {
      w.fetch=async function(input,init) {
        const url=typeof input==='string'?input:input?.url||'';
        const owner=ownerAtStart(url,init?.body||'');
        const response=await oldFetch.apply(this,arguments);
        if(owner) {
          try { response.clone().text().then(v=>emit(owner,v)).catch(err=>emit(owner,'')); }
          catch { emit(owner,''); }
        }
        return response;
      };
    }
    const XHR=w.XMLHttpRequest;
    if(XHR?.prototype) {
      const oldOpen=XHR.prototype.open,oldSend=XHR.prototype.send;
      XHR.prototype.open=function(method,url) {
        this.__nimsNativeUrl=String(url||'');return oldOpen.apply(this,arguments);
      };
      XHR.prototype.send=function(body) {
        const owner=ownerAtStart(this.__nimsNativeUrl,body);
        if(owner) this.addEventListener('loadend',()=>{
          try {
            if(this.status && (this.status<200 || this.status>=300))
              throw Error('NIMS bulk response HTTP '+this.status);
            emit(owner,this.responseType==='json'&&this.response&&typeof this.response==='object'
              ? this.response : this.responseText);
          }catch(err){emit(owner,'');}
        },{once:true});
        return oldSend.apply(this,arguments);
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
      dateChecks.forEach(check => { if (!check.checked) check.click(); });
      if (testChecks.some(c => !c.checked) || dateChecks.some(c => !c.checked))
        throw Error('NIMS Custom Sheet did not select every test and date.');
      const buttons=[...doc().querySelector(UI.customPanel).querySelectorAll('button,input[type="button"],input[type="submit"]')]
        .filter(node=>LABELS.apply.includes(compact(node.textContent||node.value))&&visible(node));
      if(buttons.length!==1) throw Error('NIMS Custom Sheet Apply control unavailable or ambiguous.');
      if(staged?.timer) clearTimeout(staged.timer);
      installCapture(frame,requestId);
      staged={requestId,cr:crNo,frame,awaitingResponse:true,timer:null};
      staged.timer=setTimeout(()=>{
        if(staged?.requestId!==requestId) return;
        staged=null;
        AndroidNimsBridge.onError(requestId,'Complete Custom Sheet response timed out.');
      },60000);
      AndroidNimsBridge.onStage(requestId,'bulk_mode_single');
      AndroidNimsBridge.onStage(requestId,'bulk_selection:'+
        [testChecks.length,testChecks.filter(c=>c.checked).length,
         dateChecks.length,dateChecks.filter(c=>c.checked).length].join('/'));
      AndroidNimsBridge.onStage(requestId,'capturing_results');
      buttons[0].click();
    } catch (error) {
      if(staged?.requestId===requestId){clearTimeout(staged.timer);staged=null;}
      const message = String(error?.message || error || 'NIMS retrieval failed.');
      AndroidNimsBridge.onError(String(requestId || ''), message);
    }
  }

  window.NativeNimsEngine = { probe, run, continueRun };
})();