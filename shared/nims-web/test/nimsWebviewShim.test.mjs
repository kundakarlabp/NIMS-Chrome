import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const shimSource = readFileSync(new URL('../nimsWebviewShim.js', import.meta.url), 'utf8');

function runShim(windowExtras = {}) {
  const timeouts = [];
  const intervals = [];
  const fakeWindow = {
    console: { error() {} },
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action' },
    addEventListener() {},
    setTimeout(fn) { timeouts.push(fn); return timeouts.length; },
    setInterval(fn) { intervals.push(fn); return intervals.length; },
    clearInterval() {},
    ...windowExtras,
  };
  if (!('top' in fakeWindow)) fakeWindow.top = fakeWindow;
  if (fakeWindow.document) fakeWindow.document.defaultView = fakeWindow;
  const context = { window: fakeWindow, URL };
  context.globalThis = context;
  vm.createContext(context);
  vm.runInContext(shimSource, context);
  fakeWindow.__tickIntervals = () => intervals.forEach((fn) => fn());
  fakeWindow.__runTimeouts = (limit = 400) => {
    let count = 0;
    while (timeouts.length && count < limit) {
      const fn = timeouts.shift();
      fn();
      count += 1;
    }
    return count;
  };
  return fakeWindow;
}

function visibleNode(extra = {}) {
  return {
    hidden: false,
    parentElement: null,
    getAttribute() { return ''; },
    ...extra,
  };
}

function makeDocument({ frames = [], forms = [], inputs = [], rows = [], labels = [], anchors = [], ids = {}, text = '', height = 0 } = {}) {
  const listeners = {};
  const doc = {
    readyState: 'complete',
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action' },
    body: {
      innerText: text,
      scrollHeight: height,
      querySelectorAll() { return [...frames, ...forms, ...inputs, ...rows, ...labels, ...anchors]; },
    },
    addEventListener(type, fn) { listeners[type] = fn; },
    getElementById(id) { return ids[id] || frames.find((frame) => frame.id === id) || anchors.find((node) => node.id === id) || null; },
    querySelector(selector) {
      if (selector.includes('frmMainMenu')) return frames.find((frame) => frame.id === 'frmMainMenu') || null;
      return null;
    },
    querySelectorAll(selector) {
      if (selector === 'iframe, frame') return frames;
      if (selector === 'form') return forms;
      if (selector === 'input, textarea, select') return inputs;
      if (selector === 'tr') return rows;
      if (selector === '[onclick],a,button') return anchors;
      if (selector.includes('label') || selector.includes('legend')) return labels;
      if (selector === '*') return [...frames, ...forms, ...inputs, ...rows, ...labels, ...anchors];
      return [];
    },
    __listeners: listeners,
  };
  for (const item of [...frames, ...forms, ...inputs, ...rows, ...labels, ...anchors]) {
    if (item && !item.ownerDocument) item.ownerDocument = doc;
  }
  return doc;
}

function makeFrame(id, childDoc, src = '') {
  return visibleNode({
    id,
    name: '',
    src,
    contentDocument: childDoc,
    contentWindow: childDoc ? { document: childDoc } : {},
    getAttribute(name) {
      if (name === 'src') return src;
      if (name === 'aria-hidden') return null;
      return '';
    },
  });
}

function makeCrInput() {
  const input = visibleNode({
    id: 'patCrNo',
    name: 'patCrNo',
    type: 'text',
    getAttribute(name) { return name === 'aria-hidden' ? null : ''; },
  });
  Object.defineProperty(input, 'value', {
    get() { throw new Error('patient value must not be read'); },
  });
  return input;
}

function makeCrForm() {
  return visibleNode({
    name: 'InvResultReportPrintingFB',
    id: '',
    method: 'post',
    getAttribute(name) {
      if (name === 'action') return '/HISInvestigationG5/new_investigation/invResultReportPrintingCRNoWise.cnt';
      if (name === 'aria-hidden') return null;
      return '';
    },
  });
}

