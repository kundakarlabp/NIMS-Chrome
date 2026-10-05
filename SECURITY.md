# Security

- Manual NIMS login and CAPTCHA remain under clinician control.
- The extension reuses the authenticated NIMS browser session; it does not store credentials or CAPTCHA values.
- Bulk Custom Sheet JSON is held only in memory for the active dashboard request.
- A confirmed CR mismatch fails closed.
- No raw patient data, cookies, tokens or reports may be committed to this repository.
- The dashboard receives only the normalized bundle needed for display.
- `reportList` metadata is supplementary and cannot override a patient identity mismatch.
