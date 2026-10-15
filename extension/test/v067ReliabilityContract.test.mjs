import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const background = fs.readFileSync(new URL('../src/background.js', import.meta.url), 'utf8');
const dashboardBridge = fs.readFileSync(new URL('../src/dashboardBridge.js', import.meta.url), 'utf8');
const uiBridge = fs.readFileSync(new URL('../src/nimsUiBridge.js', import.meta.url), 'utf8');
const normalizerSource = fs.readFileSync(new URL('../src/bulkNormalizer.js', import.meta.url), 'utf8');

const context = { URL, console };
context.self = context;
vm.createContext(context);
vm.runInContext(normalizerSource, context);
const api = context.NimsBulkNormalizer;
const CR = '1'.repeat(15);

test('dashboard request correlation is preserved across bridge and service worker events', () => {
  assert.match(dashboardBridge, /requestId/);
  assert.match(background, /normalizeDashboardRequestId/);
  assert.match(background, /KBP_NIMS_FETCH_STARTED/);
  assert.match(background, /KBP_NIMS_RETRIEVAL_STAGE/);
  assert.match(background, /KBP_NIMS_BULK_RESULTS/);
  assert.match(background, /KBP_NIMS_FETCH_ERROR/);
  const start = background.indexOf('async function fetchCrForDashboard');
  const body = background.slice(start);
  assert.match(body, /\.\.\.context/);
});

test('report inventory is retained even when a safe source URL is unavailable', () => {
  const reports = api.normalizeReports([{ id: 'r1', title: 'Synthetic CBC', date: '15-Oct-2026' }]);
  assert.equal(reports.length, 1);
  assert.equal(reports[0].title, 'Synthetic CBC');
  assert.equal(reports[0].url, undefined);
});

test('structured results receive deterministic source keys and provenance', () => {
  const raw = {
    status: 1,
    crNo: CR,
    groups: [{
      testName: 'CBC',
      rows: [{
        parameterName: 'WBC',
        unit: '/uL',
        refRange: '4000-11000',
        valuesByDate: { '15-Oct-2026': '7800' }
      }]
    }]
  };
  const first = api.normalizeBulk(raw, CR);
  const second = api.normalizeBulk(raw, CR);
  assert.equal(first.results[0].sourceKey, second.results[0].sourceKey);
  assert.equal(first.results[0].provenance.retrieval, 'nims_bulk_api');
  assert.equal(first.schemaVersion, 'nims-dashboard-bulk/1.1');
});

test('retrieval diagnostics are counts and status metadata rather than raw patient payloads', () => {
  const start = background.indexOf('bundle.retrieval =');
  const end = background.indexOf('await pushDashboardEvent("KBP_NIMS_BULK_RESULTS"', start);
  const body = background.slice(start, end);
  assert.match(body, /durationMs/);
  assert.match(body, /attempts/);
  assert.doesNotMatch(body, /patientName|pdf_base64|rawBulk/);
});

test('NIMS UI compatibility selectors are centralized and probe exposes a selector version', () => {
  assert.match(uiBridge, /const UI =/);
  assert.match(uiBridge, /selectorVersion: 'v1'/);
  assert.match(uiBridge, /LABELS\.trends/);
  assert.match(uiBridge, /UI\.trendFrameId/);
});
