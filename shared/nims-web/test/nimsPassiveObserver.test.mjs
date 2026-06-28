import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { JSDOM } from 'jsdom';

const source = readFileSync(new URL('../nimsPassiveObserver.js', import.meta.url), 'utf8');

function load(html, path = '/AHIMSG5/hissso/loginLogin.action', utils = null) {
  const dom = new JSDOM(html, {
    url: `https://www.nimsts.edu.in${path}`,
    runScripts: 'outside-only'
  });
  const { window } = dom;
  if (utils) window.NimsFastSummaryUtils = utils;
  const context = dom.getInternalVMContext();
  context.globalThis = window;
  vm.runInContext(source, context);
  return { window, api: window.NimsPassiveObserverUtil };
}

test('classifies a manual login page without changing portal globals', () => {
  const { window, api } = load('<html><body><form><input name="user"><input type="password"></form></body></html>');
  assert.equal(api.pageKind(window.document), 'login');
  assert.equal(window.date_time, undefined);
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

test('builds a sanitized report announcement from the owning frame', () => {
  const extracted = [{
    row_index: 4,
    view_report_button_index: 1,
    date_sent: '28-06-2026',
    department: 'Biochemistry',
    report_name: 'Renal Function Test',
    report_type: 'rft',
    report_tags: ['rft']
  }];
  const utils = {
    extractReportRows() { return extracted; },
    safeRuntimeRow(row) { return { ...row }; },
    getTransientReportRequestPayload() { return { transient_print_report_arg: 'synthetic_report_1.pdf' }; },
    getSafeSetPdfTemplate() {
      return {
        discovered: true,
        origin: 'https://www.nimsts.edu.in',
        pathname: '/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt'
      };
    }
  };
  const { window, api } = load(
    '<html><body><table><tr><td>Renal Function Test</td><td>View Report</td></tr></table></body></html>',
    '/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt',
    utils
  );
  const report = api.buildReport(window.document);
  assert.equal(report.type, 'nims_report_frame');
  assert.equal(report.pageKind, 'cr_results');
  assert.equal(report.rowCount, 1);
  assert.equal(report.rows[0].report_name, 'Renal Function Test');
  assert.equal(report.rows[0].transientPrintReportArg, 'synthetic_report_1.pdf');
  assert.equal(report.rows[0].onclick, undefined);
  assert.equal(report.rows[0].source_url, undefined);
  assert.equal(report.template.pathname, '/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt');
  window.close();
});

test('rejects unsafe transient report references', () => {
  const { window, api } = load('<html><body></body></html>');
  assert.equal(api.safeTransientToken('../secret.pdf'), '');
  assert.equal(api.safeTransientToken('https://example.invalid/report.pdf'), '');
  assert.equal(api.safeTransientToken('safe_report-1.pdf'), 'safe_report-1.pdf');
  window.close();
});

test('observer source contains no portal patching or automatic navigation', () => {
  assert.doesNotMatch(source, /(?:window|root)\.jQuery\s*=/);
  assert.doesNotMatch(source, /(?:window|root)\.date_time\s*=/);
  assert.doesNotMatch(source, /(?:window|root)\.ajaxCompleteTab\s*=/);
  assert.doesNotMatch(source, /\.click\s*\(/);
  assert.doesNotMatch(source, /\.submit\s*\(/);
});
