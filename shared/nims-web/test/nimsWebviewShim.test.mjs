import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../nimsWebviewShim.js', import.meta.url), 'utf8');

function fixture(extra = {}) {
  const queue = [];
  const documentListeners = new Map();
  const windowListeners = new Map();
  const errors = [];
  const warnings = [];
  const document = {
    readyState: 'complete',
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action' },
    addEventListener(type, fn) {
      const values = documentListeners.get(type) || [];
      values.push(fn);
      documentListeners.set(type, values);
    },
    fire(type, target) {
      for (const fn of documentListeners.get(type) || []) fn({ type, target, currentTarget: target, srcElement: target });
    },
  };
  const win = {
    console: {
      error(...args) { errors.push(args); },
      warn(...args) { warnings.push(args); },
    },
    document,
    location: {
      href: document.location.href,
      hostname: 'www.nimsts.edu.in',
      pathname: '/AHIMSG5/hissso/loginLogin.action',
      protocol: 'https:',
    },
    addEventListener(type, fn) {
      const values = windowListeners.get(type) || [];
      values.push(fn);
      windowListeners.set(type, values);
    },
    setTimeout(fn) { queue.push(fn); return queue.length; },
    ...extra,
  };
  win.document = extra.document || document;
  win.top = win;
  win.document.defaultView = win;
  const context = { window: win, URL, Date, Object, JSON };
  context.globalThis = context;
  vm.createContext(context);
  vm.runInContext(source, context);
  win.flush = () => {
    let count = 0;
    while (queue.length && count < 200) {
      queue.shift()();
      count += 1;
    }
  };
  win.errors = errors;
  win.warnings = warnings;
  return win;
}

function assertZeroOffset(value) {
  assert.equal(value.top, 0);
  assert.equal(value.left, 0);
}

function frame(document, id) {
  return {
    tagName: 'IFRAME',
    id,
    ownerDocument: document,
    isConnected: true,
    contentDocument: { readyState: 'complete' },
    getAttribute: () => '/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt',
  };
}

test('installs date_time and safe offset without changing navigation', () => {
  const jq = () => {};
  jq.fn = { jquery: '3.7.1', offset: () => undefined };
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
  assertZeroOffset(win.jQuery.fn.offset());
});

test('supplies the unique iframe that emitted the load event', () => {
  let received = null;
  const win = fixture({ ajaxCompleteTab(value) { received = value; return value.contentDocument.readyState; } });
  win.flush();
  const reportFrame = frame(win.document, 'Cr No Wise Result Report Printing New_iframe');
  win.document.fire('load', reportFrame);
  assert.equal(win.ajaxCompleteTab(), 'complete');
  assert.equal(received, reportFrame);
});

test('does not guess when multiple frames loaded in the same window', () => {
  let calls = 0;
  const win = fixture({ ajaxCompleteTab() { calls += 1; } });
  win.flush();
  win.document.fire('load', frame(win.document, 'frmMainMenu'));
  win.document.fire('load', frame(win.document, 'Cr No Wise Result Report Printing New_iframe'));
  assert.equal(win.ajaxCompleteTab(), undefined);
  assert.equal(calls, 0);
  assert.equal(win.warnings.length, 1);
});

test('reports and rethrows unexpected ajaxCompleteTab errors', () => {
  const win = fixture({ ajaxCompleteTab() { throw new Error('unexpected tab failure'); } });
  win.flush();
  win.document.fire('load', frame(win.document, 'Cr No Wise Result Report Printing New_iframe'));
  assert.throws(() => win.ajaxCompleteTab(), /unexpected tab failure/);
  assert.equal(win.errors.length, 1);
});

test('posts safe per-frame readiness telemetry', () => {
  const messages = [];
  const jq = () => {};
  jq.fn = { jquery: '3.7.1', offset: () => undefined };
  const win = fixture({
    jQuery: jq,
    $: jq,
    __nimsBundledJqueryVersion: '3.7.1',
    nimsAndroidBridge: { postMessage(value) { messages.push(JSON.parse(value)); } },
  });
  win.flush();
  const runtime = messages.find((value) => value.type === 'nims_runtime_status');
  assert.ok(runtime);
  assert.equal(runtime.jqueryReady, true);
  assert.equal(runtime.offsetPatched, true);
  assert.equal(runtime.dateTimeReady, true);
  assert.equal(runtime.url.includes('?'), false);
});

test('does not install outside NIMS', () => {
  const win = fixture({
    location: { href: 'https://example.invalid/app', hostname: 'example.invalid', pathname: '/app', protocol: 'https:' },
  });
  win.flush();
  assert.equal(win.date_time, undefined);
});
