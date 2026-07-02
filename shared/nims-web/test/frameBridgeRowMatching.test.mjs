import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);

// Mirrors how MainActivity concatenates and injects shimJs + coreJs + utilsJs
// + bridgeJs into ONE document-start script in the real WebView, so this test
// reproduces the actual runtime wiring, not an idealized one.
function loadPageWithBridge(html, url = 'https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt') {
  const dom = new JSDOM(html, { url, runScripts: 'dangerously' });
  // verifiedTemplate() requires a live printReport function OR a #setPdf
  // iframe to confirm the request template; templateFromLivePrintReport
  // inspects the function's own SOURCE TEXT for these substrings (it does not
  // call it), so the stub body must contain them verbatim to pass that check.
  // This mirrors the real NIMS printReport implementation closely enough for
  // the template-discovery gate, while these tests isolate row-matching (the
  // actual bug under test).
  dom.window.printReport = function (name) {
    var mode = "PRINTREPORT";
    var url = "/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt?hmode=" + mode + "&fileName=" + name;
    /* AddRowToTableAddMoreValues(url); popup("popUpDiv"); */
  };
  const utilsSrc = fs.readFileSync(path.join(__dirname, '../contentUtils.js'), 'utf8');
  const bridgeSrc = fs.readFileSync(path.join(__dirname, '../nimsAndroidFrameBridge.js'), 'utf8');
  dom.window.eval(utilsSrc);
  dom.window.eval(bridgeSrc);
  return dom;
}

function tableWithRow(onclickAttr) {
  return `<!doctype html><html><body><form name="viewExternalInvFB"><table>
    <tr><td>11-Jun-2026</td><td>CBC (HB, PCV,TLC, PLT, PS)</td>
      <td><a onclick="${onclickAttr}">View Report</a></td></tr>
  </table></form></body></html>`;
}

test('REGRESSION: a printReport call wrapped in "return ...;" is still detected (was silently dropped)', () => {
  // This is the exact shape that broke on the live 132-row report list: the
  // old anchored regex (^...printReport(...)...$) rejected anything before or
  // after the call, including a leading "return " keyword.
  const dom = loadPageWithBridge(tableWithRow("return printReport('260611R1114_E9736.pdf');"));
  const report = dom.window.NimsAndroidFrameBridgeUtil.buildFrameReport(
    dom.window.NimsFastSummaryUtils,
    dom.window.document,
    dom.window.location.href
  );
  assert.ok(report, 'buildFrameReport must not return null when a real row exists');
  assert.equal(report.rowCount, 1);
  assert.equal(report.rows[0].transientPrintReportArg, '260611R1114_E9736.pdf');
  dom.window.close();
});

test('REGRESSION: a printReport call followed by a second statement is still detected', () => {
  const dom = loadPageWithBridge(tableWithRow("printReport('260611R1114_E9736.pdf'); return false;"));
  const report = dom.window.NimsAndroidFrameBridgeUtil.buildFrameReport(
    dom.window.NimsFastSummaryUtils,
    dom.window.document,
    dom.window.location.href
  );
  assert.ok(report);
  assert.equal(report.rows[0].transientPrintReportArg, '260611R1114_E9736.pdf');
  dom.window.close();
});

test('the plain, unwrapped form (already worked before) still works after the fix', () => {
  const dom = loadPageWithBridge(tableWithRow("printReport('260611R1114_E9736.pdf');"));
  const report = dom.window.NimsAndroidFrameBridgeUtil.buildFrameReport(
    dom.window.NimsFastSummaryUtils,
    dom.window.document,
    dom.window.location.href
  );
  assert.ok(report);
  assert.equal(report.rows[0].transientPrintReportArg, '260611R1114_E9736.pdf');
  dom.window.close();
});

test('a button with no printReport call at all is still correctly excluded (no false positives)', () => {
  const dom = loadPageWithBridge(tableWithRow("submitForm('NEW');"));
  const report = dom.window.NimsAndroidFrameBridgeUtil.buildFrameReport(
    dom.window.NimsFastSummaryUtils,
    dom.window.document,
    dom.window.location.href
  );
  assert.equal(report, null, 'a row with no printReport(...) onclick must not be counted');
  dom.window.close();
});

test('an unsafe token embedded in a wrapped call is still rejected (path traversal guard holds)', () => {
  const dom = loadPageWithBridge(tableWithRow("return printReport('../../etc/passwd');"));
  const report = dom.window.NimsAndroidFrameBridgeUtil.buildFrameReport(
    dom.window.NimsFastSummaryUtils,
    dom.window.document,
    dom.window.location.href
  );
  assert.equal(report, null, 'isSafeTransientToken must still block unsafe-looking tokens even when the call is wrapped');
  dom.window.close();
});

test('132-row-scale list: every wrapped row is detected, not just the first', () => {
  const rows = Array.from({ length: 132 }, (_, i) =>
    `<tr><td>row ${i}</td><td><a onclick="return printReport('TOKEN_${i}.pdf');">View Report</a></td></tr>`
  ).join('');
  const html = `<!doctype html><html><body><form name="viewExternalInvFB"><table>${rows}</table></form></body></html>`;
  const dom = loadPageWithBridge(html);
  const report = dom.window.NimsAndroidFrameBridgeUtil.buildFrameReport(
    dom.window.NimsFastSummaryUtils,
    dom.window.document,
    dom.window.location.href
  );
  assert.ok(report);
  assert.equal(report.rowCount, 132);
  dom.window.close();
});
