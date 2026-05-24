# Security Notes

NIMS Fast Summary is designed for local personal clinical workflow.

- The Chrome extension uses only the already logged-in Chrome session.
- It does not implement automatic login.
- It does not store usernames, passwords, OTPs, captcha answers, or session tokens.
- It must not be used to bypass hospital authentication, captcha, OTP, session expiry, or access controls.
- The local helper runs on `http://127.0.0.1:8765` and stores parsed JSON cache only.
- Raw PDFs are parsed in memory and are not permanently stored.
- Do not commit real patient data, credentials, screenshots, exported reports, or raw PDFs.
- Do not upload PHI to external services.
- Optional AI interpretation, if enabled later with `OPENAI_API_KEY`, must receive only de-identified structured JSON.

To clear local parsed cache, use the extension `Clear cache` button or delete `helper/cache.db`.

