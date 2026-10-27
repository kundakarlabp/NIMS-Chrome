# NIMS Results — Desktop Connector and Native Android App

This repository contains the Chrome connector for the desktop NIMS Results Dashboard and the independent native Android client under `android-native/`. Both use authenticated NIMS HIS retrieval and must preserve source provenance.

## Live dashboard

https://nims-results-cockpit-ne5nig.v2.appdeploy.ai/

## Canonical flow

```text
Dashboard
   ↓
Chrome connector
   ↓
manual clinician NIMS login / CAPTCHA
   ↓
authenticated NIMS session
   ├─ REST reportList discovery first
   │    └─ verified NIMS report fetch + parser
   └─ existing CR-wise browser bridge fallback
        └─ proven direct-report processor + parser
   ↓
canonical structured results
   ↓
Dashboard
```

The REST endpoint is an optimization layered on top of the existing working bridge. It does not replace authentication and it does not bypass CAPTCHA.

## Repository boundaries

- The deployed `appdeploy.ai` dashboard frontend is not included here; desktop dashboard code/deployment must be independently identified and verified.
- The Chrome connector and native Android client are separate runtimes. Do not assume their builds or retrieval results are identical.
- There is no mobile or ChatGPT relay, side-panel product, Chrome Web Store publishing workflow, or shared backend authentication bypass.

## Runtime components

- `extension/src/dashboardBridge.js`: dashboard ↔ extension contract.
- `extension/src/nimsSessionBridge.js`: authenticated NIMS session, CR navigation and dashboard bridge.
- `extension/src/nimsRestApi.js`: HBIMS reportList adapter and safe NIMS report URL/token policy.
- `extension/src/navigationCore.js`: NIMS navigation/report-row logic.
- `extension/src/contentUtils.js` + `contentScript.js`: the proven report processor used only by the fallback path; its old toolbar UI is disabled.
- `helper/`: deterministic report parser used by the proven retrieval path.

## Routine use

1. Keep the validated connector loaded in Chrome.
2. Open the dashboard.
3. Click **Connect NIMS**.
4. Complete NIMS login and CAPTCHA manually. If a CR request started while logged out, the same CR resumes automatically after login.
5. Return to the dashboard; it detects the authenticated session.
6. Enter the 15-digit CR and click **Get results**.
7. REST is attempted first. If it cannot supply usable reports, the dashboard automatically uses the authenticated browser bridge.
8. The dashboard's connection help can read masked parser-helper status and save the Railway helper URL/API key directly into extension-local storage; the full key is never read back into the page.

## Development validation

```bash
npm ci
npm test
pip install -r helper/requirements-dev.txt
PYTHONPATH=helper python -m pytest -q tests/test_parsers.py
```

## Native Android app

- Sources: `android-native/app/src/main/`; Gradle project: `android-native/`.
- Clinical ingestion: `BulkParser.kt` retains source rows, distinct accession/specimen/report identifiers, and source-scoped observation identity.
- Report inventory: `ReportRepository.kt` keeps New table Report No. and source document metadata distinct; matching by title/date alone is not identity verification.
- Source links: `ReportLinkResolver.kt` requires PDF-derived CR and accession evidence to mark automatic association verified. Inventory-only matches remain suggestions.
- PDFs: `PdfSourceEvidence.kt` indexes multiple report sections while keeping original PDFs available. The runtime deliberately does not prefetch every PDF, to limit memory/network load.
- Build: GitHub Actions runs unit tests, lint, and `assembleDebug`. Successful builds upload a debug APK artifact. The APK is **not** a reproducible production-signed release; debug signatures may differ between runners.

## Release verification and known limits

Unit tests and a valid APK archive do not prove that a particular phone runs the latest commit, that all NIMS HIS sources were captured, or that the deployed desktop dashboard uses this model. Capture installed version/build provenance and perform an authenticated same-CR reconciliation before declaring clinical end-to-end completeness. Track Enquiry rows, raw Custom Sheet cells, structured observations, inventory rows, received PDFs, rendered PDFs and fully identity-verified PDFs independently. Never infer completeness from a nonempty result set.
