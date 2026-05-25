# NIMS Fast Summary

NIMS Fast Summary summarizes NIMS e-Sushrut/HIS report-list pages after the user logs in manually. It does not automate login, store credentials, bypass captcha/OTP/session expiry, or call external AI services.

Recommended modes:

1. Android WebView + Railway helper: no laptop runtime MVP. The phone logs in to NIMS manually, fetches reports with the WebView session, and sends report content to Railway helper for parsing.
2. Chrome extension + Railway helper: desktop browser session fetches reports, Railway parses/summarizes.
3. Local helper: development and fully local desktop use.

Railway removes the laptop-hosted Python dependency, but it does not replace manual NIMS login/session access. Railway receives report content for parsing, so verify source reports before clinical decisions.

Deployment and mobile docs:
- [Railway deployment](docs/RAILWAY_DEPLOYMENT.md)
- [Android WebView app](docs/ANDROID_WEBVIEW.md)

## Setup

### Desktop Local Mode

1. Install Python 3.11 or newer.
2. Clone this repository.
3. Start the helper:

```powershell
cd helper
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --host 127.0.0.1 --port 8765
```

On Mac/Linux activation is:

```bash
source .venv/bin/activate
```

4. Load the Chrome extension:
   - Open `chrome://extensions`
   - Enable Developer mode
   - Click Load unpacked
   - Choose the `extension/` folder
5. First test on the mock page below.
6. Open NIMS HIS.
7. Login manually.
8. Open `CR No Wise Result Report Printing New`.
9. Enter the CR number and wait for the report-list table.
10. Open the NIMS Fast Summary side panel.
11. Click `Diagnose Page`.
12. Confirm the `HISInvestigationG5` iframe has `View Report` rows.
13. Confirm helper status shows `ok`.
14. Click `Discover Mapping`. This performs one controlled `View Report` click to learn the current NIMS `printReport(...)` network request shape.
15. Click `Test Direct Fetch`. This should fetch one report silently without visibly opening a PDF and validate the mapping only if the helper parses at least one value or culture.
16. Only after `Test Direct Fetch` succeeds, click `Bulk Fast Summary` or `Bulk Full Summary`.
17. Verify the generated values against source reports before clinical decisions.

### Desktop With Railway Helper

This mode keeps NIMS fetching in the logged-in Chrome session, but sends fetched report content to a Railway-hosted helper for parsing/summarizing. Railway never logs in to NIMS and must never receive NIMS cookies, tokens, usernames, passwords, captcha, or OTP values.

1. Deploy the helper service from this repository to Railway.
2. Use `helper/Dockerfile` or the root `railway.json` Dockerfile config.
3. Set Railway environment variables:

```text
NIMS_HELPER_REMOTE_MODE=true
NIMS_HELPER_API_KEY=<strong random key>
NIMS_HELPER_DISABLE_RAW_LOGS=true
NIMS_HELPER_CACHE_ENABLED=false
NIMS_HELPER_ALLOWED_ORIGINS=<your chrome-extension:// origin if needed>
NIMS_HELPER_MAX_BODY_MB=25
```

4. Confirm the public Railway `/health` URL returns safe service metadata.
5. In the extension side panel, set `Helper mode` to `Remote Railway`.
6. Enter the Railway helper URL and API key.
7. Click `Test Helper Connection`.
8. Continue the normal workflow: `Diagnose Page` -> `Discover Mapping` -> `Test Direct Fetch` -> `Bulk Fast Summary`.

The extension still performs direct NIMS report fetching in the browser session. Only report PDF/HTML/text content is sent to the helper for parsing. If Railway returns `Remote helper unauthorized. Check API key.`, update the saved API key.

### Android WebView Mobile Mode

The mobile app scaffold lives under `mobile/android/`. It is a single-activity Kotlin WebView app that loads NIMS, uses manual login only, injects controlled shared JavaScript from `shared/nims-web/nimsReportCore.js`, fetches reports with the active WebView cookie session, and posts report content to the configured Railway helper.

1. Install the debug APK on the phone.
2. Enter the Railway helper URL and API key.
3. Open NIMS in the in-app WebView.
4. Login manually.
5. Open `CR No Wise Result Report Printing New`.
6. Run `Diagnose Page` -> `Discover Mapping` -> `Test Direct Fetch` -> `Bulk Fast Summary`.

The Android app must use the active WebView/NIMS session for report fetching and must not store NIMS credentials or bypass captcha/OTP/session controls.

Android build steps:

```bash
cd mobile/android
./gradlew test
./gradlew assembleDebug
```

If opening in Android Studio, open the `mobile/android/` folder, let Gradle sync, then run the `app` debug configuration. The helper API key is stored through Android Keystore-backed encryption. NIMS credentials are not stored by the app.

## Railway Deployment

The helper listens on `0.0.0.0` and uses Railway `PORT` when deployed:

```bash
uvicorn main:app --host 0.0.0.0 --port ${PORT:-8765}
```

For Railway:

