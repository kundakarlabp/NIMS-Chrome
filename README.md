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
13. If rows show `Onclick: yes` and `Href: no`, the extension will attempt the controlled onclick/popup workflow.
14. Click `Fast Summary`.
15. Verify the generated values against source reports before clinical decisions.

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
- Controlled onclick/popup report opening for NIMS rows that have `View Report` onclick handlers but no direct href
- `Copy Safe Mapping Diagnostics` for sharing function names, argument counts/kinds, and input names without raw onclick values or hidden input values
- Background fetch scaffolding using the active Chrome session
- Parser endpoints for CBC, RFT/electrolytes, LFT, coagulation, culture, radiology, and other reports
- Parsed JSON cache, never raw PDF cache
- Row-index values such as `row-1` are not trusted as cache keys
- Chrome storage and JSON export are sanitized by default to avoid raw row text, URLs, onclick code, and report previews
- Lab trend table latest-to-old
- Culture table
- Export JSON/CSV and copy summary buttons
- Fake parser fixtures and pytest coverage

## Known Limitations

- Live NIMS popup/form workflows may need adjustment in `extension/src/contentScript.js` and `extension/src/background.js` after testing on the real page.
- POST-only report viewers are detected and reported as `POST workflow needs live-site mapping`.
- `Diagnose Page` shows only sanitized host/path frame information and row previews; it strips query strings and does not show raw row text, onclick code, cookies, tokens, or credentials.
- First live Fast Summary is intentionally limited to a small selected set: latest 3 CBC reports, latest 3 renal/liver/electrolyte reports, and all culture reports.
- If the onclick/form flow cannot be mapped safely, the per-report error says `NIMS onclick/form workflow needs specific mapping`.
- If a fetched page is login/session-expired HTML, it is reported as failed and is not parsed as a lab report.
- OCR is intentionally disabled by default.
- AI interpretation remains disabled/rule-based for now.

## Security

See `SECURITY.md`. Do not commit real PDFs, screenshots, patient identifiers, credentials, API keys, logs, or cache files.

