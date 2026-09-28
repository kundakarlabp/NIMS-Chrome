# NIMS Results Dashboard

Live dashboard: https://nims-results-cockpit-ne5nig.v2.appdeploy.ai/

The dashboard is the browser-local parser and display surface for the desktop connector.

Canonical flow:

1. Dashboard requests a 15-digit CR through the Chrome bridge.
2. The extension confirms an authenticated NIMS session.
3. REST `reportList` discovery is tried first.
4. If needed, the extension uses the authenticated CR-wise result page to obtain safe report tokens.
5. The extension fetches the verified NIMS report.
6. The raw report bytes are passed in memory to this dashboard tab.
7. Bundled PDF.js / HTML parsing runs locally in the browser.
8. Only structured result data is returned to the extension and displayed.

No localhost parser, remote report parser, Android pairing, or paid extension-store publication is required.
