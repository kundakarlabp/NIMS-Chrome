import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const normalizerSource = fs.readFileSync(new URL('../src/bulkNormalizer.js', import.meta.url), 'utf8');
const background = fs.readFileSync(new URL('../src/background.js', import.meta.url), 'utf8');
const manifest = JSON.parse(fs.readFileSync(new URL('../manifest.json', import.meta.url), 'utf8'));

const context = { URL, console };
context.self = context;
vm.createContext(context);
vm.runInContext(normalizerSource, context);
const api = context.NimsBulkNormalizer;
const CR = '1'.repeat(15);

test('Custom Sheet bulk response becomes dashboard values without parser helper', () => {
  const raw = {
    status: 1,
    crNo: CR,
    groups: [{
      testName: 'CBC',
      rows: [
        { parameterName: 'Hemoglobin', unit: 'g/dL', refRange: '12-16', valuesByDate: { '15-Oct-2026': '10.2' } },
        { parameterName: 'WBC', unit: '/uL', valuesByDate: { '15-Oct-2026': '7800' } }
      ]
    }]
  };
  const out = api.normalizeBulk(raw, CR, { patient: { name: 'Synthetic Patient', crNo: CR } });
  assert.equal(out.source, 'nims_bulk_api');
  assert.equal(out.patient.crNo, CR);
  assert.equal(out.results.length, 2);
  assert.equal(out.results[0].valueText, '10.2');
  assert.equal(out.results[0].date, '2026-10-15');
});

test('Custom Sheet bulk response fails closed on another CR', () => {
  assert.throws(() => api.normalizeBulk({
    status: 1,
    crNo: '9'.repeat(15),
    groups: [{ testName: 'CBC', rows: [{ parameterName: 'WBC', valuesByDate: { '15-Oct-2026': '100' } }] }]
  }, CR), /different CR/);
});

test('primary structured API path does not depend on parser helper', () => {
  const start = background.indexOf('async function fetchStructuredBulkResults(tabId, crNo)');
  const end = background.indexOf('async function tryBulkApiForDashboard', start);
  assert.ok(start >= 0 && end > start);
  const body = background.slice(start, end);
  assert.doesNotMatch(body, /callHelper|NIMS_HELPER_PARSE_REPORT|parse-report/);
  assert.match(body, /NIMS_BULK_OPEN_TRENDS/);
  assert.match(body, /NIMS_BULK_RUN_CUSTOM/);
  assert.match(body, /NimsBulkNormalizer\.normalizeBulk/);
});

test('manifest injects the XHR capture in MAIN world before page requests', () => {
  const capture = manifest.content_scripts.find(entry => (entry.js || []).includes('src/bulkCaptureMain.js'));
  assert.ok(capture);
  assert.equal(capture.world, 'MAIN');
  assert.equal(capture.run_at, 'document_start');
  assert.equal(capture.all_frames, true);
  assert.equal(Boolean(capture.match_origin_as_fallback), false);
});

test('final retrieval order is bulk API then reportList REST then browser fallback', () => {
  const start = background.indexOf('async function fetchCrForDashboard(rawCrNo, sender, rawRequestId)');
  const body = background.slice(start);
  const bulk = body.indexOf('tryBulkApiForDashboard(workerTabId, crNo)');
  const rest = body.indexOf('tryRestApiForDashboard(crNo)');
  const browser = body.indexOf('fetchCrForDashboardLegacy(crNo)');
  assert.ok(bulk >= 0 && rest > bulk && browser > rest);
});
