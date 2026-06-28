import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../nimsWebviewShim.js', import.meta.url), 'utf8');

function fixture(extra = {}) {
  const queue = [];
  const listeners = new Map();
  const document = {
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
  const win = {
    console: { error() {} },
    document,
    location: {
      href: document.location.href,
      hostname: 'www.nimsts.edu.in',
      protocol: 'https:',
    },
    addEventListener() {},
    setTimeout(fn) { queue.push(fn); return queue.length; },
    ...extra,
  };
  win.document = extra.document || document;
  win.top = win;
  win.document.defaultView = win;
  const context = { window: win, URL, Date, Object };
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

function assertZeroOffset(value) {
  assert.equal(value.top, 0);
  assert.equal(value.left, 0);
}

test('installs date_time and safe offset without changing navigation', () => {
  const jq = () => {};
  jq.fn = { offset: () => undefined };
  const core = { navigateToCrWiseReports: () => 'unchanged' };
  const original = core.navigateToCrWiseReports;
  const win = fixture({ jQuery: jq, $: jq, NimsReportCore: core });
  win.flush();
  assert.equal(typeof win.date_time, 'function');
  assertZeroOffset(win.jQuery.fn.offset());
  assert.equal(win.NimsReportCore.navigateToCrWiseReports, original);
});

test('patches a replacement page jQuery instance', () => {
  const fallback = () => {};
  fallback.fn = { offset: () => undefined };
  const win = fixture({ jQuery: fallback, $: fallback });
  const replacement = () => {};
  replacement.fn = { offset: () => undefined };
  win.$ = replacement;
  win.jQuery = replacement;
  assertZeroOffset(win.$.fn.offset());
});

test('supplies the iframe that emitted the load event', () => {
  let received = null;
  const win = fixture({ ajaxCompleteTab(frame) { received = frame; return frame.contentDocument.readyState; } });
  win.flush();
  const frame = {
    tagName: 'IFRAME',
    id: 'Cr No Wise Result Report Printing New_iframe',
    ownerDocument: win.document,
    isConnected: true,
    contentDocument: { readyState: 'complete' },
    getAttribute: () => '/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt',
  };
  win.document.fire('load', frame);
  assert.equal(win.ajaxCompleteTab(), 'complete');
  assert.equal(received, frame);
});

test('does not install outside NIMS', () => {
  const win = fixture({
    location: { href: 'https://example.invalid/app', hostname: 'example.invalid', protocol: 'https:' },
  });
  win.flush();
  assert.equal(win.date_time, undefined);
});
