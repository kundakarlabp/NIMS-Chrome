import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const manifest = JSON.parse(fs.readFileSync(new URL('../manifest.json', import.meta.url), 'utf8'));
const background = fs.readFileSync(new URL('../src/background.js', import.meta.url), 'utf8');
const dashboard = fs.readFileSync(new URL('../src/dashboardBridge.js', import.meta.url), 'utf8');
const ui = fs.readFileSync(new URL('../src/nimsUiBridge.js', import.meta.url), 'utf8');
const capture = fs.readFileSync(new URL('../src/bulkCaptureMain.js', import.meta.url), 'utf8');
const normalizerSource = fs.readFileSync(new URL('../src/bulkNormalizer.js', import.meta.url), 'utf8');

test('connector contains only the bulk-API desktop runtime', () => {
  assert.equal(manifest.version, '0.6.0');
  const scripts = new Set(manifest.content_scripts.flatMap(entry => entry.js || []));
  assert.deepEqual([...scripts].sort(), [
    'src/bulkCaptureMain.js',
    'src/dashboardBridge.js',
    'src/nimsUiBridge.js'
  ].sort());
  assert.equal(manifest.side_panel, undefined);
  assert.equal(manifest.action, undefined);
});

test('routine retrieval uses Custom Sheet bulk API and never navigates the Results List', () => {
  assert.match(background, /NIMS_BULK_OPEN_TRENDS/);
  assert.match(background, /NIMS_BULK_RUN_CUSTOM/);
  assert.match(background, /nims_bulk_api/);
  assert.match(ui, /Cr No Wise Investigation Trends/);
  assert.match(ui, /labTrendsCustomBtn/);
  assert.match(capture, /GETMETABOLICDATA/);
  for (const forbidden of [
    'Cr No Wise Result Report Printing New',
    'NIMS_BRIDGE_RUN_SUMMARY',
    'NIMS_DISCOVER_MAPPING',
    'No View Report button found for row',
    'NIMS_HELPER_PARSE_REPORT'
  ]) {
    assert.equal(background.includes(forbidden) || ui.includes(forbidden) || capture.includes(forbidden), false, forbidden);
  }
});

test('reportList is optional metadata only and cannot trigger Results List fallback', () => {
  const start = background.indexOf('async function optionalReportMetadata');
  const end = background.indexOf('async function fetchBulkResults', start);
  const metadata = background.slice(start, end);
  assert.match(metadata, /NimsRestApi\.fetchReportList/);
  assert.match(metadata, /return \[\]/);
  const fetchStart = background.indexOf('async function fetchCrForDashboard');
  const fetchBody = background.slice(fetchStart);
  assert.equal(fetchBody.includes('KBP_NIMS_SOURCE_FALLBACK'), false);
});

test('manual login is the only fallback when session is absent', () => {
  const start = background.indexOf('async function fetchCrForDashboard');
  const body = background.slice(start);
  assert.match(body, /openDashboardLogin\(sender\)/);
  assert.match(body, /KBP_NIMS_AUTH_REQUIRED/);
  assert.match(body, /pendingAuth: true/);
});

test('bulk capture is armed per request and correlated by request id', () => {
  assert.match(capture, /__nimsBulkCaptureArm/);
  assert.match(capture, /requestId/);
  assert.match(ui, /__nimsBulkCaptureArm/);
  assert.match(background, /pendingBulk/);
  assert.match(background, /pending\.requestId !== String\(message\.requestId/);
});

test('dashboard bridge keeps the existing CR/status contract', () => {
  for (const token of [
    'KBP_NIMS_PING',
    'KBP_NIMS_STATUS_REQUEST',
    'KBP_NIMS_LOGIN_REQUEST',
    'KBP_NIMS_FETCH_REQUEST',
    'KBP_NIMS_FETCH_ERROR'
  ]) assert.match(dashboard, new RegExp(token));
  assert.match(dashboard, /chrome\.runtime\.getManifest\(\)\.version/);
});

test('bulk normalizer fails closed on a different CR', () => {
  const context = { URL, self: {} };
  context.globalThis = context.self;
  vm.createContext(context);
  vm.runInContext(normalizerSource, context);
  const api = context.self.NimsBulkNormalizer;
  const fixture = {
    status: '1',
    crNo: '999999999999999',
    groups: [{ testName: 'CBC', rows: [{ parameterName: 'WBC', valuesByDate: { '05-Oct-2026': '12000' } }] }]
  };
  assert.throws(() => api.normalizeBulk(fixture, '331012600000001'), /different CR/);
});

test('bulk normalizer converts Custom Sheet groups directly into dashboard rows', () => {
  const context = { URL, self: {} };
  context.globalThis = context.self;
  vm.createContext(context);
  vm.runInContext(normalizerSource, context);
  const api = context.self.NimsBulkNormalizer;
  const fixture = {
    status: '1',
    groups: [{ testName: 'CBC', rows: [{ parameterName: 'WBC', refRange: '4-11', valuesByDate: { '05-Oct-2026': '12.0' } }] }]
  };
  const bundle = api.normalizeBulk(fixture, '331012600000001', { patient: { name: 'Synthetic', crNo: '331012600000001' } });
  assert.equal(bundle.source, 'nims_bulk_api');
  assert.equal(bundle.results.length, 1);
  assert.equal(bundle.results[0].date, '2026-10-05');
  assert.equal(bundle.results[0].parameter, 'WBC');
  assert.equal(bundle.results[0].valueText, '12.0');
});

test('runtime scripts compile', () => {
  assert.doesNotThrow(() => new Function(dashboard));
  assert.doesNotThrow(() => new Function(ui));
  assert.doesNotThrow(() => new Function(capture));
  assert.doesNotThrow(() => new Function(normalizerSource));
});


test('bulk capture can be armed by the authenticated top NIMS frame', () => {
  assert.match(capture, /NIMS_ORIGINS\.has\(event\.origin\)/);
  assert.match(capture, /__nimsBulkCaptureArm/);
  assert.doesNotMatch(capture, /event\.source !== window/);
});

test('bulk interceptor recognizes GETMETABOLICDATA in URL or request body', () => {
  assert.match(capture, /GETMETABOLICDATA/);
  assert.match(capture, /URLSearchParams/);
  assert.match(capture, /FormData/);
});
