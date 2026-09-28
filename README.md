# NIMS Results Connector

This repository owns one product: the desktop NIMS Results Dashboard connector.

## Live dashboard

https://nims-results-cockpit-ne5nig.v2.appdeploy.ai/

## Canonical architecture

```text
Dashboard tab
   ↕ browser-local parser (PDF.js / HTML text)
Chrome extension
   ↓
authenticated NIMS session
   ├─ HBIMS reportList REST discovery (primary)
   └─ CR-wise browser report tokens (automatic fallback)
   ↓
verified NIMS report fetch
   ↓
raw report bytes returned only to the open dashboard tab
   ↓
browser-local structured parsing
   ↓
canonical result bundle
```

There is no Android app, mobile relay, localhost/Python helper, remote report parser, Railway dependency, side panel, manual-analysis product, paid extension-store publishing workflow, or duplicated navigation runtime.

## Setup

1. Open `chrome://extensions`.
2. Enable Developer mode.
3. Choose **Load unpacked**.
4. Select this repository's `extension/` directory.
5. Open the live dashboard.
6. Click **Connect NIMS** and complete NIMS login/CAPTCHA normally.
7. Enter the 15-digit CR number and click **Get results**.

The extension tries the REST report list first and automatically falls back to the authenticated CR-wise result page when required.

## Privacy and safety

- NIMS credentials, cookies and session tokens stay inside Chrome.
- CAPTCHA/OTP remain human-entered.
- Raw report bytes are held in memory only and are passed only to the open dashboard tab for local parsing.
- Raw reports are not sent to a parser backend.
- Report URLs and transient filenames are fetch-only and are not included in the final dashboard bundle.
- A returned CR mismatch is a hard failure.
- Missing or unparsable content is never treated as a normal/negative result.

## Development

```bash
npm ci
npm test
```

CI validates the connector and packages the `extension/` directory as a ZIP artifact for manual installation/update.
