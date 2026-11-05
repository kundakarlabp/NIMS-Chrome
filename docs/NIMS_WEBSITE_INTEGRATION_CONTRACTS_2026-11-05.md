# NIMS Results — verified architecture and HIS integration contract

**Audit date:** 2026-11-05 (IST)  
**Source inspected:** `kundakarlabp/NIMS-Chrome`, `main` at `21baeb0` (Android **2.0.5**, versionCode **20006**). Historical comparison: Android 2.0.4 `1f9bda2`; selected merged PRs and Issues #7/#80.  
**Status:** Source-code and historical-evidence audit, **not** an authenticated live NIMS site audit, emulator run, installed-phone test or clinical validation. This is a description and change-control baseline, **not a new endpoint/API contract supplied by NIMS**.  
**Scope:** Android native app, Chrome MV3 extension, optional Python helper, NIMS HIS entry points and report provenance. The deployed AppDeploy dashboard implementation is **not in this repository**.

Read alongside [ISSUE_REGISTER_2026-11-05.md](ISSUE_REGISTER_2026-11-05.md) and [NO_REGRESSION_VALIDATION_2026-11-05.md](NO_REGRESSION_VALIDATION_2026-11-05.md). No source code or authentication behavior was altered in this documentation phase.

## 1. Components and boundaries

```text
                  Official NIMS HIS / e-Sushrut (manual login, CAPTCHA)
                   /                                  \
      Native Android app                       Desktop Chrome session
      (Compose UI, hidden WebView)             (MV3 extension, tab/frame scripts)
          |                                          |
  Investigation Trends -> Custom Sheet      Investigation Trends -> Custom Sheet
  -> GETMETABOLICDATA capture               -> GETMETABOLICDATA capture
          |                                          |
  BulkParser -> ClinicalBundle              bulkNormalizer -> canonical bundle
          |                                          |
  Source inventory (REST + HTML)            reportList discovery / browser fallback
          |                                          |
  CR-verified PDF evidence                  optional local / configured remote helper
          |                                          |
  All Results / Micro / ID / PDFs            Deployed dashboard [external repository]
```

* **Android** does **not** run the desktop extension, pair with a laptop, or rely on a dashboard relay. `NimsEngine.kt` + `nims_engine.js` work within an authenticated, hidden WebView. `MainActivity.kt` owns request/session state, asynchronous work, clinical UI and exports; `BulkParser.kt` normalizes structured results; `ReportRepository.kt` obtains an independent report inventory.
* **Desktop** uses `dashboardBridge.js` → `background.js` → NIMS tab/frame scripts. Its **actual current code path** in `fetchCrForDashboard` is **structured bulk first**, REST report-list/report processing second, authenticated CR-wise browser/report helper fallback third. The root `README.md` and `dashboard/README.md` still misleadingly say “REST first”; do not follow those sentences to reorder the code.
* **Helper** is a deterministic FastAPI PDF/text parser. Chrome extension `DEFAULT_HELPER` is local `127.0.0.1:8765`. Optional remote mode can transmit Base64 PDF contents; it is a distinct institutional data-processing boundary, and is not required for the Android bulk path.
* **Signing**: legacy v1 `org.kundakarlab.nimsresults` and side-by-side v2 `org.kundakarlab.nimsresults.recovery` are different apps. Do not change v2 package name, installed signer identity, or delete v1 encrypted data. Consult `android-native/IN_PLACE_UPDATES.md`.

## 2. NIMS-specific observed behavior: do not simplify away

