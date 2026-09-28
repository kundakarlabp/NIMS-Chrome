# AGENTS.md

## Scope

This repository has one canonical product: the desktop NIMS Results Dashboard connector.

Keep only:
- dashboard integration contract
- Chrome bridge
- REST-first NIMS report discovery
- authenticated browser fallback
- verified NIMS report fetching
- browser-local dashboard parsing protocol
- focused tests and packaging CI

Do not reintroduce Android, mobile relays, localhost/Python helpers, Railway/remote report parsing, side panels, manual-analysis products, duplicate navigation cores, Chrome Web Store publishing, scheduled retrieval, or alternate credential paths.

## Canonical ownership

- `extension/src/navigationCore.js`: NIMS navigation and report-row extraction.
- `extension/src/nimsSessionBridge.js`: authenticated NIMS probing, CR submission, browser-fallback report tokens.
- `extension/src/nimsRestApi.js`: HBIMS report-list adapter and NIMS URL/token safety policy.
- `extension/src/background.js`: REST-first/fallback orchestration, verified report fetch, canonical bundle construction.
- `extension/src/dashboardBridge.js`: dashboard ↔ extension contract, including local report-parse request/response.
- deployed dashboard: browser-local PDF/HTML parsing and clinical display.
- `.github/workflows/ci.yml`: executable repository contract.

## Non-negotiable rules

- CAPTCHA/OTP remain human-entered.
- Never store or export usernames, passwords, cookies, session tokens, hidden auth fields, query strings, transient report filenames, or full report URLs.
- Never commit real patient identifiers, reports, screenshots or logs.
- A returned CR mismatch is a hard failure.
- Classify fetched content before parsing.
- Reject login/session pages, viewer shells, empty responses and unsupported content.
- Never infer a negative/normal result from absent text.
- Raw report bytes remain transient and may be passed only to the open dashboard tab for local parsing.
- No parser backend may receive raw NIMS reports.
- The final dashboard bundle may contain source patient identity for clinician verification, but no session material or transient report links.

## Required validation

```bash
npm ci
npm test
```

Merge only when the full PR workflow is green.