function makeNestedContract({ reportList = false } = {}) {
  const input = makeCrInput();
  const form = makeCrForm();
  const button = visibleNode({
    getAttribute(name) { return name === 'onclick' ? "printReport('opaque')" : null; },
  });
  const row = visibleNode({
    querySelectorAll(selector) { return selector === '[onclick]' ? [button] : []; },
  });
  const innerDoc = makeDocument({
    forms: reportList ? [] : [form],
    inputs: reportList ? [] : [input],
    rows: reportList ? [row] : [],
    labels: reportList ? [] : [visibleNode({ textContent: 'CR Number' })],
  });
  const inner = makeFrame(
    'Cr No Wise Result Report Printing_iframe',
    innerDoc,
    '/HISInvestigationG5/new_investigation/invResultReportPrintingCRNoWise.cnt',
  );
  const outerDoc = makeDocument({ frames: [inner] });
  const outer = makeFrame(
    'Cr No Wise Result Report Printing New_iframe',
    outerDoc,
    '/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt',
  );
  return { outer, outerDoc, inner, innerDoc };
}

test('defines date_time as a safe no-op when missing', () => {
  const w = runShim();
  assert.equal(typeof w.date_time, 'function');
  assert.equal(w.date_time(), '');
});

test('does not clobber an existing date_time', () => {
  const original = () => 'real';
  const w = runShim({ date_time: original });
  assert.equal(w.date_time, original);
});

test('patches jQuery.offset so missing #menuStrip cannot throw on .left', () => {
  const jQuery = (() => {});
  jQuery.fn = { offset() { return undefined; } };
  const w = runShim({ jQuery });
  const offset = w.jQuery.fn.offset();
  assert.equal(offset.top, 0);
  assert.equal(offset.left, 0);
});

test('preserves a real jQuery offset object', () => {
  const real = { top: 10, left: 25 };
  const jQuery = (() => {});
  jQuery.fn = { offset() { return real; } };
  const w = runShim({ jQuery });
  assert.equal(w.jQuery.fn.offset(), real);
});

test('posts a per-frame structural report without input values', () => {
  const posted = [];
  const document = makeDocument({ text: 'Services Special Clinic', height: 240, labels: Array.from({ length: 7 }, () => visibleNode()) });
  const w = runShim({
    document,
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/hislogin/transactions/jsp/st_desk_homeMenuTab_page.jsp' },
    nimsAndroidBridge: { postMessage(value) { posted.push(JSON.parse(value)); } },
  });
  w.__runTimeouts();
  const frame = posted.find((item) => item.type === 'nims_frame_debug');
  assert.ok(frame);
  assert.equal(frame.height, 240);
  assert.match(frame.url, /st_desk_homeMenuTab_page\.jsp$/);
});

test('live nested CR form overrides the old core outer-frame assumption', () => {
  const contract = makeNestedContract();
  const topDoc = makeDocument({ frames: [contract.outer] });
  const oldCore = {
    navigateToCrWiseReports() { return { ok: true, stage: 'investigation_menu', action: 'waiting_for_report_frame', done: false }; },
    navigateCurrentDocumentStep() { return { ok: true, stage: 'investigation_menu', action: 'waiting_for_report_frame', done: false }; },
    detectNimsPageStage() { return { stage: 'unknown', framesChecked: 1, evidence: [] }; },
    diagnosePage() { return {}; },
  };
  const w = runShim({ document: topDoc, NimsReportCore: oldCore });
  const result = w.NimsReportCore.navigateToCrWiseReports(topDoc);
  assert.equal(result.stage, 'cr_search');
  assert.equal(result.done, true);
  assert.equal(w.NimsReportCore.detectNimsPageStage(topDoc).stage, 'cr_search');
});

test('live nested report rows are terminal report_list', () => {
  const contract = makeNestedContract({ reportList: true });
  const topDoc = makeDocument({ frames: [contract.outer] });
  const w = runShim({
    document: topDoc,
    NimsReportCore: {
      navigateToCrWiseReports() { return { stage: 'unknown', done: false }; },
      navigateCurrentDocumentStep() { return { stage: 'unknown', done: false }; },
      detectNimsPageStage() { return { stage: 'unknown' }; },
      diagnosePage() { return {}; },
    },
  });
  const result = w.NimsReportCore.navigateToCrWiseReports(topDoc);
  assert.equal(result.stage, 'report_list');
  assert.equal(result.done, true);
});

