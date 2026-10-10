# NIMS Results Connector

This repository owns one desktop workflow only: the NIMS Results Dashboard connector.

## Live dashboard

https://nims-results-cockpit-ne5nig.v2.appdeploy.ai/

## Canonical flow

```text
Dashboard
   ↓
Chrome connector
   ↓
manual clinician NIMS login / CAPTCHA
   ↓
authenticated NIMS session
   ├─ REST reportList discovery first
   │    └─ verified NIMS report fetch + parser
   └─ existing CR-wise browser bridge fallback
        └─ proven direct-report processor + parser
   ↓
canonical structured results
   ↓
Dashboard
```

The REST endpoint is an optimization layered on top of the existing working bridge. It does not replace authentication and it does not bypass CAPTCHA.

## What is intentionally not in this repository

- Android/mobile app
- mobile or ChatGPT relay
- side-panel product
- manual-analysis UI
- Chrome Web Store publishing workflow
- duplicated navigation engine

## Runtime components

- `extension/src/dashboardBridge.js`: dashboard ↔ extension contract.
- `extension/src/nimsSessionBridge.js`: authenticated NIMS session, CR navigation and dashboard bridge.
- `extension/src/nimsRestApi.js`: HBIMS reportList adapter and safe NIMS report URL/token policy.
- `extension/src/navigationCore.js`: NIMS navigation/report-row logic.
- `extension/src/contentUtils.js` + `contentScript.js`: the proven report processor used only by the fallback path; its old toolbar UI is disabled.
- `helper/`: deterministic report parser used by the proven retrieval path.

## Routine use

1. Keep the validated connector loaded in Chrome.
2. Open the dashboard.
3. Click **Connect NIMS**.
4. Complete NIMS login and CAPTCHA manually.
5. Return to the dashboard; it detects the authenticated session.
6. Enter the 15-digit CR and click **Get results**.
7. REST is attempted first. If it cannot supply usable reports, the dashboard automatically uses the authenticated browser bridge.

## Development validation

```bash
npm ci
npm test
pip install -r helper/requirements-dev.txt
PYTHONPATH=helper python -m pytest -q tests/test_parsers.py
```
