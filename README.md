# NIMS Fast Summary

NIMS Fast Summary is a local Chrome extension plus Python helper for summarizing NIMS e-Sushrut/HIS report-list pages after you have logged in manually.

It does not automate login, store credentials, bypass captcha/OTP, or send patient identifiers to external AI services. The MVP focuses on extracting report rows, parsing local report/PDF content, and showing lab trend and culture tables.

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
5. Open NIMS HIS.
6. Login manually.
7. Go to the report-list page after entering the CR number.
8. Click `Fast Summary`.
9. View or copy the tables.

## Test With Mock Page

Start the helper, load the extension, then open:

```text
extension/test_pages/mock_report_list.html
```

The mock page includes fake/de-identified report rows and exercises the toolbar UI. It does not contain real patient data.

## What Is Built

- Chrome Extension Manifest V3 under `extension/`
- Local FastAPI helper under `helper/`
- Robust visible table row extraction for rows containing `View Report`
- Background fetch scaffolding using the active Chrome session
- Parser endpoints for CBC, RFT/electrolytes, LFT, coagulation, culture, radiology, and other reports
- Parsed JSON cache, never raw PDF cache
- Lab trend table latest-to-old
- Culture table
- Export JSON/CSV and copy summary buttons
- Fake parser fixtures and pytest coverage

## Known Limitations

- Live NIMS popup/form workflows may need adjustment in `extension/src/contentScript.js` and `extension/src/background.js` after testing on the real page.
- POST-only report viewers are detected and reported as `Needs manual support`.
- OCR is intentionally disabled by default.
- AI interpretation is optional and not required for the MVP.

## Security

See `SECURITY.md`. Do not commit real PDFs, screenshots, patient identifiers, credentials, or cache files.

