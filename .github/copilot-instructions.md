# GitHub Copilot instructions — NIMS Fast Summary

Read `AGENTS.md` before proposing or applying changes.

## Required workflow

- Use `.agents/skills/robust-repo-change/SKILL.md` for debugging, implementation, refactors, CI failures, deployment, and PR work.
- Use `.agents/skills/clinical-software-safety/SKILL.md` for every change involving reports, parsers, browser/WebView sessions, URLs, cookies, hidden fields, diagnostics, caching, local storage, Railway, summaries, or exports.
- Use `.agents/skills/session-worklog/SKILL.md` after substantial work so later chats recover decisions and validation without copying sensitive data.
- Modify the owning component and preserve `shared/nims-web/nimsReportCore.js` as the canonical navigation/report-fetch core.
- Add regression tests and run the exact commands in `AGENTS.md` and `.github/workflows/ci.yml` before claiming completion.

## Hard constraints

- Do not automate NIMS login, captcha, OTP, CR-number entry, or normal menu navigation.
- Do not store or expose credentials, cookies, tokens, query strings, hidden values, transient filenames, raw report URLs, raw HTML/PDF/text, patient identifiers, or identifiable screenshots.
- Keep Android on-device-first and Railway optional; session credentials must never be sent to Railway.
- Keep OCR and external AI interpretation disabled unless explicitly approved and comprehensively validated.
- Classify unsupported/session-expired/wrong-endpoint content visibly; never parse it as a report.
- Preserve clinician verification against source reports.

Clinical privacy, source fidelity, and repository-local rules take precedence over coding convenience.
