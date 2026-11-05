# NIMS Results — no-regression audit and validation protocol

**Date / target:** 2026-11-05, current code `main@21baeb0` (Android 2.0.5).  
**Purpose:** turn the [NIMS HIS integration contract](NIMS_WEBSITE_INTEGRATION_CONTRACTS_2026-11-05.md) and [issue register](NIMS_ISSUE_REGISTER_2026-11-05.md) into repeatable evidence **before** changing parser logic, login, report discovery, performance architecture or APK packaging.

This document contains planned tests/acceptance checks. **They are not represented as having passed.** No code, actual patient data, NIMS credentials or screenshots are part of this audit PR.

## 1. Golden rule: distinguish five things

1. **NIMS actually returned something** (authenticated HTTP status, selected Custom Sheet study/date checkboxes, JSON raw rows, HTML index or original PDF).
2. **The app accepted it** (matching request ID, independently confirmed patient CR, valid payload).
3. **It normalized it correctly** (all genuine observations and narrative text, preserving raw source identity).
4. **It displayed/exported it correctly** (patient, date and complete values in one scroll; no inappropriate fallback or silent filtering).
5. **It associated original source correctly** (PDF verified CR + compatible specimen/event/stage, or clearly a review candidate).

These are separate denominators. Do **not** declare `resultCount > 0`, REST HTTP 200, PDF link count or a green unit test to be end-to-end clinical success.

## 2. Capture one run as a reproducible redacted session ledger

For each privately reviewed test case record:
- **Release provenance:** installed package name, versionName, versionCode, build commit, signing certificate SHA-256; record whether an over-install on v2 was actually attempted.
- **Session state sequence:** manual sign-in, NIMS session probe state, network/DNS event if any, context resume, renderer recovery. Never copy cookies/CAPTCHA/credentials.
- **Retrieval:** start/end timestamps; `requestId` as a *session-local non-identifying test ID*; expected patient CR-match as boolean only in public documentation; whether visible NIMS header independently confirmed that patient.
- **Custom Sheet:** available/selected test and date checkbox counts; GETMETABOLICDATA fetch/XHR, HTTP outcome, elapsed time, response bytes, source group/row/date/cell counts; accepted and rejected batches.
- **Parser and UI:** raw rows, populated clinical fields, technical rows filtered, missing fields, dates, studies, visible inline observations and full text; independently counted discrepancies.
- **Sources:** REST catalogue rows, HTML source rows, distinct original PDF documents, PDFs inspected, source headers with CR confirmation, verified result-to-PDF links, ambiguous candidates and absent links.
- **System:** Android heap/native/PSS/peak bitmap, OOM/renderer death, time to first clinical values, time to source inventory and time to first PDF. Keep p50/p95 where multiple samples exist; otherwise report only the individual observed times.
- **Source completeness label:** “complete relative to observed selected Custom Sheet source” **only when** every source step agrees. Otherwise report the missing stage; never extrapolate hospital-wide completeness.

Store identifiable clinical evidence only in NIMS-authorized/private locations. The public repository may contain a de-identified structural fixture or a one-way reference to a private case review without names, CRs, lab numbers, patient dates or network tokens.

## 3. An immutable source-to-view reconciliation ledger

| Step | Inputs | Output/measure | Reject/failure states |
|---|---|---|---|
| H1 login | Portal pages and clinician action | Authenticated shell verified via content, not URL | Login form, real expiry, unreachable, ambiguous |
| H2 patient | Requested CR, visible patient header | Same patient confirmed for current request | Different CR, no header, stale request |
| H3 Custom Sheet | All actual test/date inputs | Selected vs available counts | Missing/unchecked controls, ambiguous Apply |
| H4 structured capture | Same-owner fetch/XHR response | Accepted JSON group/row/cell counts | Timeout, HTTP failure, partial/foreign response |
| P1 normalization | Exact H4 payload | Raw rows, numeric/text observations, explicit source fields | Parse fail, invalid JSON, unrecognized schema |
| P2 chronology | P1 observations | Per-day studies; individual inline values | Duplicate drop, wrong date semantic, truncation |
| S1 REST catalogue | Same authenticated patient context | Metadata only; source field coverage | Wrong CR, no identity context, login HTML, timeout |
| S2 HTML inventory | CR-wise report process + `SHOWPATDETAILS` | Distinct source documents, true index lab/collection fields | Unexpected columns, off-domain action, wrong patient |
| S3 original PDF | Authenticated source PDF | CR-verified PDF identity + sections + provenance | Non-PDF, image-only, contradictory CR, absent labels |
| S4 reconciliation | P1 + S2 + S3 evidence | Verified **result-to-document** link OR candidate/unlinked | Wrong specimen, stage, bottle/set, multiple plausible documents |
| E1 export | P2 + safe verified source | Generated PDF/CSV with accurate dates/units/narratives | Synthetic values, inferred accession, “original signed PDF” label |

