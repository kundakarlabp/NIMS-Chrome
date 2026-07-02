import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { JSDOM } from 'jsdom';

const source = readFileSync(
  new URL('../../mobile/android/app/src/main/assets/nimsOnDemandExtractor.js', import.meta.url),
  'utf8'
);

function run(html, path = '/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt') {
  const dom = new JSDOM(html, {
    url: `https://www.nimsts.edu.in${path}`,
    runScripts: 'outside-only'
  });
  dom.window.printReport = function printReport(name) {
    const endpoint = '/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt';
    const mode = 'PRINTREPORT';
    const parameter = 'fileName';
    return `${endpoint}?hmode=${mode}&${parameter}=${name}`;
  };
  const context = dom.getInternalVMContext();
  context.globalThis = dom.window;
  const raw = vm.runInContext(source, context);
  const parsed = JSON.parse(raw);
  dom.window.close();
  return parsed;
}

test('extractor is one-shot and does not patch or navigate the portal', () => {
  assert.doesNotMatch(source, /setInterval\s*\(/);
  assert.doesNotMatch(source, /MutationObserver/);
  assert.doesNotMatch(source, /\.click\s*\(/);
  assert.doesNotMatch(source, /\.submit\s*\(/);
  assert.doesNotMatch(source, /(?:window|root)\.jQuery\s*=/);
  assert.doesNotMatch(source, /addDocumentStartJavaScript/);
});

test('extracts safe report metadata and a verified report template', () => {
  const result = run(`
    <html><body>
      <table>
        <tr><td>28-06-2026</td><td>Biochemistry</td><td>Renal Function Test</td>
          <td><button onclick="printReport('123456_20260628.pdf')">View Report</button></td>
        </tr>
      </table>
    </body></html>
  `);

  assert.equal(result.ok, true);
  assert.equal(result.pageKind, 'cr_results');
  assert.equal(result.rows.length, 1);
  assert.equal(result.rows[0].transientPrintReportArg, '123456_20260628.pdf');
  assert.equal(result.rows[0].report_type, 'rft');
  assert.equal(result.template.pathname, '/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt');
  assert.equal(result.template.modeParamValue, 'PRINTREPORT');
});

test('rejects unsafe report arguments and identifies the CR search page', () => {
  const result = run(`
    <html><body>
      <form><input name="patCrNo"></form>
      <table><tr><td>Report</td><td><button onclick="printReport('../secret.pdf')">View Report</button></td></tr></table>
    </body></html>
  `);

  assert.equal(result.ok, true);
  assert.equal(result.pageKind, 'cr_search');
  assert.equal(result.rows.length, 0);
});
