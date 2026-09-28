# NIMS Results Dashboard

Live dashboard: https://nims-results-cockpit-ne5nig.v2.appdeploy.ai/

The deployed dashboard keeps the established manual-login Chrome bridge contract.

Retrieval order:

1. Confirm the clinician-authenticated NIMS browser session.
2. Attempt the HBIMS `reportList` REST endpoint.
3. Use verified report URLs/tokens when the REST response exposes them.
4. If the REST path is unavailable or not usable, automatically invoke the existing CR-wise browser bridge.
5. Parse reports with the deterministic parser and return the canonical bundle.

The REST adapter is primary discovery; the existing browser bridge remains the fallback. No Android pairing is required.
