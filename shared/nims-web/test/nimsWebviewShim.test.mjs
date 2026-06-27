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
    setTimeout(fn) { timeouts.push(fn); return timeouts.length; },
    setInterval(fn) { intervals.push(fn); return intervals.length; },
    clearInterval() {},
    ...windowExtras,
  };
  if (!('top' in fakeWindow)) fakeWindow.top = fakeWindow;
  const context = { window: fakeWindow, URL };
  context.globalThis = context;
  vm.createContext(context);
  vm.runInContext(shimSource, context);
  fakeWindow.__tickIntervals = () => intervals.forEach((fn) => fn());
  fakeWindow.__runTimeouts = (limit = 200) => {
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

function basicDocument() {
  return {
    readyState: 'complete',
    addEventListener() {},
    getElementById() { return null; },
    querySelector() { return null; },
    body: {
      querySelectorAll() { return { length: 0 }; },
      innerText: '',
      scrollHeight: 0,
    },
  };
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

test('patches jQuery.offset so empty-set .left cannot throw', () => {
  const jQuery = (() => {});
  jQuery.fn = { offset() { return undefined; } };
  const w = runShim({ jQuery });
  assert.deepEqual(w.jQuery.fn.offset(), { top: 0, left: 0 });
});

test('preserves a real offset object', () => {
  const real = { top: 10, left: 25 };
  const jQuery = (() => {});
  jQuery.fn = { offset() { return real; } };
  const w = runShim({ jQuery });
  assert.deepEqual(w.jQuery.fn.offset(), real);
});

test('patches jQuery assigned after document-start', () => {
  const w = runShim();
  const jQuery = (() => {});
  jQuery.fn = { offset() { return undefined; } };
  w.jQuery = jQuery;
  assert.equal(w.jQuery.fn.offset().left, 0);
});

test('posts a per-frame structural report', () => {
  const posted = [];
  const document = basicDocument();
  document.body = {
    querySelectorAll() { return { length: 7 }; },
    innerText: 'Services Special Clinic',
    scrollHeight: 240,
  };
  const w = runShim({
    document,
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/hislogin/transactions/jsp/st_desk_homeMenuTab_page.jsp' },
    addEventListener() {},
    nimsAndroidBridge: { postMessage(value) { posted.push(JSON.parse(value)); } },
  });
  w.__runTimeouts();
  const frame = posted.find((item) => item.type === 'nims_frame_debug');
  assert.ok(frame);
  assert.equal(frame.children, 7);
  assert.equal(frame.height, 240);
  assert.match(frame.url, /st_desk_homeMenuTab_page\.jsp$/);
});

test('Investigation click clears stale rows, waits for the exact anchor, then clicks it', () => {
  const posted = [];
  const listeners = {};
  let anchorClicks = 0;
  let fallbackCalls = 0;
  let reportFrameReady = false;

  const anchor = {
    click() {
      anchorClicks += 1;
      reportFrameReady = true;
    },
  };
  const childDocument = {
    readyState: 'complete',
    getElementById(id) { return id === 'Cr_No_Wise_Result_Report_Printing_New' ? anchor : null; },
    querySelectorAll() { return []; },
  };
  const menuFrame = {
    contentDocument: childDocument,
    contentWindow: { callMenu() { fallbackCalls += 1; } },
  };
  const document = basicDocument();
  document.addEventListener = (type, fn) => { listeners[type] = fn; };
  document.getElementById = (id) => {
    if (id === 'frmMainMenu') return menuFrame;
    if (id === 'Cr No Wise Result Report Printing New_iframe' && reportFrameReady) return {};
    return null;
  };

  const w = runShim({
    document,
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action' },
    addEventListener() {},
    nimsAndroidBridge: { postMessage(value) { posted.push(JSON.parse(value)); } },
  });

  listeners.click({
    target: {
      innerText: 'Investigation',
      getAttribute(name) { return name === 'onclick' ? "menuSelected('Investigation',true)" : ''; },
      parentElement: null,
    },
  });
  w.__runTimeouts();

  assert.equal(anchorClicks, 1);
  assert.equal(fallbackCalls, 0);
  assert.ok(posted.some((item) => item.type === 'nims_report_frame' && item.rowCount === 0 && item.clearReason === 'investigation_click'));
  assert.ok(posted.some((item) => (item.errors || []).includes('NAV native_cr_open action=clicked_exact_cr_anchor')));
  assert.ok(posted.some((item) => (item.errors || []).includes('NAV native_cr_open action=clicked_exact_cr_anchor report_iframe=ready')));
});

test('does not call child callMenu while the Investigation menu document is still loading', () => {
  const listeners = {};
  let fallbackCalls = 0;
  const childDocument = {
    readyState: 'loading',
    getElementById() { return null; },
    querySelectorAll() { return []; },
  };
  const document = basicDocument();
  document.addEventListener = (type, fn) => { listeners[type] = fn; };
  document.getElementById = (id) => id === 'frmMainMenu'
    ? { contentDocument: childDocument, contentWindow: { callMenu() { fallbackCalls += 1; } } }
    : null;

  const w = runShim({
    document,
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action' },
    addEventListener() {},
  });
  listeners.click({ target: { innerText: 'Investigation', getAttribute() { return ''; }, parentElement: null } });
  w.__runTimeouts(10);
  assert.equal(fallbackCalls, 0);
});

test('defers a legacy addTab contentDocument race and retries', () => {
  let calls = 0;
  const document = basicDocument();
  const w = runShim({
    document,
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action' },
    addEventListener() {},
    addTab() {
      calls += 1;
      if (calls === 1) throw new TypeError("Cannot read properties of undefined (reading 'contentDocument')");
      return 'ok';
    },
  });

  assert.doesNotThrow(() => w.addTab('Cr No Wise Result Report Printing New'));
  w.__runTimeouts();
  assert.equal(calls, 2);
});
