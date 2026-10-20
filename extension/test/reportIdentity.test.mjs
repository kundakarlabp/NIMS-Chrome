import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../src/background.js', import.meta.url), 'utf8');
function section(startText, endText) {
  const start = source.indexOf(startText);
  const end = source.indexOf(endText, start);
  assert.ok(start >= 0 && end > start, 'Source contract not found: ' + startText);
  return source.slice(start, end);
}
const context = { Map, Set, Date, String, Array, Number };
vm.createContext(context);
vm.runInContext(
  section('function canonicalReportKey(report)', 'async function fetchBrowserReportInventory') +
  '\nfunction cultureNarrative() { return ""; }\n' +
  section('function bundleFromParsedReports(crNo, extracted, state)', 'async function tryRestApiForDashboard'),
  context
);

const a = { id: 'accession-alpha', title: 'Blood culture', date: '2026-10-20', url: 'https://www.nimsts.edu.in/report/alpha' };
const b = { id: 'accession-beta', title: 'Blood culture', date: '2026-10-20', url: 'https://www.nimsts.edu.in/report/beta' };

test('two same-day cultures with different accessions remain separate', () => {
  const records = context.mergeReportInventories([a], [b]);
  assert.equal(records.length, 2);
  assert.equal(records[0].url, a.url);
  assert.equal(records[1].url, b.url);
});

test('a matching stable accession can be enriched without losing its verified PDF', () => {
  const records = context.mergeReportInventories([{ ...a, url: '' }], [a]);
  assert.equal(records.length, 1);
  assert.equal(records[0].url, a.url);
});

test('conflicting PDFs for the same accession remain separate for investigation', () => {
  const records = context.mergeReportInventories([a], [{ ...a, url: b.url }]);
  assert.equal(records.length, 2);
});

test('parsed result links use exact accession rather than the last matching test title', () => {
  const bundle = context.bundleFromParsedReports('000000000000001', {
    patient: { crNo: '000000000000001' }, reports: [a, b]
  }, { parsedReports: [
    { report_id: a.id, report_name: a.title, date_sent: a.date, parameters: [{ name: 'Result', value: 'synthetic A' }] },
    { report_id: b.id, report_name: b.title, date_sent: b.date, parameters: [{ name: 'Result', value: 'synthetic B' }] }
  ] });
  assert.equal(bundle.results.length, 2);
  assert.equal(bundle.results[0].pdfUrl, a.url);
  assert.equal(bundle.results[1].pdfUrl, b.url);
});

test('ambiguous title-and-date fallback never assigns a PDF', () => {
  const bundle = context.bundleFromParsedReports('000000000000001', {
    patient: { crNo: '000000000000001' }, reports: [a, b]
  }, { parsedReports: [
    { report_name: a.title, date_sent: a.date, parameters: [{ name: 'Result', value: 'synthetic' }] }
  ] });
  assert.equal(bundle.results[0].pdfUrl, '');
});

test('unique title-and-date fallback retains existing report linkage', () => {
  const bundle = context.bundleFromParsedReports('000000000000001', {
    patient: { crNo: '000000000000001' }, reports: [a]
  }, { parsedReports: [
    { report_name: a.title, date_sent: a.date, parameters: [{ name: 'Result', value: 'synthetic' }] }
  ] });
  assert.equal(bundle.results[0].pdfUrl, a.url);
});

test('a known unmatched accession does not fall back to a potentially wrong same-name PDF', () => {
  const bundle = context.bundleFromParsedReports('000000000000001', {
    patient: { crNo: '000000000000001' }, reports: [a]
  }, { parsedReports: [
    { report_id: 'another-accession', report_name: a.title, date_sent: a.date, parameters: [{ name: 'Result', value: 'synthetic' }] }
  ] });
  assert.equal(bundle.results[0].pdfUrl, '');
});

test('reused identifier with different source test title cannot collapse reports', () => {
  const records = context.mergeReportInventories([a], [{ ...a, title: 'Urine culture' }]);
  assert.equal(records.length, 2);
});
