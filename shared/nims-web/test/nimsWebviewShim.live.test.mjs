import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../nimsWebviewShim.js', import.meta.url), 'utf8');

function run(extra = {}) {
  const queue = [];
  const win = {
    console: { error() {} },
    location: { href: 'https://example.invalid/app' },
    addEventListener() {},
    setTimeout(fn) { queue.push(fn); return queue.length; },
    setInterval() { return 1; },
    clearInterval() {},
    ...extra,
  };
  win.top = win;
  if (win.document) win.document.defaultView = win;
  const context = { window: win, URL };
  context.globalThis = context;
  vm.createContext(context);
  vm.runInContext(source, context);
  win.flush = () => { let count = 0; while (queue.length && count < 100) { queue.shift()(); count += 1; } };
  return win;
}

function emptyDocument() {
  return {
    readyState: 'complete',
    location: { href: 'https://example.invalid/app' },
    body: { innerText: '', scrollHeight: 0, querySelectorAll: () => [] },
    addEventListener() {},
    getElementById: () => null,
    querySelector: () => null,
    querySelectorAll: () => [],
  };
}

test('installs compatibility guards', () => {
  const jq = () => {};
  jq.fn = { offset: () => undefined };
  const win = run({ jQuery: jq });
  assert.equal(win.date_time(), '');
  assert.equal(win.jQuery.fn.offset().left, 0);
});

test('retries the ajax completion frame race', () => {
  let calls = 0;
  const win = run({
    document: emptyDocument(),
    ajaxCompleteTab() {
      calls += 1;
      if (calls === 1) throw new TypeError("Cannot read properties of undefined (reading 'contentDocument')");
      return 'ok';
    },
  });
  assert.doesNotThrow(() => win.ajaxCompleteTab('tab', 'response'));
  win.flush();
  assert.equal(calls, 2);
});

test('recognises a CR form in the nested report frame', () => {
  const input = { id: 'patCrNo', name: 'patCrNo', type: 'text', hidden: false, parentElement: null, getAttribute: () => null };
  Object.defineProperty(input, 'value', { get() { throw new Error('value must not be read'); } });
  const form = {
    name: 'InvResultReportPrintingFB', id: '', hidden: false, parentElement: null,
    getAttribute(name) { return name === 'action' ? '/module/invResultReportPrintingCRNoWise.cnt' : null; },
  };
  const inner = emptyDocument();
  input.ownerDocument = inner;
  form.ownerDocument = inner;
  inner.querySelectorAll = (selector) => {
    if (selector === 'input, textarea, select') return [input];
    if (selector === 'form') return [form];
    if (selector.includes('label')) return [{ textContent: 'CR Number' }];
    return [];
  };
  const innerFrame = {
    id: 'Cr No Wise Result Report Printing_iframe', name: '', hidden: false, parentElement: null,
    contentDocument: inner,
    getAttribute(name) { return name === 'src' ? '/module/invResultReportPrintingCRNoWise.cnt' : null; },
  };
  const outer = emptyDocument();
  innerFrame.ownerDocument = outer;
  outer.querySelectorAll = (selector) => selector === 'iframe, frame' ? [innerFrame] : [];
  const outerFrame = {
    id: 'Cr No Wise Result Report Printing New_iframe', name: '', hidden: false, parentElement: null,
    contentDocument: outer,
    getAttribute(name) { return name === 'src' ? '/module/viewcrnowisereportprocess.cnt' : null; },
  };
  const top = emptyDocument();
  outerFrame.ownerDocument = top;
  top.querySelectorAll = (selector) => selector === 'iframe, frame' ? [outerFrame] : [];
  const core = {
    navigateToCrWiseReports: () => ({ stage: 'unknown', done: false }),
    navigateCurrentDocumentStep: () => ({ stage: 'unknown', done: false }),
    detectNimsPageStage: () => ({ stage: 'unknown' }),
    diagnosePage: () => ({}),
  };
  const win = run({ document: top, NimsReportCore: core });
  const result = win.NimsReportCore.navigateToCrWiseReports(top);
  assert.equal(result.stage, 'cr_search');
  assert.equal(result.done, true);
});