**Fail-safe behavior:** P1/P2 may remain usable when S1/S2/S3 are unavailable, but the UI must not claim verified original report linking; errors remain visible in diagnostics. An identity conflict must not be downgraded to “empty result” or “try another source” for the same clinical entity.

## 4. Website compatibility matrix (manual authenticated smoke tests)

Perform with the hospital portal itself, on a clinician-authorized test case:

| Scenario | Required behavior | Common regression to avoid |
|---|---|---|
| Login form, CAPTCHA required | Manual login succeeds, CR remains pending and resumes | False “CONNECTED” from cookie presence or URL |
| Authenticated e-Sushrut shell remains at login URL | Probe detects actual shell and can open Investigation | Replacing DOM evidence with URL-only heuristics |
| CR form nested inside one or more frames | Find correct `patCrNo`, verify patient banner | Top-level-only or arbitrary-textbox fallbacks |
| Different www/apex frame or origin | Use only legitimate origin/bridge mechanism; no extraneous frame access | Breaking postMessage handshake or same-origin enforcement |
| All tests/dates selected | Capture correct selected/available counts and results | Silent partial history treated as complete |
| A → B patient switch before A's network response | Old callback ignored | Cross-patient leakage through stale request IDs |
| NIMS protected session expires during Custom Sheet | User informed and may re-login/retry | Login keywords inside a normal page triggering false expiry |
| Server DNS/HTTP timeout | Distinct error, prior encrypted snapshot remains labelled stale | Clearing saved clinical results on transient outage |
| REST reportList has only catalogue fields, no CR | Accept metadata only *after* separate patient verification | Inventing accession/date/clinical values |
| HTML report index includes empty columns | Parse fixed column positions intact | “shift left” by filtering empty cells |
| NIMS source `printReport`/PDF popup | Open original PDF in dedicated viewer | Primary results WebView replaced with black PDF view |
| Source PDF is same CR but wrong accession/specimen/bottle | Keep as unresolved candidate or reject | Attaching solely by title/date |
| Multiple source PDFs share an accession | Preserve all distinct source documents | Merging aspirate/trephine, stain/culture or interim/final |
| Android app killed/restarted; v2 upgraded | Restore safe saved state and label cache; original signer/package intact | Session/data loss or installing separate unintended package |

**Pass on synthetic tests alone is insufficient for this matrix.** Note the device model, Android version, WebView version and NIMS server/test context privately, without posting PHI.

## 5. Clinical gold-standard test library (synthetic public + approved private evidence)

For each source create: source report fixture (exact structure), clinician-reviewed expected fields, explicit provenance, absent fields, negative mismatches, and original PDF visual comparison. Avoid screenshots copied into GitHub if they identify patients.

| Group | Positive cases | Mandatory adversarial/negative cases |
|---|---|---|
| CBC/coagulation | Hb, TLC after Hb, diff, platelet, ESR, PT patient/control, INR, APTT, units/reference | Control reported as patient result; technical LIS fields exposed |
| Biochemistry | Creatinine→urea→Na/K/Cl→Ca/Mg→LFT, HbA1c/lipids, qualifiers | `<` sign stripped; source date claimed “reported” |
| Urine exam | pH, specific gravity, glucose, ketones, protein, cells in correct columns | HIS dropdown code interpreted as positive/negative |
| Blood cultures | Positive, no growth, Gram prelim, 48h vs final, sets and bottles, organism/MIC/S/I/R | Same day/accession but different bottle or conflicting interpretation |
| Other cultures | Urine clean-catch vs sterile/other, aerobic wound/other, fungal cultures | Blood/urine/wound PDF cross-linked by “aerobic culture” |
| Smears | Gram, Giemsa, KOH, calcofluor, AFB variants | Generic `STAINING` wrongly accepted as a specific report |
| Molecular | BioFire/FilmArray/BGT/PCR with each target/result/control | Controls treated as clinical positive, missing targets |
| Serology/transfusion | HIV/HBsAg/HCV/CRP, DAT/IAT graded 1+–4+, blood group, DAT control | “direct” substring matching “indirect”, source code-only inference |
| Fluids | CSF, ascitic, pleural and joint protein/glucose/appearance, explicit specimen | Same date but different fluid; one measurement not adequate for specimen assignment |
| Bone marrow | Aspirate, trephine, peripheral smear, collection/report dates, sectioned narrative | Same lab number but different document and report time |
| Imaging | PET/CT/radiology complete multisection report | False clinical numbers from narrative text |
| Exports | Full CSV, selected CSV, date-wise generated clinical PDF, original PDFs | Truncated narrative, unsupported provenance and wrong “signed original” label |

**Primary safety indicators:** wrong-patient attachments (target **zero**), false qualitative positive/negative (zero), incorrect specimen/stage/bottle linkage (zero), lost exact qualifier/units (zero), and omitted clinically important source values (zero **relative to the inspected gold standard**). Do not report measured zero until cases have actually been run.

## 6. Automated and device-level gates by layer

