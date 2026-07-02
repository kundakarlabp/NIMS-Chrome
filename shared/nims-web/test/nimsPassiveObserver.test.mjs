import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { JSDOM } from 'jsdom';

const source = readFileSync(new URL('../nimsPassiveObserver.js', import.meta.url), 'utf8');

function load(html, path = '/AHIMSG5/hissso/loginLogin.action') {
  const dom = new JSDOM(html, {
    url: `https://www.nimsts.edu.in${path}`,
    runScripts: 'outside-only'
  });
  const { window } = dom;
  window.__NIMS_PASSIVE_OBSERVER_INSTALLED__ = true;
  const context = dom.getInternalVMContext();
  context.globalThis = window;
  vm.runInContext(source, context);
  return { window, api: window.NimsPassiveObserverUtil };
}

test('classifies login without changing portal globals', () => {
  const { window, api } = load('<html><body><form><input name="user"><input type="password"></form></body></html>');
  assert.equal(api.pageKind(window.document), 'login');
  assert.equal(window.jQuery, undefined);
  assert.equal(window.ajaxCompleteTab, undefined);
  window.close();
});

test('classifies the genuine CR search form', () => {
  const { window, api } = load(
    '<html><body><form name="viewExternalInvFB" action="/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt"><input name="patCrNo"></form></body></html>',
    '/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt'
  );
  assert.equal(api.hasCrSearchForm(window.document), true);
  assert.equal(api.pageKind(window.document), 'cr_search');
  window.close();
});

test('extracts a sanitized report row without helper libraries', () => {
  const { window, api } = load(
    '<html><body><table><tr><td>ignore</td></tr><tr><td>28-Jun-2026</td><td>Biochemistry</td><td>Renal Function Test</td><td><button onclick="printReport(\'synthetic_report_1.pdf\')">View Report</button></td></tr></table></body></html>',
    '/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt'
  );
  window.printReport = function printReport(name) {
    return '/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt?hmode=PRINTREPORT&fileName=' + name;
  };
  const report = api.buildReport(window.document);
  assert.equal(report.rowCount, 1);
  assert.equal(report.rows[0].row_index, 1);
  assert.equal(report.rows[0].report_name, 'Renal Function Test');
  assert.equal(report.rows[0].transientPrintReportArg, 'synthetic_report_1.pdf');
  assert.equal(report.rows[0].onclick, undefined);
  assert.equal(report.rows[0].source_url, undefined);
  assert.equal(report.template.pathname, '/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt');
  window.close();
});

test('rejects unsafe report references', () => {
  const { window, api } = load('<html><body></body></html>');
  assert.equal(api.safeTransientToken('../secret.pdf'), '');
  assert.equal(api.safeTransientToken('https://example.invalid/report.pdf'), '');
  assert.equal(api.safeTransientToken('safe_report-1.pdf'), 'safe_report-1.pdf');
  window.close();
});

test('avoids expensive layout reads and portal mutations', () => {
  assert.doesNotMatch(source, /getComputedStyle\s*\(/);
  assert.doesNotMatch(source, /NimsFastSummaryUtils/);
  assert.doesNotMatch(source, /NimsReportCore/);
  assert.doesNotMatch(source, /\.click\s*\(/);
  assert.doesNotMatch(source, /\.submit\s*\(/);
  assert.doesNotMatch(source, /attributeFilter|attributes\s*:\s*true/);
});
