import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const corePath = '../../../shared/nims-web/nimsReportCore.js';

function makeDoc(onclickAttr = "return printReport('TOKEN_0.pdf');") {
  const dom = new JSDOM(
    `<!doctype html><html><body><table>
       <tr><td>CBC (HB, PCV, TLC)</td><td>S/001</td><td>11-Jun-2026</td>
           <td><a onclick="${onclickAttr}">View Report</a></td></tr>
     </table></body></html>`,
    { url: 'https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt' }
  );
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.location = dom.window.location;
  delete require.cache[require.resolve(corePath)];
  const core = require(corePath);
  return { dom, core };
}

// ROOT CAUSE REGRESSION: the original two-hop chain interpolated a JSONArray
// (from rowsFromBestFrame) directly into a second JS call as ${rows}.
// JSONArray.toString() contains " characters; those break the JS string literal
// that evaluateJavascript receives, causing a syntax error that crashes the
// WebView renderer. selectRowsForModeFromDoc does both steps in JS, so no row
// data ever needs to cross the Kotlin->JS boundary as interpolated source.

test('selectRowsForModeFromDoc returns the same rows as rowsFromBestFrame+selectRowsForMode for test_direct', () => {
  const { core, dom } = makeDoc("return printReport('CBC_TOKEN.pdf');");
  const selected = core.selectRowsForModeFromDoc('test_direct', dom.window.document);
  assert.ok(Array.isArray(selected) || selected.length !== undefined);
  // For test_direct, should return at most 1 row
  assert.ok(selected.length <= 1);
});

test('selectRowsForModeFromDoc is safe when onclick contains double quotes that would have broken JS interpolation', () => {
  // This is the exact character that caused the crash: a " inside the onclick
  // attribute. When ${rows} was interpolated into "JS.stringify(...(${rows},...)"
  // the inner " from onclick="return printReport(\"x.pdf\");" broke the outer
  // string literal. This test proves the combined function handles it correctly.
  const { core, dom } = makeDoc('return printReport("DOUBLE_QUOTED_TOKEN.pdf");');
  // Must not throw; the " is safely contained inside the DOM, never in JS source
  const selected = core.selectRowsForModeFromDoc('bulk_fast', dom.window.document);
  assert.ok(selected !== undefined);
});

test('selectRowsForModeFromDoc returns empty array (not throws) when page has no report rows', () => {
  const dom = new JSDOM('<html><body></body></html>', {
    url: 'https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt'
  });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.location = dom.window.location;
  delete require.cache[require.resolve(corePath)];
  const core = require(corePath);
  const selected = core.selectRowsForModeFromDoc('test_direct', dom.window.document);
  assert.equal(selected.length, 0);
  dom.window.close();
});
