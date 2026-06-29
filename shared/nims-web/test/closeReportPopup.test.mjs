import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const corePath = '../../../shared/nims-web/nimsReportCore.js';

function loadCore(html, url = 'https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt') {
  const dom = new JSDOM(html, { url, runScripts: 'dangerously' });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.location = dom.window.location;
  delete require.cache[require.resolve(corePath)];
  const core = require(corePath);
  return { dom, core };
}

test('closeReportPopup reports not_present when no popup exists', () => {
  const { core, dom } = loadCore('<!doctype html><html><body></body></html>');
  const result = core.closeReportPopup(dom.window.document);
  assert.equal(result.ok, true);
  assert.equal(result.action, 'not_present');
});

test('closeReportPopup uses the page\'s own popup() function when available (matches its exact toggle contract)', () => {
  const { core, dom } = loadCore(
    '<!doctype html><html><body><div id="popUpDiv" style="display:block"><iframe id="setPdf" src="https://www.nimsts.edu.in/x.pdf"></iframe></div></body></html>'
  );
  let calledWith = null;
  dom.window.popup = function (name) { calledWith = name; dom.window.document.getElementById('popUpDiv').style.display = 'none'; };
  const result = core.closeReportPopup(dom.window.document);
  assert.equal(result.ok, true);
  assert.equal(result.action, 'closed_via_page_popup');
  assert.equal(calledWith, 'popUpDiv');
});

test('closeReportPopup falls back to a direct style change and blanks the iframe src when no page function exists', () => {
  const { core, dom } = loadCore(
    '<!doctype html><html><body><div id="popUpDiv" style="display:block"><iframe id="setPdf" src="https://www.nimsts.edu.in/x.pdf"></iframe></div></body></html>'
  );
  const result = core.closeReportPopup(dom.window.document);
  assert.equal(result.ok, true);
  assert.equal(result.action, 'closed_via_style_fallback');
  const div = dom.window.document.getElementById('popUpDiv');
  assert.equal(div.style.display, 'none');
  const iframe = dom.window.document.getElementById('setPdf');
  assert.equal(iframe.getAttribute('src'), 'about:blank');
});

test('closeReportPopup is a no-op (reports already_closed) when the popup is already hidden', () => {
  const { core, dom } = loadCore(
    '<!doctype html><html><body><div id="popUpDiv" style="display:none"></div></body></html>'
  );
  const result = core.closeReportPopup(dom.window.document);
  assert.equal(result.ok, true);
  assert.equal(result.action, 'already_closed');
});
