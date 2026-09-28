# Security

This is a clinician-operated desktop connector for NIMS investigation results.

## Authentication

- The extension reuses only the clinician's authenticated Chrome/NIMS session.
- It does not store NIMS usernames, passwords, OTPs, CAPTCHA answers, cookies, or session tokens.
- CAPTCHA and OTP remain human-entered.
- CR identity mismatch fails closed.

## Report-data boundary

- NIMS report URLs and transient filenames are fetch-only and never enter the final dashboard bundle.
- Raw PDF/HTML/report bytes are held in memory only.
- Raw report bytes move only from the extension to the already-open NIMS Results Dashboard tab for browser-local parsing.
- Report parsing uses bundled browser code; there is no localhost helper or remote parser service.
- No identifiable production reports, screenshots, logs, CR numbers, credentials, report URLs, or transient filenames may be committed.

## Clinical safety

- Missing or unparsable data must never be represented as normal or negative.
- Source dates, units, reference ranges, organism and susceptibility details must be preserved when present.
- Clinicians must verify clinically important values against the NIMS source.
