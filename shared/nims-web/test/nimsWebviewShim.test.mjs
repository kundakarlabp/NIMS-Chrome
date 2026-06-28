import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../nimsWebviewShim.js', import.meta.url), 'utf8');

function nimsDocument() {
  const listeners = new Map();
  return {
    readyState: 'complete',
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action' },
    addEventListener(type, fn) {
      const values = listeners.get(type) || [];
      values.push(fn);
      listeners.set(type, values);
    },
    fire(type, target) {
      for (const fn of listeners.get(type) || []) fn({ type, target, currentTarget: target, srcElement: target });
    },
  };
}

function run(extra = {}) {
  const queue = [];
  const document = extra.document || nimsDocument();
  const winListeners = new Map();
  const win = {
    console: { error() {} },
    document,
    location: {
      href: 'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action',
      hostname: 'www.nimsts.edu.in',
      protocol: 'https:',
    },
    addEventListener(type, fn) {
      const values = winListeners.get(type) || [];
      values.push(fn);
      winListeners.set(type, values);
    },
    setTimeout(fn) { queue.push(fn); return queue.length; },
    ...extra,
  };
  win.document = document;
  win.top = win;
  document.defaultView = win;
  const context = { window: win, URL, Date };
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

test('installs date_time and safe offset without changing navigation', () => {
  const jq = () => {};
  jq.fn = { offset: () => undefined };
  const core = { navigateToCrWiseReports: () => 'unchanged' };
  const originalNavigate = core.navigateToCrWiseReports;
  const win = run({ jQuery: jq, $: jq, NimsReportCore: core });
  win.flush();
  assert.equal(typeof win.date_time, 'function');
  assert.equal(win.date_time(), '');
  const off = win.jQuery.fn.offset();
  assert.equal(off.top, 0);
  assert.equal(off.left, 0);
  assert.equal(win.NimsReportCore.navigateToCrWiseReports, originalNavigate);
});

test('supplies the iframe that just loaded to zero-argument ajaxCompleteTab', () => {
  const document = nimsDocument();
  let received = null;
  let calls = 0;
  const win = run({
    document,
    ajaxCompleteTab(frame) {
      calls += 1;
      received = frame;
      return frame.contentDocument.readyState;
    },
  });
  win.flush();

  const frame = {
    tagName: 'IFRAME',
    id: 'Cr No Wise Result Report Printing New_iframe',
    name: '',
    ownerDocument: document,
    isConnected: true,
    contentDocument: { readyState: 'complete' },
    getAttribute(name) { return name === 'src' ? '/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt' : ''; },
  };
  document.fire('load', frame);

  assert.equal(win.ajaxCompleteTab(), 'complete');
  assert.equal(calls, 1);
  assert.equal(received, frame);
});

test('fails closed without calling the original function when no recent iframe exists', () => {
  let calls = 0;
  const win = run({
    ajaxCompleteTab() {
      calls += 1;
      throw new TypeError("Cannot read properties of undefined (reading 'contentDocument')");
    },
  });
  win.flush();
  assert.doesNotThrow(() => win.ajaxCompleteTab());
  assert.equal(calls, 0);
});

test('does not install on non-NIMS origins', () => {
  const document = nimsDocument();
  let calls = 0;
  const original = () => { calls += 1; };
  const win = run({
    document,
    location: { href: 'https://example.invalid/app', hostname: 'example.invalid', protocol: 'https:' },
    ajaxCompleteTab: original,
  });
  win.flush();
  assert.equal(win.ajaxCompleteTab, original);
  win.ajaxCompleteTab();
  assert.equal(calls, 1);
});
