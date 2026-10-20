# NIMS Results Connector — canonical operating contract

## Scope
This repository owns the **desktop Chrome connector** only. Preserve the authenticated, user-initiated NIMS retrieval workflow; do not introduce another login, Android transport, clinical registry, or ChatGPT relay here.

## Retrieval priority (matches deployed connector code and tests)
1. Clinician completes NIMS login/CAPTCHA in the browser; never automate authentication or copy session tokens.
2. NIMS Investigation Trends / GETMETABOLICDATA structured bulk values, with a verified 15-digit CR, is the **primary** results path.
3. Optional authenticated reportList source-inventory metadata and exact report-PDF links can enrich the structured results, but must not block the primary values.
4. If the structured path fails, use the existing reportList REST parsing path, then the proven authenticated CR-wise browser processor as a bounded fallback. All fallbacks still require matching patient identity and visible source fidelity. Do not revive old visible toolbars.

## Patient safety and provenance
- Never match a source PDF using test title and date alone when two report instances share that pair. Prefer exact accession/report identity; keep ambiguous PDFs unlinked.
- Do not collapse separate same-day report instances or change source investigation names.
- Never convert a missing/unparsed result into a negative finding; use explicit partial/unverified coverage.
- Every result transfer must retain source report identity, dates, original units, observation provenance and completeness diagnostics.
- Clinical-service handoff is separate and requires clinician authentication and a verified NIMS CR. De-identified sharing to ChatGPT is not the same as automatic transfer of identifiable hospital records.
- Do not commit real patient identifiers, PDFs, credentials, API keys or tokens to GitHub.

## Validation and release gates
Run `npm test` and helper parser tests on synthetic fixtures. Check the extension version, authenticated CR fetch, same-day duplicate report linkage, login/re-authentication recovery, correct source-PDF opening, and clinical bundle contract. A merged commit is not a deployed connector; a green unit test is not a validated patient-level workflow. Change the live dashboard or Android app only through their existing separate release paths.
