(function (root) {
  'use strict';

  // Read-only comparison of source-attributed VoiceGrowth facts with authenticated
  // NIMS observations. Does not fetch, persist, adjudicate or change care.
  const CR = /^\d{15}$/;
  const CONFIRMED = new Set(['CLINICIAN_CONFIRMED', 'HIS_CONFIRMED']);
  const VERIFIED_ID_SOURCES = new Set(['NIMS_PATIENT_DISPLAY', 'NIMS_SOURCE_DOCUMENT', 'NIMS_CUSTOM_SHEET_RESPONSE']);
  const RESULT_SOURCES = new Set(['nims_bulk_api', 'nims_verified_pdf', 'nims_authenticated_custom_sheet']);

  function value(x) { return x == null ? '' : String(x).trim(); }
  function normalized(x) { return value(x).replace(/\s+/g, ' ').toLowerCase(); }
  function same(a, b) { return !!value(a) && !!value(b) && normalized(a) === normalized(b); }
  function isObject(x) { return x !== null && typeof x === 'object' && !Array.isArray(x); }

  function gate(consult, snapshot) {
    if (!isObject(consult) || !isObject(snapshot)) return 'INPUT_MISSING';
    if (!value(consult.consultId) || !value(consult.sourceDriveAudioId)) return 'VOICEGROWTH_PROVENANCE_MISSING';
    if (!CR.test(value(consult.confirmedCr)) || !CONFIRMED.has(consult.crStatus)) {
      return 'CR_NEEDS_CLINICIAN_CONFIRMATION';
    }
    if (snapshot.authenticatedSession !== true || !RESULT_SOURCES.has(snapshot.source)) {
      return 'NIMS_AUTHENTICATED_VALUES_UNAVAILABLE';
    }
    const proof = snapshot.identityEvidence;
    if (!isObject(proof) || proof.verified !== true
        || !VERIFIED_ID_SOURCES.has(proof.source) || !CR.test(value(proof.returnedCr))) {
      return 'NIMS_CR_IDENTITY_NOT_VERIFIED';
    }
    if (value(proof.returnedCr) !== value(consult.confirmedCr)
        || value(snapshot.requestedCr) !== value(consult.confirmedCr)) {
      return 'NIMS_CR_IDENTITY_MISMATCH';
    }
    if (!Array.isArray(snapshot.results)) return 'NIMS_RESULT_BUNDLE_MISSING';
    if (snapshot.results.some(row => isObject(row) && value(row.patientCr)
      && value(row.patientCr) !== value(consult.confirmedCr))) {
      return 'NIMS_CR_IDENTITY_MISMATCH';
    }
    return null;
  }

  function comparableTest(fact, row) {
    // Do not silently apply drug/organism aliases or combine differing panels.
    return same(fact.test, row.test) && same(fact.parameter, row.parameter);
  }

  function reconcileFact(fact, observations) {
    const factId = value(fact && fact.eventId);
    if (!isObject(fact) || !factId || !value(fact.test) || !value(fact.parameter)
        || !value(fact.value)) {
      return { eventId: factId, status: 'NOT_COMPARABLE', reason: 'Missing explicit source fact' };
    }
    if (fact.actualResult !== true || !value(fact.sourceEvidence)) {
      return { eventId: factId, status: 'SPOKEN_CLAIM_ONLY', reason: 'Not a source-verified result' };
    }
    const candidates = observations.filter(row => isObject(row) && comparableTest(fact, row));
    if (!candidates.length) return {
      eventId: factId, status: 'NO_MATCHING_NIMS_OBSERVATION',
      reason: 'Absence from this snapshot is not a negative result'
    };

    // Some NIMS Custom Sheet rows contain only test/date/value. Such rows can
    // guide review but cannot prove same specimen/episode without accession.
    const linked = candidates.filter(row =>
      same(fact.specimen, row.specimen)
      && same(fact.accession, row.accession)
      && same(fact.collectionDate, row.collectionDate)
      && same(fact.reportStage, row.reportStage)
      && row.sourceVerified === true
      && value(row.sourceDocumentId)
    );
    if (!linked.length) {
      return {
        eventId: factId, status: 'CANDIDATE_REQUIRES_SOURCE_REVIEW',
        candidateCount: candidates.length,
        reason: 'Exact specimen, accession, collection date, report stage and verified source are required'
      };
    }
    if (linked.length !== 1) return {
      eventId: factId, status: 'MULTIPLE_SOURCE_OBSERVATIONS',
      candidateCount: linked.length,
      reason: 'Cannot select among multiple matching source rows'
    };
    const row = linked[0];
    if (!value(fact.unit) || !value(row.unit) || !same(fact.unit, row.unit)) {
      return {
        eventId: factId, status: 'UNITS_REQUIRE_REVIEW',
        sourceDocumentId: row.sourceDocumentId,
        reason: 'Unit missing or different; no implicit unit conversion'
      };
    }
    return {
      eventId: factId,
      status: same(fact.value, row.value) ? 'EXACT_SOURCE_MATCH' : 'VALUE_DIFFERENCE_REVIEW',
      sourceDocumentId: row.sourceDocumentId,
      observationId: value(row.observationId),
      reason: same(fact.value, row.value)
        ? 'Exact value and identity attributes agree'
        : 'Difference is a review flag, not a clinical correction'
    };
  }

  function reconcile(consult, snapshot) {
    const issue = gate(consult, snapshot);
    if (issue) return {
      status: 'BLOCKED', reason: issue,
      checkedCount: 0, results: [], writesPerformed: false
    };
    const events = Array.isArray(consult.events) ? consult.events : [];
    const results = events.map(fact => reconcileFact(fact, snapshot.results));
    const counts = {};
    results.forEach(item => { counts[item.status] = (counts[item.status] || 0) + 1; });
    return {
      status: 'REVIEW_READY',
      consultId: value(consult.consultId),
      checkedCount: events.length,
      counts, results,
      writesPerformed: false
    };
  }

  const api = Object.freeze({ reconcile, gate });
  root.VoiceGrowthNimsReconciliation = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof self !== 'undefined' ? self : globalThis);
