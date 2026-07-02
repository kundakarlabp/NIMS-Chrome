import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const corePath = '../../../shared/nims-web/nimsReportCore.js';

function loadCore(html, url = 'https://www.nimsts.edu.in/AHIMSG5/home') {
  const dom = new JSDOM(html, { url, runScripts: 'dangerously' });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.location = dom.window.location;
  delete require.cache[require.resolve(corePath)];
  const core = require(corePath);
  return { dom, core };
}

test('frameRenderProbe reports an empty top-level body honestly', () => {
  const { core, dom } = loadCore('<!doctype html><html><body></body></html>');
  const result = core.frameRenderProbe(dom.window.document);
  assert.equal(result.frameCount, 1);
  const top = result.frames[0];
  assert.equal(top.depth, 0);
  assert.equal(top.bodyChildCount, 0);
  assert.equal(top.bodyTextLength, 0);
  assert.equal(top.injectionRan, false, 'shim was not run in this jsdom fixture, so this must be false, not assumed true');
});

test('frameRenderProbe reports populated body content and injection marker', () => {
  const { core, dom } = loadCore('<!doctype html><html><body><div>Hello NIMS</div></body></html>');
  dom.window.__nimsInjectedAt = Date.now();
  const result = core.frameRenderProbe(dom.window.document);
  const top = result.frames[0];
  assert.equal(top.bodyChildCount, 1);
  assert.ok(top.bodyTextLength > 0);
  assert.equal(top.injectionRan, true);
});

test('frameRenderProbe surfaces a captured uncaught error for the affected window only', () => {
  const { core, dom } = loadCore('<!doctype html><html><body></body></html>');
  core.installErrorCapture(dom.window);
  dom.window.onerror('boom', 'https://www.nimsts.edu.in/x.js', 12, 3, new Error('boom'));
  const result = core.frameRenderProbe(dom.window.document);
  const top = result.frames[0];
  assert.ok(top.lastUncaughtError);
  assert.equal(top.lastUncaughtError.message, 'boom');
  assert.equal(top.lastUncaughtError.line, 12);
});

test('installErrorCapture chains through to a pre-existing page onerror instead of replacing it', () => {
  const { core, dom } = loadCore('<!doctype html><html><body></body></html>');
  let pageHandlerCalls = 0;
  dom.window.onerror = function () { pageHandlerCalls += 1; return true; };
  core.installErrorCapture(dom.window);
  dom.window.onerror('boom2', 'https://www.nimsts.edu.in/y.js', 1, 1, new Error('boom2'));
  assert.equal(pageHandlerCalls, 1, 'the original page onerror must still run');
  assert.equal(dom.window.__nimsLastError.message, 'boom2');
});

test('installErrorCapture is idempotent (does not double-chain on repeated calls)', () => {
  const { core, dom } = loadCore('<!doctype html><html><body></body></html>');
  core.installErrorCapture(dom.window);
  core.installErrorCapture(dom.window);
  let calls = 0;
  const originalOnError = dom.window.onerror;
  dom.window.onerror = function (...args) { calls += 1; return originalOnError.apply(this, args); };
  dom.window.onerror('once', 'https://www.nimsts.edu.in/z.js', 1, 1, new Error('once'));
  assert.equal(calls, 1);
});
