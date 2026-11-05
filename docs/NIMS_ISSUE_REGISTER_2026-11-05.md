# NIMS Results — end-to-end historical issue register

**Prepared:** 2026-11-05 IST  
**Baseline:** `main@21baeb0`, Android **v2.0.5** (previous Claude review was `1f9bda2`, v2.0.4).  
**Use with:** [integration contracts](NIMS_WEBSITE_INTEGRATION_CONTRACTS_2026-11-05.md), [validation plan](NO_REGRESSION_VALIDATION_2026-11-05.md), [historical roadmap issue #80](https://github.com/kundakarlabp/NIMS-Chrome/issues/80).  
**Nature:** Evidence inventory and prioritization, **not** a list of 100 confirmed defects in the current APK. No code changed in this audit.

## Reading status and priority correctly

- **C — confirmed in source:** relevant condition actually exists in current repository. It does **not** prove that the vulnerability/exploit or functional failure has occurred in production.
- **R — reported historically / field reproduction needed:** screenshots, device diagnostics or prior user reports demonstrate a problem in an earlier installed version; verify on v2.0.5 before declaring it unresolved.
- **M — mitigation merged:** code/test fix exists in current branch, but a live NIMS + installed-phone acceptance test is not documented.
- **U — unverified/untested:** cannot determine correctness from code and synthetic tests alone.
- **D — design/maintenance debt:** not necessarily a current functional bug.
- **Deferred:** privacy/access restrictions the user does not want imposed in this initial functionality phase. Foundational patient identity and session-domain safety are **not** deferred.

Priority **P0** = potential wrong-patient/clinically dangerous data or authenticated-secret egress; **P1** = clinical completeness or main functionality; **P2** = significant speed/reliability; **P3** = maintenance; **Later** = deliberately deferred UX/security controls. A status of M is **not** permission to close without live acceptance.

## A. NIMS login, navigation and request ownership

| ID / priority / state | Failure / root cause or evidence | Current code or historical repair | Acceptance test before touching |
|---|---|---|---|
| AUTH-01 P1 M | Successful login still appears at loginLogin.action; simplistic URL checks made session look logged out | `nims_engine.js:probe`; PR #137 | Login with manual CAPTCHA; confirm actual shell without redirect-induced false logout |
| AUTH-02 P1 M | Login navigation text/scripts containing “login” caused false expiry and cancelled useful results | PR #79; current probe and `NimsLoginRecovery.kt` | Authenticated menu with login text must stay CONNECTED; true login form/expires must signal accurately |
| AUTH-03 P1 M | Genuine `patCrNo` not yet rendered while arbitrary textbox gave false “ready” | PR #99, native engine CR selector | Test delayed CR form and distractor search/input fields |
| AUTH-04 P1 M | Native HIS menu / nested iframe ID changes broke Investigation/CR-wise discovery | `collectDocuments`, `trendsFrame`, `navigationCore`; PR #121/#127 | Navigate from shell under both documented frame layouts |
| AUTH-05 P1 M | CR form's Go/Search, `hmode=SHOWPATDETAILS` or input/change events were bypassed by generic click | PR #99; `nimsSessionBridge.js`, `nims_engine.js` | Only real CR form is submitted; unrelated View Report never clicked |
| AUTH-06 P2 M | Server/DNS/renderer errors treated as logout, blocked the CR entry, or lost saved result view | `NimsLoginRecovery.kt`, `MainActivity.onRendererGone`; PR #162 | Offline/DNS error displays cached result label and supports retry without wiping state |
| AUTH-07 P1 M | Session disappears on tab-switch, login-return or pending request and user must start over | PR #119/#120/#127; `SessionVault`, `MainActivity` | Return from manual login / background; pending same CR resumes once, no duplicate fetch |
| AUTH-08 P0 C | Manual `Cookie` headers plus OkHttp `followRedirects(true)`; destination checked only after network follow | `ReportActivity.kt`, `PdfSourceEnricher.kt`, `ReportRepository.kt` | Controlled off-host redirect receives **zero** NIMS cookies while allowed host-hop functions |
| AUTH-09 P2 U | SessionVault restores cookies on both apex/www but WebView native cookie lifetime or logout timing may differ | `SessionVault.kt`, `LoginActivity.kt` | Cold start, expired portal session, logout one/all, cookie refresh on actual phone |
| AUTH-10 P2 M | Manage hospital sessions / logout all and login-on-expired behavior historically inconsistent | PR #199, `LoginActivity.kt` | Hospital UI shows server's actual sessions; no claim logout succeeded until portal confirms |

## B. Structured values, source coverage and API schema

| ID / priority / state | Failure / root cause or evidence | Current code or historical repair | Acceptance test |
|---|---|---|---|
| BULK-01 P1 M | `reportList` mistaken for complete laboratory results, leaving values absent | PR #123/#130; Android `onBulk` structured first | Capture complete selected Custom Sheet source and compare cell-by-cell |
| BULK-02 P1 U | A populated nonempty bulk response was mistaken for complete history | `nims_engine.js` single bulk, all test/date checks | Evidence for selected/available dates and tests; reconcile against portal |
| BULK-03 P1 U | Current one-shot bulk could time out/omit old history on large real CR; earlier staged 14-day strategy differs | PR #158 historical; current JS one bulk/60s; MainActivity 120s | Test CRs with years of history, server timeouts, pagination and actual coverage before strategy change |
| BULK-04 P0 M | Previous patient XHR/fetch response arrives after new CR selection | `installCapture` request ownership; `MainActivity.onBulk` | Interleave CR A and CR B with delayed responses; never mix identities |
| BULK-05 P1 M | Selected tests/date checkboxes not actually applied despite UI saying ready | `nims_engine.js:run`, `MainActivity.onBulk` | Count available=selected test/date controls; reject incomplete selection |
| BULK-06 P1 M | New REST reportList response missing CR: 163-row metadata silently discarded | PR #167; `ReportPayloadParser.parseRest` | Accept only same-request independently CR-verified context as *metadata*, reject actual mismatches |
| BULK-07 P1 C | REST rows with `testname/reqdno/reportdate` may have no specimen ID, original PDF URL, or clinical values | PR #167/#200/#201, `ReportRepository` | Missing identifiers remain blank; no “zero lab IDs” interpreted as parser failure to invent accession |
| BULK-08 P1 M | REST JSON nested arrays / mixed-case keys not captured accurately | PR #166, `RestReportDiagnostics`, `ReportPayloadParser` | Fixture reproducing observed nested and uppercase keys, record counts and provenance |
| BULK-09 P1 R | Enquiry investigation/orders source under maintenance or nested in frames; import failures | PR #165/#181; `EnquiryReviewActivity` | Verify actual authenticated UI, same-CR evidence and semantic difference between orders vs report index |
| BULK-10 P1 M | Missing raw/technical rows or dedup losing repeat same-day samples | `BulkParser`, `BulkAccumulator`; PR #161/#176 | Preserve raw rows, multiplicity, source ordinal and identical repeated true specimens |
| BULK-11 P2 C | Desktop README says “REST first” but implementation in `background.js` is bulk first → REST → browser | `README.md`, `dashboard/README.md` vs `fetchCrForDashboard` | Documentation must match actual code before changing retrieval order |
| BULK-12 P2 U | Desktop AppDeploy dashboard frontend source is outside repo; external contract drift cannot be checked here | root README | Validate deployed dashboard handshake/version and full payload with live browser |

## C. Signed PDFs, specimen identity, provenance and reconciliation

| ID / priority / state | Failure / root cause or evidence | Current code or historical repair | Acceptance test |
|---|---|---|---|
| SRC-01 P0 M | Title and date alone falsely treated as verified result/PDF identity | `ReportLinkResolver`, `SourceEventReconciliation`, `SourceMetadataReview` | Same-day different specimen must remain candidate/unlinked |
| SRC-02 P0 M | Same accession across multiple sections/documents: aspirate vs trephine, Giemsa vs culture, DAT vs IAT | PR #193/#199/#201; `PdfSourceEvidence` | Same accession must **not** collapse distinct tests or report days |
| SRC-03 P0 M | Blood culture set/bottle or interim 48h/final misassociation | `MicrobiologyPdfIdentity`, PR #199/#201 | Distinct bottle and stage fixtures; wrong bottle never linked |
| SRC-04 P0 M | Urine and blood/wound/other source PDFs mixed by generic “aerobic culture” labels | PR #185/#199/#200/#201 | Same-CR, same-date cross-specimen negative fixtures |
| SRC-05 P1 M | KOH, calcofluor, Gram, Giemsa, AFB and “STAINING” incorrectly classified | `SourceMetadataReview`, `MicrobiologyPdfIdentity`; PR #191/#200/#201 | Generic title permitted for discovery, signed content needed for identity |
| SRC-06 P1 M | v2.0.4's full-text PDF cache retained ~12 recent documents; eviction removed prior validated linking evidence | PR #201 introduced separate compact verified evidence ledger | Inspect >12 PDFs; earlier verified sources remain discoverable and never attach to wrong CR |
| SRC-07 P1 R | Source PDF link missing or shows “possible”, “PDF candidate”, generic audit labels on clinical cards | PR #189/#200/#201; `SourceMetadataReview` | Confirm appropriate Open PDF vs Review sources; internal labels restricted to diagnostics |
| SRC-08 P1 R | Culture lab/study ID, specimen, collection, sent and reporting dates absent or wrongly transferred | PR #180/#181/#190/#199 | Show one compact accurate source header; missing semantics left blank/explicit |
| SRC-09 P1 M | REST requisition `reqdno` treated as accession / lab ID | PR #200/#201; `ReportPayloadParser` | Never infer a lab/sample ID from requisition |
| SRC-10 P1 M | HTML source table parsing shifted columns when empty cells dropped | `ReportPayloadParser.parseHtml` | Preserve empty TD indices, correct lab/collected/report stage against actual HTML |
| SRC-11 P1 M | HTML/PDF inventory and bulk observations merged before proof, duplicate source PDFs/candidates | `ReportRepository.merge`, `UnifiedResultsFeed` | Inventory remains independent; distinct PDF URLs/sections preserved |
| SRC-12 P1 M | PDF patient CR conflicts with current selected CR | `ReportActivity.openOnWorker`, `PdfSourceEnricher` | Block mismatching source, retain clinical view without false linking |
| SRC-13 P1 U | PDF scanned/image-only or custom font/coordinate layout yields empty/misaligned PDFBox text | `PdfGeometryTable`, `PdfSourceEnricher`, `ReportActivity` | Clearly unresolved original PDF remains readable; no fabricated values |
| SRC-14 P1 M | Multi-section physical PDFs (e.g. LAC, blood bank) assigned only one section or duplicated | `PdfSourceEvidence`, `ReportTitleCache`; PR #193/#198 | Show each genuinely identified section without cross-assignment |
| SRC-15 P2 U | PDF source inventory may load slowly/fail and be misconstrued as missing structured results | `ReportRepository`, `MainActivity.loadSourceInventory` | Ready structured values remain usable while inventory times out; errors separately counted |
| SRC-16 P1 U | On-demand entire-source reconciliation may be slow or incomplete for long histories | `MainActivity.reconcileMicrobiologyIndex`, PR #201 | Bounded worker progress, cancelability, no silent “complete” claim; same evidence after eviction |

## D. Clinical interpretation and display fidelity

These are historical screenshot/field complaints and known parser hazards. Most have code mitigations; **none is accepted as clinically resolved on v2.0.5 without real-source comparison**.

| ID / priority / state | Observed/anticipated failure | Code and historical references | Required golden fixture |
|---|---|---|---|
| CLIN-01 P1 R | CBC/TLC position, Hb→TLC order, platelet/ESR missed, unwanted “others”/technical rows | `ClinicalResultsLayout`, `ClinicalPresentation`; PR #180/#183 | Full CBC and differential with all source rows/units |
| CLIN-02 P1 R | Creatinine, urea, Na/K/Cl, Ca/Mg and LFT wrong ordering or hidden individual values | `LabPanelMatrix`, `ClinicalChronology`; PR #176/#183 | Complete renal-first multianalyte panel |
| CLIN-03 P1 R | Urine examination: pH, specific gravity, ketones, glucose, epithelial cells wrong alignment or coded values | `PdfUrineSummaries`, `PdfClinicalValues`; PR #184/#188/#189 | Actual column-aware urine PDF and structured source |
| CLIN-04 P1 R | Urine culture negative/interim/final and contradictory narrative; wrong PDF linked | `MicrobiologyPdfIdentity.contradictoryCulture`; PR #199/#201 | Contradictory polarity, distinct accession/date and clean-catch specimens |
| CLIN-05 P1 R | Aerobic/fungal/AFB/Gram smears: source/lab ID, specific test class and narrative dropped | `MicrobiologyReportDisplay`, `FungalReportSummary`; PR #191/#199/#201 | KOH, calcofluor, ZN/AFB, Gram/Giemsa, culture distinct |
| CLIN-06 P1 R | Blood culture set/bottle, provisional 48h, final, organisms and AST not fully inline | `BloodCultureCard`, `CultureNarrative`; PR #177/#176 | Positive, negative, interim/final, multi-bottle and susceptibilities |
| CLIN-07 P1 R | BioFire/FilmArray/BGT complex panels flattened, truncated or interpreted as dropdown numbers | `ClinicalReportRenderer`, `ResultTableFormats`; PR #177/#183 | One complete target table including targets and controls |
| CLIN-08 P1 R | Direct/indirect Coombs result/control confused; graded 1+–4+ dropped | `PdfClinicalEvidence`, `PdfClinicalSummaries`; PR #191/#193 | Combined DAT/IAT, separate control and comment |
| CLIN-09 P1 R | PT/INR patient/control and APTT labels improperly mapped | `CoagulationDisplay`; PR #182/#198 | Patient PT, control PT, INR, APTT explicitly distinct |
| CLIN-10 P1 R | HIV/HBsAg/HCV, CRP, serology values and reference ranges rendered as ambiguous code or missing | `SerologyReportDisplay`, `ClinicalReadableSummaries`; PR #184/#197 | Positive/negative, cutoff, numeric, threshold and normal ranges |
| CLIN-11 P1 R | CSF/pleural/ascitic/joint fluid glucose/protein/specimen missing or assigned to wrong source | `PdfFluidChemistry`; PR #189/#198 | Two dates, two specimens and explicit protein/glucose labels |
| CLIN-12 P1 R | Bone marrow aspiration, trephine and smear distinguished poorly despite shared accession | `PdfSourceEvidence`, `PdfClinicalSummaries`; PR #198/#201 | Separate aspirate and biopsy with different reported times |
| CLIN-13 P1 R | Radiology/nuclear medicine/PET full narrative, interpretation and source headings disappear | `RadiologyNarrative`, `ClinicalReadableSummaries`; PR #183 | Multisection PET/radiology signed report |
| CLIN-14 P1 M | Less-than qualifiers `<1.0`, encoded `&lt;` and units lost during sanitization | `BulkParser.cleanValue`; PR #143 | Numeric qualifiers and scientific notation verbatim |
| CLIN-15 P0 M | Raw HIS coded `1/2/-1` displayed as definitive result/normal/negative without dictionary | `ClinicalPresentation`, `PdfClinicalValues`; PR #188/#189 | Only explicit verified mapping; otherwise preserve “unmapped” outside clinical values |
| CLIN-16 P1 M | Controls, administrative metadata, reference text or sampling instructions included as patient values | `ClinicalResultRules`, `ClinicalChronology` | Source-preserving exclusion, no hidden actual result |
| CLIN-17 P1 U | Date/time displayed for tests may be a source chart date, not actual reported time | `RecentReportDates`, `ClinicalChronology` | Separate chart, sent, collection and validated reported dates; no relabel |
| CLIN-18 P1 U | Positive/negative cultures and clinically important narratives may be shortened or filtered by search/scope | `ClinicalChronology.inlineRows`, `ClinicalReadableSummaries` | Exact full structured narrative visible without PDF or expansion requirement |
| CLIN-19 P1 U | Scope of snapshot “all results” may hide data that source omitted, rejected, or filtered | `AppDiagnostics`, `RawInvestigationRow` | Compare actual input cells→normalized→displayed one-by-one with exclusions reported |

## E. Clinical experience, PDF viewing, exports and focus

| ID / priority / state | Issue | Current/historical point of repair | Acceptance |
|---|---|---|---|
| UI-01 P1 R | All Results should be one newest-first scroll with **all** inline values; old grouping hid multiple CBC/creatinines | `ClinicalChronology`, `UnifiedResultsFeed`; PR #175/#176 | All dates, all repeats, long cultures, no extra drill-down needed for structured values |
| UI-02 P1 R | Duplicate PDF/source cards and noisy “pdf candidate” per report | `UnifiedResultsFeed`, `SourceMetadataReview`; PR #200/#201 | Distinguish independent source list from exact verified links; no redundant warnings |
| UI-03 P2 R | Overlong technical header: 190 source values / 93 parameters / 33 dates | PR #171/#180; UI layout | Compact date + test + full results, details only on demand |
| UI-04 P2 R | Inconsistent micro single-line lab/collection/reported/source header | PR #190/#199; `MicrobiologyReportDisplay` | One readable header, no misleading “sample number” label |
| UI-05 P2 R | ID Focus tab mixes markers/cultures and repeats expensive filtering | `IdFocusIndex`; PR #168 | Separate CRP/ESR/PCT/TLC/ANC; microbial episodes and trends correct |
| UI-06 P1 M | PDF viewer black screen, crashes on HorizontalScrollView image parameters, obstructed Save/zoom | `ReportActivity`, `FullClinicalPdfActivity`; PR #79/#176/#201 | Multi-page original PDF, swipes, pinch, save, rotation, no OOM |
| UI-07 P1 U | Combined 7/14/30/90-day/all clinical PDF vs original signed PDF list are different products | `FullClinicalResultsPdf`, `FullClinicalResultsDocument`; PR #200/#201 | Explicitly label derived summary; no claim it is the original signed PDF/bulk combined originals |
| UI-08 P2 M | QR/barcode CR input missed scans or selected unrelated 15-digit identifiers | `CrScanParser` + ML Kit offline fallback; PR #201 | QR, printed barcodes, ambiguous IDs, photo/gallery; no automatic wrong-patient fetch |
| UI-09 P2 M | Long list scroll, stale scroll after switching tabs/CR, overly broad results/category filters | PR #167/#168/#171 | Smooth scroll, filter stable, no hidden REST/catalogue metadata |
| UI-10 P2 R | Redundant Routine PDF links for CBC/biochemistry/serology/urine where full structured values are shown | Earlier user UI decisions; `MainActivity` | Routine values inline; keep originals accessible via separate source report list |
| UI-11 P2 U | Export selected/complete CSV may omit provenance or duplicate source rows | `FullClinicalResultsDocument`; PR #200 | Reconcile each output row against clinical feed; source fields only when explicit |

## F. Performance, state and code complexity

| ID / priority / state | Issue | Evidence/current mitigation | Acceptance |
|---|---|---|---|
| PERF-01 P2 C | `MainActivity.kt` ~**5,138 lines** in v2.0.5, four single-thread executors, broad mutable UI state | Current source | Profile lifecycle, cross-worker stale updates and contention before extracting components |
| PERF-02 P2 M | Large PDFBox parsing/rendering caused memory pressure; Java heap metrics alone inadequate | PR #168, `PdfPerformanceTrace`, bounded PDF cache | Measure Java/native/PSS, bitmap peak and p95 PDF-open; no device crash |
| PERF-03 P2 M | Bulk downloading hundreds of PDFs is expensive; source enrichment is bounded and asynchronous | `MainActivity.startPdfIndex`, PR #201 | Result view ready first, background PDF checks cancellable and bounded |
| PERF-04 P2 U | Evidence cache memory count vs completeness: prior 12-document eviction lost verified links; now 180 compact entries | `ReportTitleCache`, PR #201 | >12 PDFs, >180 candidate stress, no incorrect stale source reuse |
| PERF-05 P2 U | Repeated result processing and source matching might recompute on each render/search/keypress | `UnifiedResultsFeed.baseEntries`, `ReportLinkResolver` memo | Profile scroll, search, day filters; memo cleared on CR change |
| PERF-06 P2 U | WebView hidden DOM waits, REST fast timeout, HTML 15–20s, one-shot bulk 60s have different failure semantics | `nims_engine.js`, `ReportRepository`, `NimsEngine` | Track latency per stage and p95 end-to-end, distinguish timeout vs empty source |
| PERF-07 P2 U | Encrypted snapshot restore may show 72h-old data before live fetch | `ClinicalSnapshotCache`, main status | Clear cached label; never call restored data current |
| PERF-08 P3 D | ~60 Kotlin JVM test files, many version-suffixed, patch-by-patch logic and duplicated fixtures | `android-native/app/src/test` | Group tests by invariant and provenance; do **not** delete working coverage prematurely |
| PERF-09 P2 U | Android application size and PDF dependencies dated, but minification can break runtime | `build.gradle.kts`, PR #187 | Measure APK install size, startup and source parsing before targeted updates |

## G. Desktop, helper, project governance, release and privacy

| ID / priority / state | Issue | Evidence | Disposition |
|---|---|---|---|
| DESK-01 P1 M | www/apex postMessage cross-origin mismatch | PR #132, extension bridge | Regression test origin handshake |
| DESK-02 P1 M | Chrome MV3 invalid `match_origin_as_fallback` manifest paths blocked extension loading | PR #128/#114 | `chrome://extensions` real load test |
| DESK-03 P1 M | Chrome tab navigation can close message channel even after successful CR submit | `fetchCrForDashboardLegacy`; PR #122/#127 | Distinguish narrowly expected closure |
| DESK-04 P1 M | Browser fallback reused prior CR report list and could return stale patient | PR #117/#127; current mismatch checks | A→B CR transition with older tab open |
| DESK-05 P2 D | Extension grants `https://*.railway.app/*` and `*.up.railway.app/*` host permissions, broader than one configured endpoint | `extension/manifest.json` | Later scope after current remote functionality established |
| DESK-06 P1 C | Extension helper remote URL accepts HTTP; API key in extension local storage and sent in header | `background.js:normalizeHelperUrl/callHelper` | Validate endpoint TLS and deployment controls; no external patient-data transfer without institutional approval |
| DESK-07 P2 C | Remote helper's in-memory per-process rate limiting; backend caching defaults differ in local/remote | `helper/main.py`, `cache.py` | Deployment-mode audit; ensure no accidental PHI retention |
| DESK-08 P2 U | Deployed external dashboard not version-validated here | Root README | External dashboard runtime smoke test required |
| BUILD-01 P1 M | v1.0.52 signer private key lost; v2 uses side-by-side new persistent identity | `IN_PLACE_UPDATES.md`, PR #194/#195 | v2 same application ID/cert and monotonic versionCode |
| BUILD-02 P1 U | CI signer check and APK assembly do not test actual install-over-update | `android-release.yml` says `device-install-upgrade-test=not-run` | On-device install, preserved user data/session, launch and PDF |
| BUILD-03 P2 C | `AGENTS.md` forbids Android/PDF despite current Android feature; CI desktop-only contract and manifest exact `0.6.10` | `AGENTS.md`, `ci.yml` | Reconcile only after this documentation baseline |
| BUILD-04 P2 C | CI uses Gradle action rather than committed `gradlew`; branch triggers include stale historical refs | `ci.yml`, tree | Decide reproducible wrapper and simplify triggers without breaking builds |
| BUILD-05 P2 D | Dependabot/dependency scanning not configured, dated Compose BOM/PDFBox; GitHub actions tagged, not SHA-pinned | `build.gradle.kts`, `.github/workflows` | Track separate dependency-risk work |
| BUILD-06 P3 D | Open historical PRs (#138–#158) overlap merged evolution; roadmap Issue #80 remains open | GitHub PR/issues listing | Explicitly label superseded vs outstanding, don't merge old branch blindly |
| SEC-01 P1 C | WebView `addJavascriptInterface` is accessible from every frame; origin cannot be authenticated from method args | `NimsEngine.kt` | Implement origin/frame-aware bridge after documenting portal's legitimate frames |
| SEC-02 P2 C | NimsEngine/Login WebViews use `MIXED_CONTENT_COMPATIBILITY_MODE` | `NimsEngine.kt`, `LoginActivity.kt` | Test real HIS compatibility before changing mode |
| SEC-03 Later C | No global `FLAG_SECURE`; screen and recents may expose patient data | Android activity code | **User defers user-facing restrictions** |
| SEC-04 Later C | Local original PDF cache is not application-encrypted; 16 files/30-minute pruning is access-triggered | `ReportActivity` | **User defers restrictive controls**; do not falsely claim no cache eviction exists |
| SEC-05 Later C | MainActivity **does** implement optional device-credential app lock and 120s background re-lock; not on by default | `MainActivity.kt` | **User defers compulsory lock**; Claude's “no app lock” statement was incorrect |
| SEC-06 P2 U | Copy diagnostics uses clipboard; counts-only audit exists but all upstream messages must be checked | `AppDiagnostics.kt`, `MainActivity.copyDiagnostics` | Redaction regression, zero PHI/token leakage |
| SEC-07 P1 U | Helper supports remote Base64 PDF payloads; default is local, and Android PDF enrichment is local | `background.js`, `helper/main.py` | Prove actual deployment and approved transfer before claiming all PDFs are uploaded |

## Important observations that are **not yet evidence of a current bug**

1. PR #201 claims to repair verified PDF evidence persistence and broaden microbiology matching. **Merged code and synthetic tests do not establish that the installed phone or NIMS portal has validated these fixes.**
2. Older screenshots/diagnostics from Android v1.0.28–v1.0.52 describe genuine historical failures, but do not automatically reproduce under separate v2 app identity.
3. The desktop README's current retrieval-order description is demonstrably outdated; the **code**, not the README, decides what happens.
4. No statement that a “PDF is verified” should be made without distinguishing CR-verified **document identity** from verified **result-to-PDF association**.
5. Source inventory row count, original PDF count, JSON cell count and complete clinical values are **different metrics**; one cannot substitute for another.
6. HTTP redirects are a verified **unsafe coding pattern**, not evidence that a real NIMS session cookie has already been stolen.
7. Security/access restrictions beyond foundational cookie egress and identity checks remain deferred by explicit product decision; do not impose them as prerequisites for clinical UI improvements.

## Minimal order for investigation (not implementation yet)

1. **Record one real NIMS request trace, redacted:** login state → CR banner → source test/date checkbox counts → GETMETABOLICDATA status, duration, group/cell counts.
2. **Reconcile source completeness:** source cell count → `BulkParser` count → chronology displayed count; separately count HTML/REST inventory, CR-verified PDFs, exact links and unresolved candidates.
3. **Build gold-standard clinical fixtures** for blood culture interim/final/bottles; fungal/AFB/Gram/Giemsa; urine culture/CUE; fluid chemistry; DAT/IAT; marrow aspirate and trephine; CBC/LFT/PT/INR; BioFire/BGT/PET.
4. **Reproduce** a v2.0.5-specific mismatch before modifying clinical parsing or PDF identity gates.
5. **Profile** actual memory, response times and source PDF cache pressure; do not delete the PDF evidence mechanism solely because it appears complex.
6. **Review** authenticated redirect and helper endpoint controls as isolated network changes with explicit NIMS compatibility tests.
7. **Then** plan small PRs, one root cause at a time, preserving source lineage, signer, patient state and the validated fallback.

**Audit limitations:** no NIMS authenticated live traffic, private clinical PDF corpus, deployed dashboard code, build logs for every merged head, on-device crash logs or release installation verification were available for this documentation-only pass. All issues marked R/U require direct evidence before claiming resolution.
