import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../nimsWebviewShim.js', import.meta.url), 'utf8');

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

test('installs the compatibility guards', () => {
  const jq = () => {};
  jq.fn = { offset: () => undefined };
  const win = run({ jQuery: jq });
  assert.equal(win.date_time(), '');
  assert.equal(win.jQuery.fn.offset().left, 0);
});

test('retries the completion callback only once after a frame race', () => {
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

test('normalizes a string response before legacy response.filter use', () => {
  function jq(value) {
    return { value, filter() { return this; } };
  }
  jq.fn = { offset: () => ({ top: 0, left: 0 }) };
  const win = run({
    document: emptyDocument(),
    jQuery: jq,
    ajaxCompleteTab(response) {
      assert.equal(typeof response.filter, 'function');
      return response.value;
    },
  });
  assert.equal(win.ajaxCompleteTab('<div>CR</div>'), '<div>CR</div>');
});

test('recognises a CR form in the nested report frame', () => {
  const input = { id: 'patCrNo', name: 'patCrNo', type: 'text', hidden: false, parentElement: null, getAttribute: () => null };
  Object.defineProperty(input, 'value', { get() { throw new Error('value must not be read'); } });
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

test('uses the exact native anchor and never calls a guessed callMenu signature', () => {
  let anchorClicks = 0;
  let callMenuCalls = 0;
  const anchor = {
    id: 'Cr_No_Wise_Result_Report_Printing_New', hidden: false, parentElement: null,
    innerText: 'Cr No Wise Result Report Printing New',
    getAttribute(name) { return name === 'onclick' ? "callMenu('/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt')" : null; },
    click() { anchorClicks += 1; },
  };
  const menu = emptyDocument('https://www.nimsts.edu.in/AHIMSG5/menu');
  menu.defaultView = { callMenu() { callMenuCalls += 1; } };
  menu.getElementById = (id) => id === anchor.id ? anchor : null;
  menu.querySelectorAll = (selector) => {
    if (selector === 'iframe, frame') return [];
    if (selector.includes('[onclick]')) return [anchor];
    return [];
  };
  const frame = { id: 'frmMainMenu', hidden: false, parentElement: null, contentDocument: menu, getAttribute: () => null };
  const top = emptyDocument('https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action');
  frame.ownerDocument = top;
  top.querySelectorAll = (selector) => selector === 'iframe, frame' ? [frame] : [];
  const win = run({ document: top });
  const result = win.NimsAndroidNavigation.navigateToCrWiseReports(top);
  assert.equal(result.action, 'clicked_cr_wise_menu');
  assert.equal(anchorClicks, 1);
  assert.equal(callMenuCalls, 0);
});

test('a visible CR tab header is loading and is not clicked again', () => {
  let anchorClicks = 0;
  const header = { hidden: false, parentElement: null, innerText: 'Cr No Wise Result Report Printing New', getAttribute: () => null };
  const anchor = {
    id: 'Cr_No_Wise_Result_Report_Printing_New', hidden: false, parentElement: null,
    innerText: 'Cr No Wise Result Report Printing New', getAttribute: () => null,
    click() { anchorClicks += 1; },
  };
  const menu = emptyDocument('https://www.nimsts.edu.in/AHIMSG5/menu');
  menu.getElementById = (id) => id === anchor.id ? anchor : null;
  menu.querySelectorAll = (selector) => selector === 'iframe, frame' ? [] : selector.includes('[onclick]') ? [anchor] : [];
  const menuFrame = { id: 'frmMainMenu', hidden: false, parentElement: null, contentDocument: menu, getAttribute: () => null };
  const top = emptyDocument('https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action');
  menuFrame.ownerDocument = top;
  top.querySelectorAll = (selector) => {
    if (selector === 'iframe, frame') return [menuFrame];
    if (selector === 'a,li,div,span,button') return [header];
    return [];
  };
  const win = run({ document: top });
  const result = win.NimsAndroidNavigation.navigateToCrWiseReports(top);
  assert.equal(result.action, 'waiting_for_report_frame');
  assert.equal(anchorClicks, 0);
});