### Existing repo-level checks (review; rerun on final commit)
- `npm ci && npm test` for MV3 extension (8 test files; separate `node --test android-native/tests/*.test.cjs` for native WebView engine contracts).
- `pip install -r helper/requirements-dev.txt` and `PYTHONPATH=helper python -m pytest -q tests/test_parsers.py tests/test_remote_helper.py`. Helper tests were **not** run by Claude's v2.0.4 sandbox; current GitHub CI configuration includes them.
- GitHub Android `gradle -p android-native clean test lintRelease assembleRelease`, with JUnit reports and test failures inspected. Android SDK unavailable in Claude's earlier sandbox is not evidence of test absence.
- APK signature/certificate SHA-256, package ID, versionName/versionCode, non-debuggable and artifact SHA256 verified by `.github/workflows/android-release.yml`.
- Execute actual device **install over existing v2**, verify app state and clinical results; release workflow itself records `device-install-upgrade-test=not-run`.
- No statement of “all tests passed” from this documentation pass: source examined only; no new tests executed here.

### Isolation matrix for new fixes

| Change class | Minimum tests | Preserve |
|---|---|---|
| Redirect handling | Synthetic allowed hops, refused external hop, cookie-per-host, login redirect | Manual login and host-specific session cookies |
| WebView bridge | Legitimate frames, untrusted frame, request ID changes, renderer death | Existing NIMS frame compatibility |
| Bulk parsing | Realistic Custom Sheet fixtures across months, exact raw-to-value accounting | All source rows, narratives, zero guessing |
| PDF source matching | Conflicting CR, mismatched accession, same-day specimens, stage/bottles, cache eviction | Candidate review vs verified link boundary |
| UI and performance | Full synthetic golden dataset, Compose scrolling, background inventory timeout, heap/PSS | Single chronological feed, no blocked main thread |
| Exports | Same normalized dataset compared against screen/source, full text and dates | Derived vs original PDF distinction |
| Release | Same original v2 signer/package, higher code, device upgrade | v1 stays installed; existing v2 user data persists |

## 7. Runtime/performance audit plan: profile before refactoring

Do not refactor `MainActivity` solely because it exceeds 5,000 lines. Instead identify measured bottlenecks:
1. Timestamp `requestFetch` → session check → actual CR form → selected Custom Sheet → first data → normalization → first composited result → independent report inventory → PDF verification.
2. Count NIMS bulk requests, REST calls, HTML inventory fetches, per-PDF requests, repeated requests for same source, and late/duplicate callback discards.
3. Compare main-thread frames/jank, Java heap, native heap, PSS, active PDF bitmap bytes, PDFBox extracted text length and cache entries. Record device/Android/WebView conditions.
4. Use at least: small-history, large-history, multiple cultures, multi-section PDF, weak network, expired login, process death, source index missing and heavy repeated screen scrolling.
5. Optimize in descending proven contribution: redundant PDF downloads/matching, repeated full-data transforms, accidental main-thread work, stale executor work, then only isolated structural refactor.
6. Any bounded caching change must test evidence survival across eviction and patient switching. v2.0.5 specifically introduced 180-entry compact evidence separate from the heavier PDF text cache; deleting that design can recreate a proven regression.

Do not impose invented latency targets. First record a reproducible baseline, then define improvements and permissible regression budgets for identical workloads.

## 8. Releases, rollback and traceability

- Single cause per feature branch/PR; put issue ID(s) from register in title/body, and note source contract being preserved.
- Keep existing test coverage; consolidate version-named tests only when equivalent invariants are retained.
- Never rewrite the desktop connector and Android together without separate contract-level testing. The AppDeploy dashboard is outside this repo.
- Do not alter NIMS authenticated access method, use external services as a fallback for a failing NIMS portal, bypass CAPTCHA, or blindly require new credentials.
- Preserve `org.kundakarlab.nimsresults.recovery`, v2 installed certificate SHA-256 `0932A983C18134C898D05671E579969BCE5724F9C44840D367A5BE556C2F11C5` and strictly increasing versionCode. Keep legacy v1 package intact.
- If a new signed APK breaks live login/clinical correctness, fix and publish a **higher** versionCode with the *same signer*. Android does not generally support blind version downgrades.
- Save the redacted before/after test ledger, list unresolved mismatches, source counts, CI run/artifact, installed build identity and explicit manual NIMS/device check result. Do not claim production readiness when the last two checks are absent.

## 9. Explicit product scope for this stage

The user wants a thorough understanding of historical NIMS website behavior **before** any code corrections, not a sweeping hardening project. **Do not introduce compulsory app lock, screenshot restrictions, inaccessible diagnostic tools, new pairing/relay, local-client dependency on Railway, or bulk PDF uploads.**

Recording an unsafe redirect pattern or an insecure cloud configuration does not require introducing new user-facing restrictions; these can be addressed later in contained, compatibility-tested patches. **Never defer wrong-patient safeguards** or endorse a change that would send authenticated NIMS cookies to untrusted hosts.

**First milestone of this documentation PR:** comprehensive, source-linked map + evidence-labelled issue register + executable test plan. **No claim that the actual NIMS server, Android APK or external dashboard was exercised in this pass.**
