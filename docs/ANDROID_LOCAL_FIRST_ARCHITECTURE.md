# Android local-first architecture

## Design objective

The Android application is a supervised clinical report extractor and presenter,
not an alternative implementation of the NIMS portal.

```text
NIMS portal
  -> passive frame observation
  -> sanitized report references
  -> authenticated on-device fetch
  -> response classification
  -> deterministic parsing
  -> native Reports / Trends / Cultures / Summary UI
```

## Ownership boundaries

### NIMS portal

NIMS owns:

- manual login, captcha, and OTP;
- menu and frame navigation;
- CR-number entry and form submission;
- session state and page rendering;
- source report generation.

The app must not patch the portal runtime or bypass its normal navigation flow.

### Passive WebView observer

`shared/nims-web/nimsPassiveObserver.js` is injected at document start into every
approved NIMS frame. It observes the existing DOM and posts structured events.
It has no authority to click, submit, navigate, replace libraries, or define NIMS
globals.

### Android session and report retrieval

The Android WebView owns the authenticated cookie session. Approved report
requests use the same NIMS host and WebView user-agent. Full URLs, query values,
cookies, hidden fields, and transient filenames remain in memory and are never
persisted or logged.

### Processing boundary

- `ReportInput` carries safe source metadata, content type, and transient bytes.
- `ProcessingRouter` selects local processing or explicit optional Railway mode.
- `OnDeviceReportProcessor` handles text/HTML and text-based PDF reports.
- `LocalTextReportProcessor` performs conservative deterministic extraction.
- `PdfBoxAndroidTextExtractor` extracts text in memory with byte/page/text limits.
- `RemoteReportProcessor` is optional advanced fallback and never receives NIMS
  credentials or cookies.

## Application workflow

1. Clinician logs in to NIMS manually.
2. Clinician navigates to the CR-wise report page using the normal NIMS menu.
3. Clinician enters and submits the CR number manually.
4. The owning frame announces visible report rows.
5. **Analyze Results** validates one report request before bulk processing.
6. Selected source reports are fetched silently and classified before parsing.
7. Parsed results appear in native Reports, Trends, Cultures, and Summary screens.
8. Clinician verifies generated values against source NIMS reports.

## Page-state contract

The observer reports only these coarse states:

- `login`
- `portal`
- `cr_search`
- `cr_results`
- `loading`
- `unknown`

State detection is based on genuine rendered elements, not the presence of jQuery
or compatibility flags. An Investigation URL alone is not proof that the CR form
or result table rendered.

## Local processing support

- `text/plain`: supported locally.
- report-like `text/html`: supported locally after response classification.
- text-based PDF: extracted and parsed locally.
- image-only PDF: explicitly unsupported; OCR is not enabled.
- login/session HTML, viewer shells, wrong endpoints, empty responses, encrypted
  PDFs, corrupt PDFs, and oversized inputs: visible controlled failures.

## Processing modes

- **On-device only**: default. No Railway URL or API key is required.
- **Automatic with Railway fallback**: local first; remote fallback only when
  explicitly configured and permitted by failure policy.
- **Railway only**: advanced legacy mode.

Login/session/captcha/OTP content is never eligible for remote fallback.

## Privacy and clinical safety

- NIMS credentials are not stored.
- Cookies remain on-device and are used only for approved NIMS requests.
- Raw reports, HTML, PDFs, extracted text, hidden values, query strings, and
  transient report references are not persisted.
- Parsed summaries and physician notes remain encrypted locally.
- Report provenance, parsing status, omissions, and errors remain visible.
- Missing text is never interpreted as a negative or normal result.
- The summary does not make autonomous diagnostic or treatment decisions.
- Source reports must be checked before clinical decisions.

## Build and assets

The Android build uses normal Kotlin/Gradle source as the source of truth. There
is no Python source mutation, generated jQuery asset, or pre-build patching.
`shared/nims-web` remains the canonical directory for pure browser/WebView code,
and Gradle packages those files directly as assets.

Required Android runtime assets:

- `nimsReportCore.js`
- `contentUtils.js`
- `nimsPassiveObserver.js`

The former jQuery bootstrap and NIMS compatibility shim are not part of the
Android runtime.

## Validation

```bash
pip install -r helper/requirements-dev.txt
python -m pytest -q
python -m py_compile helper/main.py helper/models.py helper/cache.py
npm ci
npm test
python scripts/sync_navigation_core.py --check
cd mobile/android
./gradlew clean test lintDebug assembleDebug
```

Configured Android instrumented tests are also required. CI verifies that the
passive observer is packaged and bundled jQuery is absent.

## Residual risk

The NIMS portal is a legacy framed application and may change its markup or
report request contract. The observer therefore fails closed when a genuine CR
form, report row, or safe report reference is not found. Navigation remains
manual, and the source NIMS report remains authoritative.