| HIS behavior or historical difficulty | Evidence / adaptation | Invariant for any fix |
|---|---|---|
| Login URI can stay `/AHIMSG5/hissso/loginLogin.action` after a successful login while an authenticated frameset replaces page contents | `nims_engine.js:probe`, PRs #79, #137 | Do not classify login required solely by URL or by the word “login” in authenticated navigation scripts |
| Portal uses an outer shell, frames/iframes, nested and sometimes cross-origin www/apex content | `nims_engine.js:collectDocuments`, `trendsFrame`; `navigationCore.js`; PRs #99/#121/#127/#132 | Identify the **actual** visible form and expected frame; don't assume top-level URL/DOM, fixed iframe ID, or unrestricted cross-origin DOM access |
| Actual CR form uses `patCrNo`, a real Go/Search control, and `hmode=SHOWPATDETAILS`; generic text boxes are false positives | PR #99 and current native `nims_engine.js:run`; `nimsSessionBridge.js:submitCrNumber` | Prove real form readiness; set value + input/change events; preserve native form semantics; do not click unrelated “View Report” |
| Login, CAPTCHA and session management are hospital-controlled; transient network/DNS errors are not proven logout | `LoginActivity.kt`, `NimsLoginRecovery.kt`, PR #162 | Retain manual authentication, retry network errors without erasing validated results/cookies; never automate CAPTCHA |
| `Investigation Trends → Custom Sheet` has separate patient banner, test checkboxes, date checkboxes and **Apply** | `nims_engine.js:run` | Require same CR visibly confirmed; verify every intended test/date was selected; never equate a merely visible form with complete results |
| Lab values come from the intercepted **GETMETABOLICDATA** response, not from `reportList` | `nims_engine.js:installCapture`, `BulkParser.kt`; PRs #123/#130/#161 | Structured bulk is primary for values; detect XHR and fetch; pin response to initiating `requestId` and patient, prevent stale cross-patient delivery |
| Current Android native strategy is **one full Custom Sheet request** with a 60s JS response timeout, not the historical staged 14-day strategy | `nims_engine.js` comment “One complete NIMS Custom Sheet request”; `MainActivity.onBulk`; earlier PR #158 | Do not quietly reintroduce staged retrieval or infer historical completeness from non-empty results; test large real histories before changing strategy |
| `GETMETABOLICDATA` Custom Sheet dates may be chart/source dates, not collection/reported timestamps | `BulkParser.kt`, `ClinicalChronology.kt`, `IN_PLACE_UPDATES.md` | Never relabel a source date as sent, collection or reporting date without explicit source field evidence |
| `/HBIMS/services/restful/invService/reportList?crNo=...&hosCode=33101` has been observed returning fields such as `testname`, `reqdno`, `reportdate`, potentially no returned CR and no PDF URL or accession | Device diagnostics and PRs #166/#167/#200/#201; `ReportPayloadParser.parseRest` | Treat as **catalogue/metadata**, not a full results feed. Context-bound no-CR rows are allowed only after independent same-request Custom Sheet CR verification. Explicit different CR is a hard failure |
| CR-wise report inventory HTML contains source-lab/document metadata and report links not necessarily in Custom Sheet or REST | `ReportRepository.loadHtml`, `ReportPayloadParser.parseHtml`, PR #201 | Keep HTML inventory independent and optional; use form's protected hidden fields; HTML timeouts should not suppress already-retrieved structured labs |
| NIMS may expose `View Report` via `onclick=printReport(...)` rather than direct href, and PDF popup can destroy list context/appear black | Issue #7; PR #79; `nimsSessionBridge.js`, `ReportActivity.kt` | Preserve transient PRINTREPORT argument discovery; preserve exact NIMS PDF viewer; avoid navigating the primary clinical results WebView to the raw PDF |
| One accession can cover **multiple** documents or sections (CBC/panels; marrow aspirate/trephine; stain/culture; combined DAT/IAT); repeated same-day specimens exist | `ReportLinkResolver.kt`, `SourceEventReconciliation.kt`, PRs #176/#193/#199/#201 | Never deduplicate by title/date or accession alone; retain accession + specimen + section + report stage + bottle/set + source document identity |
| Generic source titles (STAINING, BACTERIOLOGY, aerobic culture) can conceal a more specific verified report | `SourceMetadataReview.kt`, `MicrobiologyPdfIdentity.kt`, PRs #199/#200/#201 | Widen **candidate discovery** only; label a particular specimen/test only from signed PDF plus compatible identity/content |
| Original PDFs may use positional text, split table columns, encoded less-than signs, image-only content, and interim/final revisions | `PdfGeometryTable.kt`, `PdfClinicalValues.kt`, `BulkParser.cleanValue`, earlier PDFBox parser issues | Preserve full value/narrative, qualifiers, control/patient differentiation and original PDF. Never turn unresolved codes into positive/negative clinical findings |
| Chrome MV3 navigation may close the message channel during successful page submission / back-forward cache restoration | `background.js:fetchCrForDashboardLegacy`, PRs #117/#122/#127 | Correlate request/CR; interpret **known** navigation closures narrowly; do not suppress unrelated errors |
| www/apex cross-origin dashboard messaging previously failed because of target origin mismatch | PR #132; `dashboardBridge.js`, `bulkCaptureMain.js` | Preserve exact-origin postMessage checks and explicit origin handshake; browser and Android environments are not interchangeable |

**Sources are observed implementation/field evidence, not a claim that every NIMS server response will have that schema.** Internal protected paths/filenames are documented for maintenance; never add authenticated URLs containing patient CRs/tokens to logs or public fixtures.

## 3. Android request lifecycle (current main)

