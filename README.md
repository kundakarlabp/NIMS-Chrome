# NIMS Results Connector

This repository is intentionally limited to one product: the desktop NIMS Results Dashboard connector.

## Live dashboard

https://nims-results-cockpit-ne5nig.v2.appdeploy.ai/

## Canonical architecture

```text
Dashboard
   ↓
Chrome extension
   ↓
authenticated NIMS session
   ├─ REST reportList (primary)
   └─ CR-wise browser extraction (automatic fallback)
   ↓
local parser on 127.0.0.1:8765
   ↓
canonical result bundle
   ↓
Dashboard
```

There is no Android app, mobile relay, remote parser, side panel, manual-analysis product, paid extension-store publishing workflow, or duplicated shared navigation runtime.

## Setup

### Local parser

```bash
cd helper
python -m venv .venv
# Windows: .venv\Scripts\activate
# macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --host 127.0.0.1 --port 8765
```

### Chrome extension

1. Open `chrome://extensions`.
2. Enable Developer mode.
3. Choose **Load unpacked**.
4. Select `extension/`.
5. Reload the extension only after installing a new code version; normal use needs no reload.

### Routine use

1. Open the live dashboard.
2. Connect NIMS if the session is not active.
3. Complete login and CAPTCHA manually.
4. Enter the 15-digit CR.
5. Click **Get results**.

The extension tries REST first and uses the browser fallback automatically when required.

## Development

```bash
npm ci
npm test
pip install -r helper/requirements-dev.txt
PYTHONPATH=helper python -m pytest -q
```

CI packages a validated extension ZIP artifact for manual installation/update. It does not publish to an extension store.
