# NIMS Results Connector

This repository owns one desktop workflow only.

## Canonical retrieval

```text
Dashboard CR
   ↓
Authenticated NIMS browser session
   ↓
Cr No Wise Investigation Trends
   ↓
Custom Sheet
   ↓
GETMETABOLICDATA JSON — PRIMARY structured-results source
   ↓
Normalize in memory
   ↓
Dashboard
```

If there is no authenticated NIMS session, the connector opens the normal NIMS login page. Username, password and CAPTCHA remain manual. After successful login the dashboard can resume the pending CR.

The HBIMS `reportList` REST endpoint is secondary metadata only. Failure of `reportList` does **not** switch to the NIMS Results List and does **not** block bulk structured results.

## Explicitly excluded

- CR-wise Results List scraping for routine retrieval
- View Report button discovery/mapping
- PDF/OCR parsing for structured laboratory values
- helper server / Railway parser
- Android/APK/relay
- side panel/manual-analysis UI
- CAPTCHA automation

This restores the earlier validated dashboard bridge design: Custom Sheet bulk JSON drives matrices and trends; report metadata is supplementary.

Operational rule: structured values must never depend on opening the NIMS CR-wise Results List or locating a View Report button.

The GitHub Actions artifact is packaged as a directly loadable unpacked extension: `manifest.json` and `src/` are at the artifact root.
