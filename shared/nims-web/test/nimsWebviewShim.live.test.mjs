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
  win.flush = () => {
    let count = 0;
    while (queue.length && count < 100) {
      queue.shift()();
      count += 1;
    }
  };
  return win;
}

function emptyDocument(url = 'https://example.invalid/app') {
  return {
    readyState: 'complete',
    location: { href: url },
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

test('normalizes legacy response before response.filter', () => {
  function jq(value) { return { value, filter() { return this; } }; }
  jq.fn = { offset: () => ({ top: 0, left: 0 }) };
  const win = run({
    document: emptyDocument(),
    jQuery: jq,
    ajaxCompleteTab(response) {
      assert.equal(typeof response.filter, 'function');
      return response.value;
    },
  });
  assert.equal(win.ajaxCompleteTab('<div>ok</div>'), '<div>ok</div>');
});

test('recognises a CR form in the nested report frame', () => {
  const input = { id: 'patCrNo', name: 'patCrNo', type: 'text', hidden: false, parentElement: null, getAttribute: () => null };
  const form = {
    name: 'InvResultReportPrintingFB', id: '', hidden: false, parentElement: null,
    getAttribute(name) { return name === 'action' ? '/module/invResultReportPrintingCRNoWise.cnt' : null; },
  };
  const inner = emptyDocument('https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/invResultReportPrintingCRNoWise.cnt');
  input.ownerDocument = inner;
  form.ownerDocument = inner;
  inner.querySelectorAll = (selector) => {
    if (selector === 'input,textarea,select') return [input];
    if (selector === 'form') return [form];
    if (selector === 'iframe, frame') return [];
    return [];
  };
  const innerFrame = {
    id: 'Cr No Wise Result Report Printing_iframe', hidden: false, parentElement: null,
    contentDocument: inner,
    getAttribute(name) { return name === 'src' ? '/module/invResultReportPrintingCRNoWise.cnt' : null; },
  };
  const outer = emptyDocument('https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt');
  innerFrame.ownerDocument = outer;
  outer.querySelectorAll = (selector) => selector === 'iframe, frame' ? [innerFrame] : [];
  const outerFrame = {
    id: 'Cr No Wise Result Report Printing New_iframe', hidden: false, parentElement: null,
    contentDocument: outer,
    getAttribute(name) { return name === 'src' ? '/module/viewcrnowisereportprocess.cnt' : null; },
  };
  const top = emptyDocument('https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action');
  outerFrame.ownerDocument = top;
  top.getElementById = (id) => id === outerFrame.id ? outerFrame : null;
  top.querySelectorAll = (selector) => selector === 'iframe, frame' ? [outerFrame] : [];
  const win = run({ document: top });
  const result = win.NimsAndroidNavigation.navigateToCrWiseReports(top);
  assert.equal(result.stage, 'cr_search');
  assert.equal(result.done, true);
});
