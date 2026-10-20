import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const background = fs.readFileSync(new URL('../src/background.js', import.meta.url), 'utf8');
const bridge = fs.readFileSync(new URL('../src/nimsSessionBridge.js', import.meta.url), 'utf8');

function isolate(source, name, env = {}) {
  const index = source.search(new RegExp('\\b(?:async\\s+)?function\\s+' + name + '\\s*\\('));
  assert.ok(index >= 0, 'Missing ' + name);
  const rest = source.slice(index);
  const next = /\n[ \t]*(?:async\s+)?function\s+[A-Za-z]\w*\s*\(/.exec(rest.slice(1));
  const context = vm.createContext(env);
  vm.runInContext(rest.slice(0, next ? next.index + 1 : rest.length), context);
  return context[name];
}

test('report row reader prefers rich extractor that preserves source URL metadata', () => {
  const rich = bridge.indexOf('window.NimsFastSummaryUtils.extractReportRows');
  const core = bridge.indexOf('core.extractReportRows');
  assert.ok(rich >= 0 && core > rich);
});

test('report extraction derives only fixed authenticated NIMS PRINTREPORT URLs from safe tokens', () => {
  assert.match(bridge, /REPORT_PATH = "\/HISInvestigationG5\/new_investigation\/invDuplicateResultReportPrinting\.cnt"/);
  assert.match(bridge, /sourceReportUrlFromToken/);
  assert.match(bridge, /url\.searchParams\.set\("hmode", "PRINTREPORT"\)/);
  assert.match(bridge, /url\.searchParams\.set\("fileName", token\)/);
  assert.match(bridge, /REPORT_HOSTS\.has\(url\.hostname\)/);
});

test('structured API enriches report inventory before normalization', () => {
  const start = background.indexOf('async function fetchStructuredBulkResults(tabId, crNo)');
  const body = background.slice(start, background.indexOf('async function tryBulkApiForDashboard', start));
  const rest = body.indexOf('optionalBulkReportMetadata(crNo)');
  const enrich = body.indexOf('enrichBulkReportInventory(tabId, crNo, restReportIndex)');
  const normalize = body.indexOf('NimsBulkNormalizer.normalizeBulk');
  assert.ok(rest >= 0 && enrich > rest && normalize > enrich);
});

test('report inventory enrichment uses an inactive isolated NIMS tab and closes it', () => {
  const start = background.indexOf('async function fetchBrowserReportInventory(tabId, crNo)');
  const end = background.indexOf('async function enrichBulkReportInventory', start);
  const body = background.slice(start, end);
  assert.match(body, /chrome\.tabs\.create\(\{ url: NIMS_LOGIN_URL_DASHBOARD, active: false \}\)/);
  assert.match(body, /NIMS_BRIDGE_EXTRACT_DASHBOARD_DATA/);
  assert.match(body, /NIMS_IDENTITY_MISMATCH/);
  assert.match(body, /chrome\.tabs\.remove\(temporaryTabId\)/);
  assert.doesNotMatch(body, /NIMS_BRIDGE_RUN_SUMMARY/);
});

test('REST and browser inventory rows with different IDs stay separate and preserve verified PDF', () => {
  const keyFn = isolate(background, 'canonicalReportKey');
  const mergeFn = isolate(background, 'mergeReportInventories', { canonicalReportKey: keyFn });
  const merged = mergeFn(
    [{ id: 'rest-a', title: 'Complete Blood Count', date: '15-Oct-2026', url: '' }],
    [{ id: 'browser-b', title: 'Complete Blood Count', date: '15-Oct-2026', url: 'https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt?hmode=PRINTREPORT&fileName=safe.pdf' }]
  );
  assert.equal(merged.length, 2);
  assert.equal(merged[0].url, '');
  assert.match(merged[1].url, /PRINTREPORT/);
});

test('report inventory absence does not discard structured result retrieval', () => {
  const start = background.indexOf('async function enrichBulkReportInventory(tabId, crNo, restReports)');
  const end = background.indexOf('async function fetchStructuredBulkResults', start);
  const body = background.slice(start, end);
  assert.match(body, /report_inventory_unavailable/);
  assert.match(body, /reports: current/);
});
