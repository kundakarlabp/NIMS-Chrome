# AGENTS.md

## Scope

This repository has one canonical product: the desktop NIMS Results Dashboard connector.

Keep only the live-dashboard integration contract, Chrome bridge, REST-first NIMS report discovery, authenticated browser fallback, deterministic local report parsing, and the tests/CI required for those paths.

Do not reintroduce Android, mobile relays, Railway/remote parsing, side panels, manual-analysis products, duplicate navigation cores, Chrome Web Store publishing, scheduled retrieval, or alternate credential paths.

## Canonical ownership

- `extension/src/navigationCore.js`: single navigation/report-row source of truth.
- `extension/src/nimsSessionBridge.js`: authenticated NIMS page probing, CR submission, fallback report-list extraction.
- `extension/src/nimsRestApi.js`: HBIMS REST report-list adapter and URL/token safety policy.
- `extension/src/background.js`: orchestration, REST-first/fallback selection, local parser calls, canonical dashboard bundle.
- `extension/src/dashboardBridge.js`: dashboard ↔ extension message contract.
- `helper/`: local-only deterministic parser.
- `.github/workflows/ci.yml`: executable repository contract.

## Non-negotiable rules

- CAPTCHA/OTP remain human-entered; never OCR-solve, bypass, replay, or outsource them.
- Never store or export usernames, passwords, cookies, session tokens, hidden auth fields, query strings, transient report filenames, or full report URLs.
- Never commit real patient identifiers/reports/screenshots/logs.
- Use synthetic/de-identified fixtures only.
- A returned CR mismatch is a hard failure.
- Classify fetched content before parsing and visibly reject login/session pages, viewer shells, empty responses, and unsupported content.
- Never infer a negative/normal result from absent text.
- Raw report bytes remain transient.
- The local parser is the only parser endpoint; do not add remote helper modes.
- The dashboard bundle may contain patient identity returned by NIMS for clinician verification, but must not contain session material or transient report links.

## Change workflow

1. Read this file, `README.md`, `SECURITY.md`, and relevant tests.
2. Read the robust-repo-change and clinical-software-safety skills.
3. Reproduce with synthetic/de-identified fixtures.
4. Change the owning module only.
5. Add focused regression coverage.
6. Run complete CI on the final PR head.
7. Merge only when all checks pass.
