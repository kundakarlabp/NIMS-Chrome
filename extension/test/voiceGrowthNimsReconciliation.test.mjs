import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../src/voiceGrowthNimsReconciliation.js', import.meta.url), 'utf8');
const context = {};
context.self = context;
vm.createContext(context);
vm.runInContext(source, context);
const { reconcile } = context.VoiceGrowthNimsReconciliation;
const CR = '9'.repeat(15);
const consult = () => ({
  consultId: 'VG-SYNTHETIC-C01',
  sourceDriveAudioId: 'synthetic-source-id',
  confirmedCr: CR, crStatus: 'CLINICIAN_CONFIRMED',
  events: [{
    eventId: 'E01', test: 'CBC', parameter: 'Hemoglobin',
    value: '10.2', unit: 'g/dL', specimen: 'Blood',
    accession: 'LAB-SYN-1', collectionDate: '2026-10-15',
    reportStage: 'Final', actualResult: true, sourceEvidence: '00:15'
  }]
});
const snapshot = () => ({
  authenticatedSession: true,
  requestedCr: CR,
  source: 'nims_bulk_api',
  identityEvidence: { verified: true, returnedCr: CR, source: 'NIMS_PATIENT_DISPLAY' },
  results: [{
    observationId: 'N1', test: 'CBC', parameter: 'Hemoglobin',
    value: '10.2', unit: 'g/dL', specimen: 'Blood',
    accession: 'LAB-SYN-1', collectionDate: '2026-10-15',
    reportStage: 'Final', sourceVerified: true, sourceDocumentId: 'synthetic-report-id'
  }]
});

test('exact verified observation is a match, without writes', () => {
  const out = reconcile(consult(), snapshot());
  assert.equal(out.status, 'REVIEW_READY');
  assert.equal(out.results[0].status, 'EXACT_SOURCE_MATCH');
  assert.equal(out.writesPerformed, false);
});

test('unverified and ambiguous CR cannot trigger result checking', () => {
  const c = consult(); c.confirmedCr = '9'.repeat(14);
  assert.equal(reconcile(c, snapshot()).reason, 'CR_NEEDS_CLINICIAN_CONFIRMATION');
  c.confirmedCr = CR; c.crStatus = 'TRANSCRIBED_UNCERTAIN';
  assert.equal(reconcile(c, snapshot()).reason, 'CR_NEEDS_CLINICIAN_CONFIRMATION');
});

test('identity must be independently evidenced, not echoed from the request', () => {
  const n = snapshot(); delete n.identityEvidence;
  assert.equal(reconcile(consult(), n).reason, 'NIMS_CR_IDENTITY_NOT_VERIFIED');
  n.identityEvidence = { verified: true, returnedCr: CR, source: 'UNKNOWN' };
  assert.equal(reconcile(consult(), n).reason, 'NIMS_CR_IDENTITY_NOT_VERIFIED');
});

test('wrong patient fails closed and never compares observations', () => {
  const n = snapshot(); n.identityEvidence.returnedCr = '8'.repeat(15);
  assert.equal(reconcile(consult(), n).reason, 'NIMS_CR_IDENTITY_MISMATCH');
  n.identityEvidence.returnedCr = CR;
  n.results[0].patientCr = '7'.repeat(15);
  assert.equal(reconcile(consult(), n).reason, 'NIMS_CR_IDENTITY_MISMATCH');
});

test('unauthenticated and report-list-only sources cannot be treated as clinical values', () => {
  const n = snapshot(); n.authenticatedSession = false;
  assert.equal(reconcile(consult(), n).reason, 'NIMS_AUTHENTICATED_VALUES_UNAVAILABLE');
  n.authenticatedSession = true; n.source = 'nims_rest_report_list';
  assert.equal(reconcile(consult(), n).reason, 'NIMS_AUTHENTICATED_VALUES_UNAVAILABLE');
});

test('same test and date without accession is only a candidate', () => {
  const n = snapshot(); delete n.results[0].accession;
  assert.equal(reconcile(consult(), n).results[0].status, 'CANDIDATE_REQUIRES_SOURCE_REVIEW');
});

test('different specimen, report stage or collection date is not an exact match', () => {
  for (const [field, changed] of [
    ['specimen', 'CSF'], ['reportStage', 'Preliminary'], ['collectionDate', '2026-10-16']
  ]) {
    const n = snapshot(); n.results[0][field] = changed;
    assert.equal(reconcile(consult(), n).results[0].status, 'CANDIDATE_REQUIRES_SOURCE_REVIEW');
  }
});

test('units are not inferred or converted', () => {
  const n = snapshot(); n.results[0].unit = 'g/L';
  assert.equal(reconcile(consult(), n).results[0].status, 'UNITS_REQUIRE_REVIEW');
});

test('a disagreement is flagged, never silently corrected', () => {
  const n = snapshot(); n.results[0].value = '11.2';
  assert.equal(reconcile(consult(), n).results[0].status, 'VALUE_DIFFERENCE_REVIEW');
  assert.equal(consult().events[0].value, '10.2');
});

test('multiple same-identity observations are not silently merged', () => {
  const n = snapshot(); n.results.push({ ...n.results[0], observationId: 'N2' });
  assert.equal(reconcile(consult(), n).results[0].status, 'MULTIPLE_SOURCE_OBSERVATIONS');
});

test('no result is not interpreted as negative/normal', () => {
  const n = snapshot(); n.results = [];
  assert.equal(reconcile(consult(), n).results[0].status, 'NO_MATCHING_NIMS_OBSERVATION');
});

test('recommendation or unsupported source claim is not a verified result', () => {
  const c = consult(); c.events[0].actualResult = false;
  assert.equal(reconcile(c, snapshot()).results[0].status, 'SPOKEN_CLAIM_ONLY');
});
