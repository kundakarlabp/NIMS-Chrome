# Android 0.8.2 live acceptance gate

A CI-green APK is a candidate build until it passes the authenticated NIMS device workflow.

Required sequence:

1. Manual NIMS login completes.
2. Investigation → Cr No Wise Result Report Printing New renders the CR form.
3. The CR form accepts a manually entered CR number and renders the report list.
4. Runtime diagnostics show `dateTime=true`, `jquery=true`, and `offset=true` for the relevant NIMS frames.
5. The all-frame bridge detects visible `printReport(...)` rows.
6. Test One fetches one authenticated PDF and extracts text locally.
7. Bulk analysis runs only after the one-report validation succeeds.

The app must not persist credentials, session cookies, SSO tickets, report tokens, or PDF bytes.
