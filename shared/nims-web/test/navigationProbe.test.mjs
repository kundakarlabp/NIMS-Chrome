import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../nimsWebviewShim.js', import.meta.url), 'utf8');

function run(fakeWindow) {
  const context = { window: fakeWindow, URL, MutationObserver: fakeWindow.MutationObserver };
  context.globalThis = context;
  vm.createContext(context);
  vm.runInContext(source, context);
}

test('Investigation click emits safe frmMainMenu snapshots', () => {
  const messages = [];
  const listeners = {};
  const child = {
    readyState: 'complete',
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/menu.jsp?x=1' },
    body: { querySelectorAll() { return { length: 4 }; }, innerText: 'Investigation', scrollHeight: 200 },
  };
  const frame = {
    src: 'https://www.nimsts.edu.in/AHIMSG5/menu.jsp?x=1',
    contentDocument: child,
    getAttribute() { return '/AHIMSG5/menu.jsp?x=1'; },
    addEventListener() {},
  };
  const document = {
    readyState: 'complete',
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/home.jsp?x=1' },
    body: { querySelectorAll() { return []; }, innerText: 'Home', scrollHeight: 100 },
    documentElement: {},
    getElementById() { return frame; },
    querySelector() { return null; },
    addEventListener(type, listener) { listeners[type] = listener; },
  };
  run({
    document,
    location: document.location,
    console: { error() {} },
    setTimeout(fn) { fn(); return 1; },
    setInterval() { return 1; },
    clearInterval() {},
    addEventListener() {},
    nimsAndroidBridge: { postMessage(value) { messages.push(JSON.parse(value)); } },
  });
  listeners.click({
    target: {
      innerText: 'Investigation',
      getAttribute() { return "menuSelected('Investigation',true)"; },
      parentElement: null,
    },
  });
  const notes = messages.flatMap((item) => item.errors || []);
  assert.ok(notes.includes('NAV investigation_click'));
  assert.ok(notes.some((note) => note.includes('reason=after_click_3000ms')));
  assert.ok(notes.some((note) => note.includes('children=4')));
  assert.ok(!JSON.stringify(messages).includes('?x=1'));
});

test('frmMainMenu src mutation is reported', () => {
  const messages = [];
  let observer;
  class FakeObserver {
    constructor(callback) { this.callback = callback; }
    observe(target, options) { if (options.attributeFilter) observer = this; }
  }
  const frame = {
    src: 'https://www.nimsts.edu.in/AHIMSG5/old.jsp',
    contentDocument: null,
    getAttribute() { return '/AHIMSG5/old.jsp'; },
    addEventListener() {},
  };
  const document = {
    readyState: 'complete',
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/home.jsp' },
    body: { querySelectorAll() { return []; }, innerText: 'Home', scrollHeight: 100 },
    documentElement: {},
    getElementById() { return frame; },
    querySelector() { return null; },
    addEventListener() {},
  };
  run({
    document,
    location: document.location,
    MutationObserver: FakeObserver,
    console: { error() {} },
    setTimeout(fn) { fn(); return 1; },
    setInterval() { return 1; },
    clearInterval() {},
    addEventListener() {},
    nimsAndroidBridge: { postMessage(value) { messages.push(JSON.parse(value)); } },
  });
  assert.ok(observer);
  frame.src = 'https://www.nimsts.edu.in/AHIMSG5/new.jsp';
  frame.getAttribute = () => '/AHIMSG5/new.jsp';
  observer.callback([{ attributeName: 'src' }]);
  const notes = messages.flatMap((item) => item.errors || []);
  assert.ok(notes.some((note) => note.includes('reason=frmMainMenu_src_changed')));
});
