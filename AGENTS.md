# NIMS Results repository contract

## Supported products (both current, independent)

1. **Desktop dashboard connector:** `extension/` is the clinician-authenticated Chrome MV3 bridge to the deployed dashboard. The deployed AppDeploy dashboard code is **not** part of this repository.
2. **Native Android application:** `android-native/` is a separate Compose results viewer, with an authenticated NIMS WebView engine, native structured parsing, optional report inventory/PDF review, diagnostics and local exports.
3. **Optional deterministic parser helper:** `helper/` supports the desktop PDF/report fallback, locally by default; configured remote processing is an explicit, separate data boundary.

**Do not describe the repo as desktop-only or prohibit Android/PDF handling.**

## Canonical source behavior

- NIMS login and CAPTCHA remain clinician-operated. Session state must be confirmed from authenticated content, not a login URL or cookie presence alone.
- Both clients prefer **Investigation Trends → Custom Sheet → GETMETABOLICDATA** for clinical values. The HBIMS `reportList` REST endpoint supplies **report metadata/catalogue**, which can lack the patient CR, accession, full results, and signed PDF links. Do not assume it is a complete clinical feed.
- The Chrome connector can fall back to authenticated report processing; Android obtains report metadata using REST plus CR-wise HTML, and may inspect original PDFs on demand. Report discovery must not block already accepted structured results.
- Treat structured observations, source HTML index, REST catalogue, and original signed PDFs as **independent evidence** until the requested CR and event identity (test, accession when available, specimen, stage, culture bottle/set, explicit dates and text) agree.
- Never infer missing clinical values or coded dropdown meanings; never convert chart dates into collection/reported dates without explicit evidence. Preserve full narratives, multiple same-date specimens and preliminary/final reports.
- Exact-original PDF links and patient identity gates remain mandatory. Candidate title/date matches are **not** proof; no wrong-patient fallback.

## GitHub change workflow: one task, one PR

Before starting a change, search all open **and draft** PRs for the same clinical outcome, affected module or stable **Task-Key**. If one exists, update that PR's **same head branch**; do not create sequential Android version PRs or replacement `-final` branches to fix its CI. Add further changes and regression tests to the original PR. When no matching PR exists, use one branch from current `main` and one draft PR.

Draft PRs run only the fast, dependency-free repository-contract checks. Mark the **same PR** ready to trigger full JavaScript, Python/helper, Android build, and extension-packaging validation. New commits after ready state should return the PR to draft for focused iteration; full CI must pass on the final reviewable head before merge. Ordinary push CI on `main` remains complete. These rules never relax HIS authentication, source identity, patient matching, or APK signing controls.

Stale PRs may be closed only after an exact SHA ancestry check against `main`, or explicit equivalent-change and regression verification. Post the evidence before closing. Keep diverged or unfinished work open; do not automatically delete source branches, tags, APK artifacts, or research data. Historical discussion remains accessible after closure.

## Change-control / regression prevention

- Read `audit/README.md`, its NIMS integration contracts, issue register and validation matrix before changing HIS selectors, session handling, source matching, PDF processing or Kotlin architecture.
- Preserve NIMS-specific nested frames, www/apex host handling, actual `patCrNo` / `SHOWPATDETAILS` form behavior, request-owner correlation and manual login return. Never delete a proven fallback without a tested replacement.
- Prefer small focused patches and source-faithful synthetic tests. Evaluate real NIMS behavior on the phone for any retrieval/authentication change. CI alone does not establish clinical correctness.
- Do not add compulsory app locks/screenshot blocking, new relay/pairing architecture, bulk cloud uploads or an unrelated redesign during the current clinical-functionality phase.
- Keep sensitive patient data, cookies, URLs with report tokens, signing secrets and clinical PDFs out of Git and copyable diagnostics.
- For Android v2 upgrades preserve `org.kundakarlab.nimsresults.recovery`, the original v2 signing certificate and monotonic versionCode. Do not uninstall existing v1/v2 data to resolve signing conflicts. See `android-native/IN_PLACE_UPDATES.md`.

## Never introduce

- Automated CAPTCHA/OTP solving or bypass of hospital authentication.
- A source-date/title-only PDF mapping that can silently attach a different specimen or patient.
- A clinical “normal/negative” interpretation from unknown HIS codes or missing values.
- Restored obsolete Chrome side-panel/manual-analysis products or speculative mobile relays.
