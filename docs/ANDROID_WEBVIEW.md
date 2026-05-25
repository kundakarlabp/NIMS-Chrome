# Android WebView App

The Android app lets the phone replace the laptop-hosted helper workflow. It loads NIMS in a WebView, the user logs in manually, the app fetches report content using the active WebView cookies, and it sends report bytes/text to the configured Railway helper for parsing and summarization.

The app does not automate login, store NIMS credentials, bypass captcha/OTP/session expiry, or send NIMS cookies to Railway.

## Build

```bash
cd mobile/android
./gradlew test
./gradlew assembleDebug
```

The debug APK is created under `mobile/android/app/build/outputs/apk/debug/`.

## Use

1. Install the debug APK.
2. Enter the Railway helper URL and API key.
3. Tap `Save Helper`.
4. Tap `Test Helper` and confirm health/version details.
5. Log in manually in the NIMS WebView.
6. Open `CR No Wise Result Report Printing New`.
7. Tap `Diagnose Page`.
8. Tap `Discover Mapping`.
9. Tap `Test Direct Fetch`.
10. If one report parses successfully, tap `Bulk Fast Summary`, `Bulk Cultures Only`, or `Bulk Full Summary`.

Bulk buttons are blocked until `Test Direct Fetch` validates the mapping.

## Troubleshooting

- No rows found: confirm the report list is visible inside NIMS.
- Mapping not discovered: run `Discover Mapping` after the report list loads.
- Session expired: log in again in the WebView.
- Helper `401`: check the Railway helper API key.
- Large report: increase `NIMS_HELPER_MAX_BODY_MB` only if needed and safe.
- Parse error: verify the source report and helper logs; logs should not include raw report content.

## Data Handling

Raw report PDF/HTML/text is processed transiently. The app sends report content to Railway helper for parsing. NIMS cookies stay on the phone and are used only for NIMS report fetches.