1. `MainActivity.requestFetch` validates a 15-digit CR, creates a new request ID, clears request-scoped state, and checks the saved NIMS session.
2. `SessionVault` restores per-origin protected cookies; `NimsEngine` uses WebView state/probe and routes the clinician to `LoginActivity` if needed.
3. `nims_engine.js.openTrends` navigates authenticated frames. `run` waits for CR input, verifies the displayed CR banner, opens Custom Sheet, selects all test/date checkboxes, and arms fetch/XHR interception **before** Apply.
4. The intercepted bulk response is delivered through `AndroidNimsBridge.onBulk` only for the owning `requestId` and same visible patient CR.
5. `MainActivity.onBulk` independently checks request ID, accepted patient header and complete checkbox counts; `BulkParser.parse` preserves source-oriented observations. UI becomes READY **before** optional PDF inventory finishes. An encrypted, explicitly cached/not-live snapshot is saved.
6. `ReportRepository.load` runs on `inventoryWorker`: REST catalogue plus authenticated HTML inventory; if source URLs remain unavailable it may request the CR-wise HTML through the same WebView. Report inventory is not proof every study has a PDF link.
7. `PdfSourceEnricher` inspects bounded candidate PDFs in a separate worker. `ReportTitleCache` retains compact verified evidence separately from heavy text (new in PR #201). `ReportLinkResolver`, `SourceMetadataReview`, `MicrobiologyPdfIdentity` decide **verified link vs review candidate vs no link**.
8. `ClinicalChronology`, `UnifiedResultsFeed`, domain renderers, and PDF/CSV export expose values and provenance. Dedicated `ReportActivity` opens original PDFs; `FullClinicalPdfActivity` displays an **app-generated clinical summary** (not an original signed NIMS report).
9. Cancellation, new CR, renderer death, session expiry, cache restoration, empty REST and late background inventory completion must not display another patient's data or silently overwrite current clinical observations.

**Independent measures:** source Custom Sheet groups/cells, accepted structured observations, enquiry orders, REST catalogue rows, HTML inventory rows, unique PDF URLs, downloaded PDFs, PDF headers with matching CR, unique result-to-PDF links, unsupported/missing source fields. A single total like “results=485” cannot prove these quantities all agree.

## 4. Source-of-truth and provenance matrix

| Data attribute | Trusted primary evidence | Never infer from |
|---|---|---|
| Patient identity | Same-request visible NIMS patient header / explicit returned matching CR / PDF header for the PDF itself | Typed CR, a cookie, report title, old browser tab |
| Quantitative/qualitative source value | Exact structured Custom Sheet cell, or independently CR-verified and visibly identified signed PDF evidence | `reportList` row, raw HIS dropdown integer, normal range, neighbor text |
| Full narrative, organism and AST | Full labelled structured narrative or compatible verified PDF | A keyword in a specimen comment, code, truncated summary |
| Investigation date | Source field with its **own stated semantics** | Assuming chart date means collection date |
| Lab/accession/specimen number | Explicit corresponding NIMS HTML source/PDF header or structured field | REST requisition ID (`reqdno`), title/date coincidence |
| Sent / collected / reported time | Explicit field, labelled report-header time | REST generic `reportdate` being automatically used as both |
| Preliminary / final; blood-culture set/bottle | Explicit narrative, stage, bottle and source evidence | Same accession, date or identical AST |
| PDF identity | Unique CR-verified PDF with compatible test/specimen/stage and sufficient event evidence | One approximate name/date candidate |
| PDF candidate | User-review-only source inventory shortlist | Treating one candidate as automatically linked |
| Enquiry orders | Authenticated, separately verified Enquiry source rows | Interpreting source-index reports as imported orders |
| Cached results | Encrypted snapshot clearly labelled cached/stale until refresh | Claiming a successful current NIMS fetch |

## 5. Historical pivots and why previous simplifications regressed

- **May–August:** extension + optional local helper, direct report PDF mapping, PDF popup/black window and false session expiry; actual form `patCrNo` readiness fixes (Issue #7, PRs #1/#79/#99).
- **September–October:** conflicting “REST first” vs proven structured Custom Sheet bulk; repeated dashboard connector regressions in session rediscovery, per-frame navigation, closed message ports, identity-less REST rows and exact-origin messaging. PRs #107/#117/#119/#121/#127/#129/#130/#132/#133.
- **15 October:** fresh native Compose Android client was introduced to avoid old WebView/dashboard-based UI. The hidden WebView is now **only an authenticated NIMS engine**, not the principal results UI (PR #136).
- **Late October:** progressive fixes to source mapping, synthetic vs real schema differences, fast single-scroll view, PDF rendering, Enquiry capture and REST identity-less catalogue (PRs #161/#166/#167/#176).
- **1–5 November:** Coombs, fluids, stains, cultures, same-accession multi-document reports, persistent signing, PDF source evidence eviction, viewer ergonomics and barcode scanning (PRs #184–#201). Code changes in PR #201 should be tested against *real* microbiology PDFs before calling older complaints resolved.
- **Administrative contradiction:** `AGENTS.md` still forbids Android and PDF parsing despite current legitimate code and root README; CI's repository-contract is partly scoped to a deprecated “desktop only” vision and pins extension manifest v0.6.10. Documentation needs eventual reconciliation before architectural enforcement changes.

## 6. Boundaries of this audit

The authenticated NIMS pages, real patient PDFs, external AppDeploy dashboard code, current Railway deployment settings, installed phone app/build, in-place upgrade and live camera were **not** exercised. Prior PR descriptions are implementation claims and design context, not proof of live clinical correctness. All future defect fixes should first reproduce the relevant failure without removing the fallback and clinical provenance safeguards that accumulated through these versions.

**Do not in this phase:** rewrite the engine, delete fallback pathways, force device/app lock or screenshot blocking, replace manual login, add relay architecture, upload identifiable patient data to GitHub, change the package/signer, or change parser behavior based solely on synthetic test success.
