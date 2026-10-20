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
   ├─ NIMS GETMETABOLICDATA Custom Sheet structured API first
   │    └─ authenticated source-report inventory when available
   ├─ verified REST reportList fetch + parser if bulk results fail
   └─ existing CR-wise browser bridge fallback
        └─ proven direct-report processor + parser
   ↓
canonical structured results
   ↓
Dashboard
```

The authenticated structured Custom Sheet endpoint is the primary values path. Verified REST reportList metadata enriches source links when available; if structured retrieval fails, REST parsing and then the existing CR-wise browser bridge provide bounded fallbacks. None of these bypass clinician authentication or CAPTCHA.

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
4. Complete NIMS login and CAPTCHA manually. If a CR request started while logged out, the same CR resumes automatically after login.
5. Return to the dashboard; it detects the authenticated session.
6. Enter the 15-digit CR and click **Get results**.
7. Structured Custom Sheet values are attempted first. Verified reportList metadata enriches source links; if values fail, verified REST parsing and then the authenticated browser bridge are fallback routes.
8. The dashboard's connection help can read masked parser-helper status and save the Railway helper URL/API key directly into extension-local storage; the full key is never read back into the page.\n9. The separate clinical-record service can receive a verified CR bundle through its browser handoff once configured. This connector does not automatically export identifiable NIMS records into ChatGPT.

## Development validation

```bash
npm ci
npm test
pip install -r helper/requirements-dev.txt
PYTHONPATH=helper python -m pytest -q tests/test_parsers.py
```
