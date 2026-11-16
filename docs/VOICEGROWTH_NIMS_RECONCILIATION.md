# VoiceGrowth ↔ NIMS Results: verified, read-only reconciliation stage

## Scope
This is a **source-only, opt-in reconciliation primitive**. It does **not** connect the deployed AppDeploy dashboard, add new OAuth/API services, enter NIMS credentials, bypass NIMS login/CAPTCHA, query records in the background, or sync clinical changes to Google Sheets. No patient examples or source identifiers belong in this public repository.

The live working sources are the existing private VoiceGrowth transcripts and consultation JSON in Drive, the existing private KBP Registry, and the NIMS Results desktop/Android authenticated session. The AppDeploy dashboard frontend is not in this repository and needs an independently verified authorized integration point.

## Data flow
1. Retrieve one **clinician-confirmed 15-digit CR** from the authorized private consultation record; preserve uncertain transcription variants without guessing digits.
2. Only after manual NIMS login/CAPTCHA and an authenticated-session check, retrieve clinical values from Custom Sheet or an independently identity-verified source PDF. REST reportList alone is **metadata, not clinical values**.
3. Build an identity evidence object from returned NIMS clinical content, not from echoing the requested CR. A conflicting CR aborts every comparison.
4. Provide a source-attributed VoiceGrowth case with source audio ID, stable consult/event IDs, source timestamp and *actual-result versus reported/recommended* distinction. Only clinician-confirmed records can be called clinically verified.
5. Call `VoiceGrowthNimsReconciliation.reconcile(consult, snapshot)` in an authorized local/browser context. The output is a **review queue**, not a clinical correction.
6. Where test, parameter, specimen, accession, collection date, report stage and actual source document ID all agree, exact comparable value/unit equality can be shown as `EXACT_SOURCE_MATCH`. Otherwise keep a *candidate* or discrepancy for human review. No missingness-as-negative, automatic unit conversion, specimen/date-only PDF match or indiscriminate normalization.
7. The clinician approves any change separately, retaining the original spoken record, confirmed correction provenance and source PDF. Only then may the existing private VG Clinical Events registry be updated, with readback and idempotent event keys.

## Adapter contract (synthetic shape)
```js
const consult = {
  consultId: "VG-SYNTHETIC-C01",
  sourceDriveAudioId: "synthetic-source-id",
  confirmedCr: "999999999999999",
  crStatus: "CLINICIAN_CONFIRMED",
  events: [{
    eventId: "E01", test: "CBC", parameter: "Hemoglobin",
    value: "10.2", unit: "g/dL", specimen: "Blood",
    accession: "LAB-SYN-1", collectionDate: "2026-10-15",
    reportStage: "Final", actualResult: true, sourceEvidence: "00:15"
  }]
};
const snapshot = {
  authenticatedSession: true, requestedCr: "999999999999999",
  source: "nims_bulk_api",
  identityEvidence: {
    verified: true, returnedCr: "999999999999999",
    source: "NIMS_PATIENT_DISPLAY"
  },
  results: [{
    observationId: "N1", test: "CBC", parameter: "Hemoglobin",
    value: "10.2", unit: "g/dL", specimen: "Blood",
    accession: "LAB-SYN-1", collectionDate: "2026-10-15",
    reportStage: "Final", sourceVerified: true, sourceDocumentId: "synthetic-report-id"
  }]
};
const outcome = VoiceGrowthNimsReconciliation.reconcile(consult, snapshot);
```

## Runtime limits / prerequisites
- No deployed UI has yet been changed; the frontend owner must wire this module to authenticated retrieval and private case access, ideally with a user-visible "Compare with NIMS" control and independent review status. An always-on ChatGPT task cannot inherit the clinician's private NIMS browser session.
- The existing `NimsBulkNormalizer.normalizeBulk` generally emits `test/parameter/date/valueText/unit`, **not** verified specimen/accession/report stage. These must come from an appropriately verified source. Do not map generic `date` to `collectionDate` without original source evidence.
- AI assistance may summarize **review flags within an authorized private ChatGPT conversation**, with no autonomous treatment revision. This repository neither sends PHI to an AI API nor stores results.
- No modifications to NIMS authentication, existing retrieval fallbacks, Android package/signing, or current dashboard bridge.
- To test the gate: `npm test`. Passing synthetic tests is not live NIMS verification.
