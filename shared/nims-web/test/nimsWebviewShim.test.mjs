import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const shimSource = readFileSync(new URL('../nimsWebviewShim.js', import.meta.url), 'utf8');

// Build a minimal fake window with controllable timers and run the shim in it.
function runShim(windowExtras = {}) {
  const timers = [];
  const fakeWindow = {
    console: { error() {} },
    setInterval(fn) { timers.push(fn); return timers.length; },
    clearInterval() {},
    ...windowExtras,
  };
  const context = { window: fakeWindow };
  context.globalThis = context;
  vm.createContext(context);
  vm.runInContext(shimSource, context);
  // Expose a way to advance the jQuery-detection poller.
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
  const fn = { offset() { return undefined; } };
  const jQuery = (() => {}) ;
  jQuery.fn = fn;
  const w = runShim({ jQuery });
  // jQuery present at start -> patched immediately.
  const result = w.jQuery.fn.offset();
  assert.equal(result.top, 0);
  assert.equal(result.left, 0);
  // The exact failing expression from tabmenu.js:576 must now be safe.
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
  const w = runShim(); // no jQuery yet
  const jQuery = (() => {});
  jQuery.fn = { offset() { return undefined; } };
  w.jQuery = jQuery; // assignment is intercepted and patched synchronously
  const off = w.jQuery.fn.offset();
  assert.equal(off.top, 0);
  assert.equal(off.left, 0);
});

test('does not double-patch offset', () => {
  const jQuery = (() => {});
  let calls = 0;
  jQuery.fn = { offset() { calls += 1; return { top: 1, left: 1 }; } };
  const w = runShim({ jQuery });
  const firstPatched = w.jQuery.fn.offset;
  w.__tick(); // subsequent poller ticks must not re-wrap
  assert.equal(w.jQuery.fn.offset, firstPatched);
  assert.equal(w.jQuery.fn.__nimsOffsetPatched, true);
});

test('posts a per-frame content report to the Android bridge', () => {
  const posted = [];
  const listeners = {};
  const fakeWindow = {
    console: { error() {} },
    setInterval() { return 1; },
    clearInterval() {},
    setTimeout(fn) { fn(); return 1; }, // run synchronously
    addEventListener(type, fn) { listeners[type] = fn; },
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/hislogin/transactions/jsp/st_desk_homeMenuTab_page.jsp' },
    document: {
      readyState: 'complete',
      body: {
        querySelectorAll() { return { length: 7 }; },
        innerText: '  Services Special Clinic  ',
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
