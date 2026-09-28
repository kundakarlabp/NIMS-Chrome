# Security

This is a clinician-operated desktop connector for NIMS investigation results.

- The extension reuses only the clinician's authenticated Chrome/NIMS session.
- It does not store NIMS usernames, passwords, OTPs, CAPTCHA answers, cookies, or session tokens.
- CAPTCHA and OTP remain human-entered.
- CR identity mismatch fails closed.
- NIMS report URLs and transient filenames are fetch-only and are not returned in the dashboard bundle.
- Raw PDF/HTML/report bytes are transient and are not persisted.
- Parsing is local-only on `127.0.0.1:8765`; there is no remote parser mode.
- No identifiable production reports, screenshots, logs, CR numbers, or credentials may be committed.
- Missing/unparsable content must never be represented as normal or negative.
- Clinicians must verify clinically important values against the NIMS source.
