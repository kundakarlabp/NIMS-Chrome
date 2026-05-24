# Security Notes

NIMS Fast Summary is designed for local personal clinical workflow.

- The Chrome extension uses only the already logged-in Chrome session.
- It does not implement automatic login.
- It does not store usernames, passwords, OTPs, captcha answers, or session tokens.
- It must not be used to bypass hospital authentication, captcha, OTP, session expiry, or access controls.
- The local helper runs on `http://127.0.0.1:8765` and stores parsed JSON cache only.
- Raw PDFs are parsed in memory and are not permanently stored.
- Cache keys do not trust generated row indexes such as `row-1` or `row-2`; PDF/report bytes are preferred, then real report identifiers combined with date and report name, then URL/date/name metadata only when bytes are unavailable.
- Chrome storage and JSON export are sanitized by default and exclude raw row text, full report URLs, onclick code, and raw text previews.
- Debug mode is off by default. If enabled for live-site mapping, the UI warns: `Debug mode may contain PHI. Do not export/share.`
- Login/session-expired HTML is treated as a failed fetch and is not parsed as a clinical report.
- Do not commit real patient data, credentials, screenshots, exported reports, or raw PDFs.
- Do not commit `cache.db`, `.env`, logs, API keys, or real report samples.
- Do not upload PHI to external services.
- Optional AI interpretation, if enabled later with `OPENAI_API_KEY`, must receive only de-identified structured JSON.

To clear local parsed cache, use the extension `Clear cache` button or delete `helper/cache.db`.

