# Security Notes

NIMS Fast Summary is designed for local personal clinical workflow.

- The Chrome extension uses only the already logged-in Chrome session.
- It does not implement automatic login.
- It does not store usernames, passwords, OTPs, captcha answers, or session tokens.
- It must not be used to bypass hospital authentication, captcha, OTP, session expiry, or access controls.
- The local helper runs on `http://127.0.0.1:8765` and stores parsed JSON cache only.
- Raw PDFs are parsed in memory and are not permanently stored.
- Direct report mapping is stored only in service-worker memory and `chrome.storage.session` when available; the displayed mapping summary contains method and host/path only.
- A mapping discovered from one click is only a candidate until `Test Direct Fetch` successfully fetches and parses one report in the current session.
- Raw `onclick`, raw `printReport(...)` arguments, cookies, tokens, session IDs, query strings, hidden input values, CR number, and patient identifiers are not stored or exported.
- If hidden form values are needed for direct fetch, their field names may be remembered, but current values are read transiently from the live page for that request only.
- Cache keys do not trust generated row indexes such as `row-1` or `row-2`; PDF/report bytes are preferred, then real report identifiers combined with date and report name, then URL/date/name metadata only when bytes are unavailable.
- Direct bulk cache lookup uses a non-reversible SHA-256 `report_key:` derived from the transient report argument plus safe row metadata. The raw argument is not stored.
- Chrome storage and JSON export are sanitized by default and exclude raw row text, full report URLs, onclick code, and raw text previews.
- The side-panel `Diagnose Page` feature reports only sanitized iframe host/path, row counts, API availability, and minimal row previews; it strips query strings and does not expose raw row text, onclick code, cookies, tokens, or credentials.
- Helper API calls are routed through the extension background service worker. Content scripts in NIMS frames do not directly call `127.0.0.1`.
- Safe mapping diagnostics include onclick function names, argument counts, parse status, global form presence, form method, unsupported POST-only status, and nearby input names only; raw onclick code and hidden input values are excluded.
- Direct fetch diagnostics include only safe metadata such as host/path, method, response status, content-type, classification, parameter names, POST field names, parse counts, and parse errors. They do not include raw URLs, query strings, hidden values, raw HTML, raw PDFs, or raw report text.
- `Bulk Fast Summary` and `Bulk Full Summary` do not silently use popup fallback. The slow popup/open-close path is available only through `Manual Popup Fallback`.
- Debug mode is off by default. If enabled for live-site mapping, the UI warns: `Debug mode may contain PHI. Do not export/share.`
- Login/session-expired HTML is treated as a failed fetch and is not parsed as a clinical report.
- Do not commit real patient data, credentials, screenshots, exported reports, or raw PDFs.
- Do not commit `cache.db`, `.env`, logs, API keys, or real report samples.
- Do not upload PHI to external services.
- Optional AI interpretation, if enabled later with `OPENAI_API_KEY`, must receive only de-identified structured JSON.

To clear local parsed cache, use the extension `Clear cache` button or delete `helper/cache.db`.

