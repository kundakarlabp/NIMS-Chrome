# NIMS Results Dashboard

Live dashboard: https://nims-results-cockpit-ne5nig.v2.appdeploy.ai/

The dashboard is deployed in AppDeploy. This repository owns the desktop connector contract that serves it.

Canonical flow: dashboard → Chrome connector → authenticated NIMS session → REST reportList first → authenticated browser fallback → local parser → canonical result bundle.

The dashboard does not receive NIMS cookies, passwords, CAPTCHA values, session tokens, transient report filenames, or full report URLs.
