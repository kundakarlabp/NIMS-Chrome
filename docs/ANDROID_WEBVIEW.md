# Android WebView App

## Architecture

The Android app separates the NIMS portal from the native clinical-results UI:

```text
NIMS website -> passive all-frame observer -> safe report references ->
on-device fetch/parse -> Reports / Trends / Cultures / Summary
```

NIMS remains responsible for authentication, menu navigation, CR-number entry,
form submission, and page rendering. The app does not replace jQuery, patch NIMS
globals, call undocumented tab functions, or construct internal navigation URLs.

The document-start runtime contains only the canonical report core, safe report
utilities, and `nimsPassiveObserver.js`. Document-start timing ensures the
observer is present in every approved frame, but the observer does not modify the
website.

## Normal workflow

1. Install the debug-signed APK.
2. Open the **NIMS** tab.
3. Log in manually, including captcha/OTP where applicable.
4. Navigate in the normal NIMS menu to:
   **Investigation -> CR No Wise Result Report Printing New**.
5. Enter the CR number and submit it in NIMS.
6. Keep the result table with visible **View Report** rows on screen.
7. Tap **Analyze Results**.
8. Review the native **Reports**, **Trends**, **Cultures**, and **Summary** tabs.
9. Verify generated values against the source NIMS reports before clinical use.

Login, captcha, OTP, CR-number entry, and report-page navigation remain manual.

## Passive observer contract

`shared/nims-web/nimsPassiveObserver.js` runs inside every approved NIMS frame and
may only:

- classify the frame as login, portal, CR search, CR results, or loading;
- observe AJAX/DOM changes;
- detect the genuine CR form and visible report rows;
- extract sanitized report metadata and an in-memory report reference;
- post structured JSON to the Android message bridge.

It must never:

- assign or replace `window.jQuery` / `$`;
- define `date_time` or other NIMS globals;
- wrap `ajaxCompleteTab`, `addTab`, or portal functions;
- click menus, submit forms, enter a CR number, or automate login;
- log or persist cookies, credentials, query values, raw onclick text, or report
  content.

## Report processing

- The result-list page is used to discover source-report references; it is not
  assumed to contain every clinical value.
- Source reports are fetched silently with the authenticated WebView cookie
  session and the same user-agent.
- Text/HTML reports and text-based PDFs are processed locally.
- Image-only PDFs are unsupported because OCR is not enabled.
- Responses are classified before parsing so login/session pages, viewer shells,
  empty responses, wrong endpoints, and unsupported formats fail visibly.
- Raw reports, raw HTML, PDF bytes, cookies, full URLs, query strings, hidden
  values, and transient filenames are not persisted.

## Build

```bash
cd mobile/android
./gradlew clean test lintDebug assembleDebug
```

The debug APK is generated at:

```text
mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

The Android build has no generated jQuery asset and no source-mutation step.
`shared/nims-web` is the canonical source for the pure WebView scripts packaged
as Android assets.

## CI validation

CI runs:

```bash
python -m pytest -q
npm test
python scripts/sync_navigation_core.py --check
cd mobile/android && ./gradlew clean test lintDebug assembleDebug
```

It also runs the configured Android instrumented tests, verifies that
`nimsPassiveObserver.js` is packaged, and fails if bundled jQuery is present.

## Troubleshooting

- **Blank or incomplete NIMS page:** reload the page. The app does not patch the
  portal; capture sanitized WebView console/network diagnostics if NIMS itself
  fails to render.
- **CR form not detected:** confirm the correct NIMS menu page is visibly open.
- **No report rows:** submit the CR form and wait for visible **View Report** rows.
- **Analyze Results unavailable or no usable rows:** keep the result table visible
  and retry. Do not navigate directly to an internal endpoint.
- **Session expired:** log in again manually.
- **Image-only PDF:** open the source report in NIMS; OCR is intentionally absent.

## Privacy and clinical safety

NIMS credentials are not stored. Session cookies remain on-device and are never
sent to Railway. Railway processing remains optional advanced fallback only.
Every parsed report retains source provenance and explicit errors. The generated
summary is supervised decision support and must be verified against source NIMS
reports before clinical decisions.
