# Android WebView App

The Android app is a zero-cost, local-first NIMS report viewer. It loads NIMS in a WebView, requires the clinician to log in manually, uses the shared `shared/nims-web/nimsReportCore.js` scraper to discover report rows and report parameters, fetches reports with the active WebView cookie session, and processes supported reports on the device.

No Railway URL, helper API key, backend, cloud database, or external AI service is required for normal Android use.

## Build

```bash
cd mobile/android
./gradlew clean test lintDebug assembleDebug
```

The debug-signed APK is created under `mobile/android/app/build/outputs/apk/debug/app-debug.apk`.

## Install from GitHub Actions

1. Open GitHub → **Actions**.
2. Select the latest successful **CI** run.
3. Open **Artifacts**.
4. Download `nims-fast-summary-debug-apk`.
5. Extract the ZIP.
6. Install `app-debug.apk` on the Android device.
7. If prompted, enable Android **Install unknown apps** for the browser or file manager used to open the APK.

## Use

1. Install the debug-signed APK.
2. Confirm the app starts in **On-device only** mode and does not ask for a Railway URL or API key.
3. Log in manually in the NIMS WebView.
4. Open `CR No Wise Result Report Printing New`.
5. Tap `Diagnose Page`.
6. Tap `Discover Mapping`.
7. Tap `Test One`.
8. If one report parses successfully, run `Fast`, `Cultures`, or `Full`.

Bulk buttons are blocked until `Test One` validates the current in-memory mapping.

## Local processing support

- Text and HTML reports are parsed locally.
- Text-based PDFs are extracted locally with PdfBox-Android and then parsed through the same conservative local parsers.
- Image-only PDFs are unsupported because OCR is not included.
- Encrypted, corrupt, oversized, and excessive-page PDFs are shown as visible unsupported or failed source-report rows with controlled reasons.
- Generated summaries must be verified against source NIMS reports before clinical decisions.

## Privacy and storage

- NIMS credentials are not stored.
- NIMS cookies are used only for approved NIMS HTTPS report requests.
- Raw reports, raw HTML, raw PDF bytes, full report URLs, query strings, hidden form values, and transient report filenames are not persisted.
- Summary JSON and physician notes are encrypted locally with Android Keystore AES/GCM.
- Use **Clear NIMS Session** to clear cookies, WebStorage, cache, form data, history, mapping, and in-memory transient requests.

## URL and popup policy

Approved NIMS HTTPS URLs remain inside the WebView. Ordinary external HTTPS URLs may open in the system browser. `http`, `javascript`, `intent`, `file`, `content`, `data`, user-info URLs, alternate ports, suffix-host attacks, malformed paths, and unknown schemes are blocked without logging raw URLs.

Popup/new-window navigation is forwarded to the main WebView only when the original target URL is an approved NIMS HTTPS URL. Query parameters remain in memory for navigation but are not persisted or logged.

## Advanced legacy remote modes

Railway settings are optional legacy/advanced functionality. **Automatic with Railway fallback** tries on-device processing first and uses Railway only when explicitly configured. **Railway only** sends report content to the configured helper and requires a helper URL and API key. These modes are not required for normal Android use.

## Troubleshooting

- No rows found: confirm the report list is visible inside NIMS.
- Mapping not discovered: run `Discover Mapping` after the report list loads.
- Session expired: log in again in the WebView.
- Image-only PDF: open the source report in NIMS; OCR is not enabled.
- Parse error: verify the source report; app logs must not include raw report content.


## NIMS CR-wise navigation workflow

1. Open the app/extension and log in to NIMS manually.
2. Use **Open CR Reports**.
3. The tool opens Investigation → CR No Wise Result Report Printing New with bounded, frame-aware clicks.
4. Enter the CR number manually in the NIMS page.
5. Submit the NIMS search form manually.
6. After the report list appears, run Diagnose Page.
7. Run Discover Mapping.
8. Run Test One Report.
9. Run Fast, Cultures or Full.

Login, CAPTCHA/OTP, password entry and CR-number entry remain manual. Navigation uses exact NIMS menu IDs where available, is bounded, and stops at the CR search page or report list. If NIMS changes its frames or menu handlers, Diagnose reports the detected stage and recommended next step. Always verify source reports before clinical decisions.

Troubleshooting states include: Manual login required, Session expired, Investigation menu not found, CR-wise menu not found, CR search page ready, and Report list not yet loaded.
