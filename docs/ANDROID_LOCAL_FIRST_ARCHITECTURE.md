# Android local-first architecture

## Design objective

The Android application is a supervised clinical report extractor and presenter, not an alternative implementation of the NIMS portal.

```text
NIMS portal, untouched during login and navigation
  -> one on-demand read-only extraction
  -> sanitized report references
  -> authenticated on-device fetch
  -> response classification
  -> deterministic local parsing
  -> native Reports, Trends, Cultures, and Summary UI
```

## Ownership boundaries

### NIMS portal

NIMS owns manual login, captcha or OTP, menu and frame navigation, CR-number entry, form submission, session state, page rendering, and source-report generation. The app does not patch the portal runtime or bypass its normal navigation flow.

### On-demand WebView extraction

`src/main/assets/nimsOnDemandExtractor.js` is not installed at document start. It runs once only after the clinician taps Analyze and returns a structured result from the currently rendered approved NIMS page and reachable same-origin frames.

The extractor does not poll, observe the DOM continuously, click controls, submit forms, replace libraries, define NIMS globals, or persist report references.

### Android session and retrieval

The WebView owns the authenticated cookie session. Approved report requests use the same NIMS hosts, a desktop user-agent derived from the installed Chromium version, and a NIMS referrer. Full URLs, query values, cookies, hidden fields, and transient filenames remain in memory and are not logged or persisted.

### Processing boundary

- `ReportInput` carries safe metadata, content type, and transient bytes.
- `OnDeviceReportProcessor` handles text, HTML, and text-based PDF reports.
- `LocalTextReportProcessor` performs conservative deterministic extraction.
- `PdfBoxAndroidTextExtractor` extracts PDF text in memory with byte, page, and text limits.
- Source provenance, warnings, and failures are retained in the native summary model.

## Application workflow

1. The clinician logs in to NIMS manually.
2. The clinician navigates through the normal NIMS menu to the CR-wise report page.
3. The clinician enters and submits the CR number manually.
4. The result list remains visible.
5. Analyze runs one read-only extraction and validates the live report-request template.
6. One report is fetched and parsed first as a validation gate.
7. Remaining selected reports are fetched with concurrency limited to two.
8. Parsed results appear in Reports, Trends, Cultures, and Summary.
9. The clinician verifies generated values against source NIMS reports.

## Analysis modes

- **Fast:** cultures, inflammatory markers, and bounded recent CBC and chemistry groups.
- **Cultures:** culture and susceptibility reports only.
- **Full:** every usable report row detected on the visible result page.

## Supported content

- plain text and report-like HTML;
- text-based PDFs;
- explicit controlled failures for login/session HTML, empty responses, wrong endpoints, corrupt or encrypted PDFs, image-only PDFs, and oversized inputs.

OCR is intentionally not enabled.

## Privacy and clinical safety

- NIMS credentials are not stored.
- Cookies remain on-device and are used only for approved NIMS requests.
- Raw reports, extracted text, full report URLs, query strings, and transient filenames are not persisted.
- Parsed summaries and physician notes remain encrypted locally through the existing secure settings layer.
- Missing or unparsed text is never interpreted as a normal or negative finding.
- The generated summary does not make autonomous diagnostic or treatment decisions.
- Source reports remain authoritative.

## Build and validation

```bash
python -m pytest -q
npm ci
npm test
python scripts/sync_navigation_core.py --check
cd mobile/android
./gradlew clean test lintDebug assembleDebug
```

CI also runs Android instrumented PDF tests and builds the helper Docker image. Live authenticated NIMS behaviour requires supervised testing on the target phone because it cannot be reproduced in CI.

## Residual risk

NIMS is a legacy framed application and its markup or report-request contract may change. Extraction therefore fails closed when the current page does not contain safe report controls or a verified live request template.