1. Create a Railway service from this GitHub repo.
2. Use the root `railway.json`, or set the service root to `helper/` and use `helper/Dockerfile`.
3. Set the environment variables listed in `Desktop With Railway Helper`.
4. Deploy.
5. Open `https://<service>.up.railway.app/health`.

Do not deploy a public unauthenticated helper. In remote mode, `/parse-report`, `/summarize`, `/cache-lookup`, and `/clear-cache` require `X-NIMS-HELPER-KEY`.

## Test With Mock Page

Start the helper, load the extension, then open:

```text
extension/test_pages/mock_report_list.html
```

The mock page includes fake/de-identified report rows and exercises the toolbar UI. It does not contain real patient data.

Also test the delayed mock page, which simulates AJAX/postback table insertion:

```text
extension/test_pages/delayed_mock_report_list.html
```

After mock testing, test only on de-identified real PDF/report output before any live clinical workflow.

## What Is Built

- Chrome Extension Manifest V3 under `extension/`
- Local FastAPI helper under `helper/`
- Robust visible table row extraction for rows containing `View Report`
- Dynamic toolbar detection with `MutationObserver` and a short periodic page scan
- Iframe support for `AHIMSG5` and `HISInvestigationG5` on both `nimsts.edu.in` and `www.nimsts.edu.in`
- Side-panel run buttons and `Diagnose Page` for iframe-based NIMS report pages
- Direct silent bulk fetching after `Discover Mapping`, without opening each PDF one by one
- `Test Direct Fetch` for validating the discovered mapping on one report before bulk runs
- Confirmed NIMS `iframe#setPdf` template support for `/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt?hmode=PRINTREPORT&fileName=<transient argument>`
- Safe direct-fetch response classification for PDF, text report, login/session HTML, report viewer HTML, duplicate-report pages, generic HTML, empty responses, wrong endpoints, and unsupported content
- `Copy Direct Fetch Diagnostics` for safe endpoint/path, method, field-name, status, content-type, classification, and parse-count details
- Separate `Manual Popup Fallback` for slow one-by-one visible popup capture when direct mapping fails
- Background-mediated helper calls for health, parsing, summarizing, and cache clearing so NIMS iframes do not directly call localhost
- Safe onclick/form workflow diagnostics for NIMS rows with `onclick=yes` and `href=no`
- Background fetch scaffolding using the active Chrome session
- Parser endpoints for CBC, RFT/electrolytes, LFT, coagulation, culture, radiology, and other reports
- Parsed JSON cache, never raw PDF cache
- Safe `report_key:` hash cache lookup so repeat runs can reuse parsed reports before downloading report bytes again
- Row-index values such as `row-1` are not trusted as cache keys
- Chrome storage and JSON export are sanitized by default to avoid raw row text, URLs, onclick code, and report previews
- Lab trend table latest-to-old
- Culture table
- Export JSON/CSV and copy summary buttons
- Fake parser fixtures and pytest coverage

## Direct Bulk Workflow

`Bulk Fast Summary` and `Bulk Full Summary` use direct silent fetch only. They do not use the popup/open-close fallback by default. If the mapping is not validated by a successful `Test Direct Fetch` in the current session, they stop with `Direct report mapping is not validated. Run Discover Mapping, then Test Direct Fetch first.`

`Bulk Fast Summary` initially selects latest 3 CBC reports, latest 3 renal/liver/electrolyte reports, all culture reports, CRP/procalcitonin when present, and caps the run at 20 reports. `Bulk Full Summary` processes all visible rows with concurrency 3, capped internally at 5.

`Manual Popup Fallback` remains available as a separate explicit button. It may visibly open reports one by one and is expected to be slow.

The first full run can still take time depending on NIMS response speed. Repeat runs should be faster when safe parsed-report cache keys are available.

If direct mapping fails, use `Copy Direct Fetch Diagnostics`. The copied text intentionally includes only host/path, method, whether `setPdf` was discovered, query parameter names such as `hmode` and `fileName`, response status, content-type, response classification, parameter names, POST field names, selected report name/date/department, parse count, and parse errors. It excludes raw URLs, query strings, hidden values, raw `onclick`, raw `printReport` arguments, raw `fileName` values, cookies, tokens, CR number, identifiers, raw HTML, raw PDFs, and raw report text.

## Known Limitations

- Direct request mapping is inferred as a candidate from one controlled live report click. It is marked validated only after `Test Direct Fetch` retrieves and parses one report.
- If direct fetch returns viewer HTML or a duplicate-report page, the extension classifies that result and reports that second-stage mapping may be needed.
- `Diagnose Page` shows only sanitized host/path frame information and row previews; it strips query strings and does not show raw row text, onclick code, cookies, tokens, or credentials.
- Bulk modes do not silently fall back to popup capture.
- If a fetched page is login/session-expired HTML, it is reported as failed and is not parsed as a lab report.
- OCR is intentionally disabled by default.
- AI interpretation remains disabled/rule-based for now.

## Security

See `SECURITY.md`. Do not commit real PDFs, screenshots, patient identifiers, credentials, API keys, logs, or cache files.

