# Android WebView App

## Architecture

Android version 0.10.0 uses a browser-first WebView design:

```text
Unmodified NIMS portal during login and navigation
  -> clinician opens the CR result list manually
  -> one read-only extraction call after Analyze is tapped
  -> approved authenticated report fetches
  -> on-device HTML and PDF parsing
  -> native Reports, Trends, Cultures, and Summary UI
```

NIMS remains responsible for authentication, captcha or OTP, menu and frame navigation, CR-number entry, form submission, and source-report rendering.

During login and ordinary portal navigation the app installs no document-start JavaScript, persistent bridge, polling timer, DOM observer, jQuery replacement, compatibility shim, or navigation automation. The only Android JavaScript asset is `src/main/assets/nimsOnDemandExtractor.js`, and it executes once only when the clinician taps **Analyze**.

## Normal workflow

1. Install and open the APK.
2. Log in to NIMS manually.
3. Navigate through the normal NIMS menu to **Investigation -> CR No Wise Result Report Printing New**.
4. Enter and submit the CR number in NIMS.
5. Keep the result table containing visible **View Report** actions on screen.
6. Tap **Analyze**.
7. Review the native Reports, Trends, Cultures, and Summary tabs.
8. Verify generated values against the source NIMS reports before clinical use.

The app does not automate login, captcha, OTP, CR entry, form submission, or menu navigation.

## On-demand extractor contract

`nimsOnDemandExtractor.js` may only inspect approved NIMS documents and reachable same-origin frames, classify the visible page, identify genuine report controls, extract sanitized metadata and validated transient PDF references, verify the live report-request contract, and return one JSON value to Android.

It must never run automatically at page start, install observers or polling, replace libraries, define NIMS globals, click controls, submit forms, enter a CR number, change navigation, or persist sensitive session and report data.

The legacy NIMS shell may use page-owned script links for menu actions. `NimsWebViewClient` permits those only when they originate from an approved NIMS HTTPS document. External and unsafe navigation remains blocked.

## Report processing

- The result list provides report references, not necessarily all clinical values.
- Approved reports are fetched with the authenticated WebView cookie session, current Chromium-derived desktop user-agent, and NIMS referrer.
- Responses are classified before parsing.
- Text, HTML, and text-based PDFs are processed locally.
- Image-only and encrypted PDFs fail visibly; OCR is not enabled.
- Report fetches are limited in size and concurrency.
- Raw report bytes, HTML, extracted text, cookies, full URLs, query strings, and transient filenames are not persisted.

## User interface

The NIMS tab uses a compact toolbar so the WebView receives most of the screen: Back, Reload, Analyze, and More. The More menu contains Login, Cultures-only analysis, Full analysis, and Clear session. Runtime logs and diagnostic controls are absent from the routine clinical interface.

## Build

```bash
cd mobile/android
./gradlew clean test lintDebug assembleDebug
```

The debug APK is generated at `mobile/android/app/build/outputs/apk/debug/app-debug.apk`.

The build has no source-mutation step and does not package the former jQuery, compatibility-shim, passive-observer, or Android frame-bridge assets.

## CI validation

CI runs Python tests, JavaScript tests, Android JVM tests, lint, APK assembly, Android instrumented PDF tests, shared-core synchronization checks, and the helper Docker build. It also verifies that `nimsOnDemandExtractor.js` is packaged.

CI cannot reproduce authenticated live NIMS behaviour. A final supervised device test is mandatory after every portal or WebView change.

## Troubleshooting

- **Portal blank or slow before Analyze:** no app extraction script has run yet. Reload once, then update Android System WebView or Chrome and retry. Use Clear session only when a stale session is likely.
- **No report rows found:** keep the submitted CR result list visible and retry.
- **Report request cannot be verified:** open one source report normally in NIMS, return to the result list, and retry.
- **Session expired:** log in again and reopen the result list.
- **Image-only PDF:** open the source report in NIMS; OCR is intentionally absent.

## Privacy and clinical safety

NIMS credentials are not stored. Cookies remain on-device and are used only for approved NIMS requests. Every parsed report retains provenance and explicit failure status. Generated summaries are supervised decision support and must be checked against the source NIMS reports before clinical decisions.
