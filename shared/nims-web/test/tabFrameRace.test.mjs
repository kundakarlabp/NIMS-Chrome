import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../nimsWebviewShim.js', import.meta.url), 'utf8');
const frameId = 'Cr No Wise Result Report Printing New_iframe';
const endpoint = 'https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt';

function harness(frame = null) {
  const timeouts = [];
  let assigned = '';
  const document = {
    readyState: 'complete',
    body: { querySelectorAll() { return []; }, innerText: 'Home', scrollHeight: 1 },
    addEventListener() {},
    getElementById(id) { return id === frameId ? frame : null; },
    querySelector() { return null; },
  };
  const window = {
    document,
    console: { error() {} },
    location: { href: 'https://www.nimsts.edu.in/AHIMSG5/home.jsp', assign(value) { assigned = value; } },
    setTimeout(fn) { timeouts.push(fn); return timeouts.length; },
    setInterval() { return 1; },
    clearInterval() {},
    addEventListener() {},
  };
  window.top = window;
  const context = { window, URL };
  context.globalThis = context;
  vm.createContext(context);
  vm.runInContext(source, context);
  return {
    window,
    assigned: () => assigned,
    flush() {
      let count = 0;
      while (timeouts.length && count < 100) {
        timeouts.shift()();
        count += 1;
      }
    },
  };
}

test('uses the NIMS-generated URL when the CR iframe was not created', () => {
  const h = harness();
  const finalUrl = endpoint + '?x=1';
  h.window.addTab = function () {};
  h.window.addTab('Cr No Wise Result Report Printing New', finalUrl);
  h.flush();
  assert.equal(h.assigned(), finalUrl);
});

test('never falls back to the endpoint without a generated query', () => {
  const h = harness();
  h.window.addTab = function () {};
  h.window.addTab('Cr No Wise Result Report Printing New', endpoint);
  h.flush();
  assert.equal(h.assigned(), '');
});

test('does not leave the shell when the exact CR iframe is ready', () => {
  const frame = {
    id: frameId,
    name: frameId,
    contentDocument: {
      body: { innerText: 'CR No' },
      querySelector(selector) { return selector.includes('patCrNo') ? {} : null; },
      querySelectorAll() { return []; },
    },
    getAttribute() { return endpoint + '?x=1'; },
    setAttribute() {},
  };
  const h = harness(frame);
  h.window.addTab = function () {};
  h.window.addTab('Cr No Wise Result Report Printing New', endpoint + '?x=1');
  h.flush();
  assert.equal(h.assigned(), '');
});
