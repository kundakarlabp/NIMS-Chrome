# NIMS Fast Summary

NIMS Fast Summary is a local Chrome extension plus Python helper for summarizing NIMS e-Sushrut/HIS report-list pages after you have logged in manually.

This is the V2 safety-improved MVP. It does not automate login, store credentials, bypass captcha/OTP, or send patient identifiers to external AI services. The main value is accurate local tables; do not use output for clinical decisions until manually verified against source reports.

## Setup

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

