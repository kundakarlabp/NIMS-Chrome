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
  // STRENGTHENED: the whole point of this function is that the token is
  // already attached -- a length check alone would not have caught the
  // original defect (token silently missing/wrong).
  assert.equal(selected[0].transientPrintReportArg, 'CBC_TOKEN.pdf');
});

test('selectRowsForModeFromDoc is safe when onclick contains double quotes that would have broken JS interpolation', () => {
  // This is the exact character that caused the crash: a " inside the onclick
  // attribute's OWN argument value. NIMS markup can use single OR double
  // quotes for the printReport(...) argument; either way, that text ends up
  // inside an HTML onclick="..." attribute, so a literal " in the argument
  // must be HTML-entity-escaped (&quot;) by the page itself to be valid HTML
  // in the first place -- which is what real browsers/NIMS would actually
  // produce. (The attribute is double-quoted here specifically because that
  // is what makeDoc() uses, matching real NIMS markup.)
  const { core, dom } = makeDoc('return printReport(&quot;DOUBLE_QUOTED_TOKEN.pdf&quot;);');
  // Must not throw; the " is safely contained inside the DOM attribute value,
  // never concatenated into JS source on the Kotlin side.
  const selected = core.selectRowsForModeFromDoc('bulk_fast', dom.window.document);
  assert.ok(selected !== undefined);
  // STRENGTHENED: confirm the token was actually extracted correctly despite
  // the embedded double quotes, not just that nothing threw.
  assert.equal(selected[0].transientPrintReportArg, 'DOUBLE_QUOTED_TOKEN.pdf');
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

// ROOT CAUSE REGRESSION (the row-relocation defect, distinct from the JS-
// interpolation crash above): a row must never be re-located by row_index in
// a LATER, separate call after the DOM has been mutated -- which is exactly
// what NIMS's own printReport()/AddRowToTableAddMoreValues() does (inserts a
// new row for the #setPdf iframe) the moment a report is clicked. This test
// proves clickFirstReportForMode captures the token in the SAME tick as the
// click, before any such mutation, so it is immune to this regardless of
// what happens to the table afterward.
test('clickFirstReportForMode captures the token BEFORE the click, immune to DOM mutation the click itself causes', () => {
  const html = `<!doctype html><html><body><table id="reportTable">
       <tr><td>CBC</td><td><a onclick="return printReport('ROW0_TOKEN.pdf');">View Report</a></td></tr>
     </table></body></html>`;
  const dom = new JSDOM(html, { url: 'https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt' });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.location = dom.window.location;
  delete require.cache[require.resolve(corePath)];
  const core = require(corePath);

  // Simulate NIMS's printReport() inserting a new row at the TOP of the table
  // as a side effect of the click -- this is what shifts every row_index by
  // one, the exact mechanism that made later position-based re-lookups
  // resolve to the wrong row (or no row) in the original defect.
  dom.window.printReport = function () {
    const table = dom.window.document.getElementById('reportTable');
    const newRow = table.insertRow(0);
    newRow.innerHTML = '<td colspan="2"><iframe id="setPdf" src="about:blank"></iframe></td>';
  };

  const result = core.clickFirstReportForMode('test_direct', dom.window.document);
  assert.equal(result.ok, true);
  // The token must be correct EVEN THOUGH the row that was originally at
  // row_index 0 is now at row_index 1 after the simulated mutation.
  assert.equal(result.row.transientPrintReportArg, 'ROW0_TOKEN.pdf');
  dom.window.close();
});

test('clickFirstReportForMode fails closed (no token) when the row has no printReport button, rather than returning a stale/wrong token', () => {
  const html = `<!doctype html><html><body><table>
       <tr><td>CBC</td><td><a onclick="submitForm('NEW');">Cancel</a></td></tr>
     </table></body></html>`;
  const dom = new JSDOM(html, { url: 'https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt' });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.location = dom.window.location;
  delete require.cache[require.resolve(corePath)];
  const core = require(corePath);
  const result = core.clickFirstReportForMode('test_direct', dom.window.document);
  assert.equal(result.ok, false);
});
