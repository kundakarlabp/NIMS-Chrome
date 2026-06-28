import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../nimsAndroidFrameBridge.js', import.meta.url), 'utf8');

function loadApi() {
  const module = { exports: {} };
  const context = { module, exports: module.exports, URL };
  context.globalThis = context;
  vm.createContext(context);
  vm.runInContext(source, context);
  return module.exports;
}

function makeDocument(visibility = 'visible') {
  const view = {
    getComputedStyle(node) {
      return node.styleState || { display: 'table-row', visibility: 'visible', opacity: '1' };
    },
  };
  const control = {
    hidden: false,
    parentElement: null,
    ownerDocument: null,
    styleState: { display: 'inline', visibility: 'visible', opacity: '1' },
    getAttribute(name) { return name === 'onclick' ? "printReport('token-1')" : null; },
  };
  const row = {
    hidden: false,
    parentElement: null,
    ownerDocument: null,
    styleState: { display: 'table-row', visibility, opacity: '1' },
    querySelectorAll(selector) { return selector === '[onclick]' ? [control] : []; },
  };
  const doc = {
    location: { href: 'https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt' },
    defaultView: view,
    querySelectorAll(selector) { return selector === 'tr' ? [row] : []; },
  };
  row.ownerDocument = doc;
  control.ownerDocument = doc;
  control.parentElement = row;
  return doc;
}

function utils() {
  return {
    extractReportRows() {
      return [{ row_index: 0, report_name: 'CBC', report_type: 'cbc', report_tags: ['cbc'] }];
    },
    safeRuntimeRow(row) { return { ...row }; },
    getTransientReportRequestPayload() {
      return { ok: true, transient_print_report_arg: 'token-1' };
    },
  };
}

test('collapsed report rows are not announced', () => {
  const api = loadApi();
  const report = api.buildFrameReport(utils(), makeDocument('collapse'), 'www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt');
  assert.equal(report, null);
});

test('visible report rows include the safe template and transient argument', () => {
  const api = loadApi();
  const report = api.buildFrameReport(utils(), makeDocument('visible'), 'www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt');
  assert.ok(report);
  assert.equal(report.rowCount, 1);
  assert.equal(report.rows[0].transientPrintReportArg, 'token-1');
  assert.equal(report.template.pathname, '/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt');
});

test('non-report pages cannot announce report-like rows', () => {
  const api = loadApi();
  const doc = makeDocument('visible');
  doc.location.href = 'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action';
  assert.equal(api.buildFrameReport(utils(), doc, 'www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action'), null);
});