test('outer New tab iframe alone is loading, not falsely ready', () => {
  const outerDoc = makeDocument();
  const outer = makeFrame('Cr No Wise Result Report Printing New_iframe', outerDoc, '/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt');
  const topDoc = makeDocument({ frames: [outer] });
  const w = runShim({
    document: topDoc,
    NimsReportCore: {
      navigateToCrWiseReports() { return { stage: 'unknown', done: false }; },
      navigateCurrentDocumentStep() { return { stage: 'unknown', done: false }; },
      detectNimsPageStage() { return { stage: 'unknown' }; },
      diagnosePage() { return {}; },
    },
  });
  const result = w.NimsReportCore.navigateToCrWiseReports(topDoc);
  assert.equal(result.stage, 'investigation_menu');
  assert.equal(result.action, 'waiting_for_report_frame');
  assert.equal(result.done, false);
});

test('ajaxCompleteTab contentDocument race is retried without an uncaught throw', () => {
  let calls = 0;
  const document = makeDocument();
  const w = runShim({
    document,
    ajaxCompleteTab() {
      calls += 1;
      if (calls === 1) throw new TypeError("Cannot read properties of undefined (reading 'contentDocument')");
      return 'ok';
    },
  });
  assert.doesNotThrow(() => w.ajaxCompleteTab('tab', 'response'));
  w.__runTimeouts();
  assert.equal(calls, 2);
});

test('Investigation click uses the exact anchor and waits for the nested live contract', () => {
  const posted = [];
  let anchorClicks = 0;
  let fallbackCalls = 0;
  const topDoc = makeDocument();
  const anchor = visibleNode({
    id: 'Cr_No_Wise_Result_Report_Printing_New',
    textContent: 'Cr No Wise Result Report Printing New',
    getAttribute(name) {
      if (name === 'onclick') return "callMenu('/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt','Cr_No_Wise_Result_Report_Printing_New')";
      return null;
    },
    click() {
      anchorClicks += 1;
      const contract = makeNestedContract();
      contract.outer.ownerDocument = topDoc;
      topDoc.__frames.push(contract.outer);
    },
  });
  const menuDoc = makeDocument({ anchors: [anchor], ids: { Cr_No_Wise_Result_Report_Printing_New: anchor } });
  const menuFrame = makeFrame('frmMainMenu', menuDoc, '/AHIMSG5/hislogin/transactions/jsp/st_desk_homeMenuTab_page.jsp');
  menuFrame.contentWindow.callMenu = () => { fallbackCalls += 1; };
  topDoc.__frames = [menuFrame];
  topDoc.querySelectorAll = function (selector) {
    if (selector === 'iframe, frame') return this.__frames;
    if (selector === '*') return this.__frames;
    return [];
  };
  topDoc.getElementById = function (id) { return this.__frames.find((frame) => frame.id === id) || null; };
  topDoc.querySelector = function (selector) { return selector.includes('frmMainMenu') ? menuFrame : null; };

  const w = runShim({
    document: topDoc,
    nimsAndroidBridge: { postMessage(value) { posted.push(JSON.parse(value)); } },
  });
  topDoc.__listeners.click({
    target: visibleNode({
      innerText: 'Investigation',
      getAttribute(name) { return name === 'onclick' ? "menuSelected('Investigation',true)" : null; },
    }),
  });
  w.__runTimeouts();

  assert.equal(anchorClicks, 1);
  assert.equal(fallbackCalls, 0);
  assert.ok(posted.some((item) => item.type === 'nims_report_frame' && item.rowCount === 0 && item.clearReason === 'investigation_click'));
  assert.ok(posted.some((item) => (item.errors || []).some((entry) => entry.includes('contract=cr_search'))));
});
