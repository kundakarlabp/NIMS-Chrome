import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../nimsWebviewShim.js', import.meta.url), 'utf8');

function runtimeFixture(extra = {}) {
  const queue = [];
  const documentListeners = new Map();
  const messages = [];
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
    }
  };
  const win = {
    console: { error() {} },
    document,
    location: {
      href: document.location.href,
      hostname: 'www.nimsts.edu.in',
      pathname: '/AHIMSG5/hissso/loginLogin.action',
      protocol: 'https:'
    },
    nimsAndroidBridge: { postMessage(value) { messages.push(JSON.parse(value)); } },
    addEventListener() {},
    setTimeout(fn) { queue.push(fn); return queue.length; },
    ...extra
  };
  win.document = document;
  win.top = win;
  document.defaultView = win;
  const context = { window: win, URL, Date, Object, JSON };
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
  win.runtimeMessages = messages;
  return win;
}

test('posts sanitized readiness for the active frame', () => {
  const jq = () => {};
  jq.fn = { jquery: '3.7.1', offset: () => undefined };
  const win = runtimeFixture({ jQuery: jq, $: jq });
  win.flush();
  const ready = win.runtimeMessages.find(message => message.type === 'nims_runtime_ready');
  assert.ok(ready);
  assert.equal(ready.path, '/AHIMSG5/hissso/loginLogin.action');
  assert.equal(ready.jqueryVersion, '3.7.1');
  assert.equal(ready.offsetPatched, true);
  assert.equal(ready.dateTimeReady, true);
  assert.equal(Object.hasOwn(ready, 'href'), false);
});

test('reports and rethrows non-race ajaxCompleteTab errors', () => {
  const win = runtimeFixture({ ajaxCompleteTab() { throw new Error('runtime-test-error'); } });
  win.flush();
  const frame = {
    tagName: 'IFRAME',
    id: 'Cr No Wise Result Report Printing New_iframe',
    ownerDocument: win.document,
    isConnected: true,
    contentDocument: { readyState: 'complete' },
    getAttribute: () => '/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt'
  };
  win.document.fire('load', frame);
  assert.throws(() => win.ajaxCompleteTab(), /runtime-test-error/);
  assert.ok(win.runtimeMessages.some(message => message.type === 'nims_runtime_error'));
});
