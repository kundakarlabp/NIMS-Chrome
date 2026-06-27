import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const shimSource = readFileSync(new URL('../nimsWebviewShim.js', import.meta.url), 'utf8');

function runShim(windowExtras = {}) {
  const timers = [];
  const fakeWindow = {
    console: { error() {} },
    setInterval(fn) { timers.push(fn); return timers.length; },
    clearInterval() {},
    ...windowExtras,
  };
  const context = { window: fakeWindow, URL };
  context.globalThis = context;
  vm.createContext(context);
  vm.runInContext(shimSource, context);
  fakeWindow.__tick = () => timers.forEach((fn) => fn());
  return fakeWindow;
}

test('defines date_time as a safe no-op when missing', () => {
  const w = runShim();
  assert.equal(typeof w.date_time, 'function');
  assert.equal(w.date_time(), '');
  assert.doesNotThrow(() => w.date_time(1, 2, 3));
});

test('does not clobber an existing date_time', () => {
  const original = () => 'real';
  const w = runShim({ date_time: original });
  assert.equal(w.date_time, original);
});

test('patches jQuery.offset so empty-set .left cannot throw', () => {
  const jQuery = (() => {});
  jQuery.fn = { offset() { return undefined; } };
  const w = runShim({ jQuery });
  const result = w.jQuery.fn.offset();
  assert.equal(result.top, 0);
  assert.equal(result.left, 0);
  assert.doesNotThrow(() => w.jQuery.fn.offset().left);
});

test('preserves a real offset object when the element exists', () => {
  const real = { top: 10, left: 25 };
  const jQuery = (() => {});
  jQuery.fn = { offset() { return real; } };
  const w = runShim({ jQuery });
  assert.deepEqual(w.jQuery.fn.offset(), real);
});

test('patches jQuery that appears only after document-start', () => {
  const w = runShim();
  const jQuery = (() => {});
  jQuery.fn = { offset() { return undefined; } };
  w.jQuery = jQuery;
  const off = w.jQuery.fn.offset();
  assert.equal(off.top, 0);
  assert.equal(off.left, 0);
});

test('does not double-patch offset', () => {
  const jQuery = (() => {});
  jQuery.fn = { offset() { return { top: 1, left: 1 }; } };
  const w = runShim({ jQuery });
  const firstPatched = w.jQuery.fn.offset;
  w.__tick();
  assert.equal(w.jQuery.fn.offset, firstPatched);
  assert.equal(w.jQuery.fn.__nimsOffsetPatched, true);
});

test('posts a per-frame content report to the Android bridge', () => {
  const posted = [];
  const fakeWindow = {
    console: { error() {} },
    setInterval() { return 1; },
    clearInterval() {},
    setTimeout(fn) { fn(); return 1; },
    addEventListener() {},
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/hislogin/transactions/jsp/st_desk_homeMenuTab_page.jsp' },
    document: {
      readyState: 'complete',
      addEventListener() {},
      body: {
        querySelectorAll() { return { length: 7 }; },
        innerText: 'Services Special Clinic',
        scrollHeight: 240,
      },
    },
    nimsAndroidBridge: { postMessage(s) { posted.push(s); } },
  };
  const context = { window: fakeWindow, URL };
  context.globalThis = context;
  vm.createContext(context);
  vm.runInContext(shimSource, context);

  assert.equal(posted.length, 1);
  const msg = JSON.parse(posted[0]);
  assert.equal(msg.type, 'nims_frame_debug');
  assert.equal(msg.children, 7);
  assert.equal(msg.height, 240);
  assert.ok(msg.textLen > 0);
  assert.match(msg.url, /st_desk_homeMenuTab_page\.jsp$/);
});

test('after Investigation click calls the child-frame native callMenu contract', () => {
  const posted = [];
  const documentListeners = {};
  let callArgs = null;
  const frame = {
    contentWindow: {
      callMenu(...args) { callArgs = args; },
    },
  };
  const document = {
    readyState: 'complete',
    body: {
      querySelectorAll() { return { length: 5 }; },
      innerText: 'Home Menu',
      scrollHeight: 100,
    },
    addEventListener(type, fn) { documentListeners[type] = fn; },
    getElementById(id) { return id === 'frmMainMenu' ? frame : null; },
    querySelector() { return null; },
  };
  runShim({
    document,
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action' },
    setTimeout(fn) { fn(); return 1; },
    addEventListener() {},
    nimsAndroidBridge: { postMessage(s) { posted.push(JSON.parse(s)); } },
  });

  documentListeners.click({
    target: {
      innerText: 'Investigation',
      getAttribute(name) { return name === 'onclick' ? "menuSelected('Investigation',true)" : ''; },
      parentElement: null,
    },
  });

  assert.deepEqual(callArgs, [
    '/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt',
    'Cr_No_Wise_Result_Report_Printing_New',
  ]);
  assert.ok(posted.some((item) => (item.errors || []).includes('NAV native_cr_open action=called_child_callMenu')));
});
