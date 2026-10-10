# Security

This is a clinician-operated connector for authenticated NIMS investigation results.

- CAPTCHA/OTP remain human-entered.
- The extension reuses the clinician's authenticated NIMS browser session.
- It does not collect or export usernames, passwords, CAPTCHA answers, cookies or session tokens.
- REST access is attempted only after the authenticated-session gate.
- A returned CR mismatch is a hard failure and must never fall through to another patient's data.
- Only approved NIMS hosts and report paths may be fetched.
- Transient report filenames/URLs are used for retrieval and are not retained in the dashboard result bundle.
- The deterministic parser may run locally or through an explicitly configured helper used by the proven pre-cleanup workflow.
- Never commit real patient CR numbers, identifiers, reports, screenshots, cookies, tokens or credentials.
- Missing or unparsable content must never be represented as a normal/negative result.
- Clinically important values must remain source-verifiable.
