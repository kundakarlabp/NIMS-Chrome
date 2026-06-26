# AGENTS.md

## Project identity

NIMS Fast Summary is a privacy-sensitive clinical report retrieval and summarization system composed of:

- a Chrome Manifest V3 extension
- a local or Railway-hosted FastAPI helper
- a local-first Android WebView application
- shared browser/WebView navigation and report-fetch logic
- deterministic parsers and physician-facing summaries

The primary objective is **safe, source-verifiable access to NIMS reports after manual clinician login**. The system must not automate authentication, expose session material, silently misclassify reports, or present incomplete parsing as reliable clinical data.

## Read first

Before editing, inspect:

1. this file
2. `README.md`
3. `SECURITY.md` when present
4. the owning implementation and its tests
5. `.agents/skills/robust-repo-change/SKILL.md`
6. `.agents/skills/clinical-software-safety/SKILL.md`
7. `.github/workflows/ci.yml`

Repository code, tests, security constraints, and deployment documentation remain authoritative. Shared skills supplement local rules; they do not override them.

## Non-negotiable clinical and authentication rules

- Login, captcha, OTP, CR-number entry, and report-page navigation remain manual unless an approved NIMS interface explicitly supports automation.
- Never store or transmit NIMS usernames, passwords, OTPs, cookies, session tokens, hidden form values, full report URLs, query strings, transient report filenames, raw `onclick` values, or raw `printReport` arguments.
- Never commit real patient reports, identifiers, screenshots, PDFs, HTML, logs, cache files, API keys, or production diagnostics.
- Use synthetic or explicitly de-identified fixtures only.
- Android remains on-device-first. Railway is optional fallback and must never receive browser/WebView session credentials.
- Raw reports, raw HTML, and raw PDF bytes must not be persisted. Preserve the existing parsed-summary/cache boundaries.
- External AI interpretation remains disabled unless an explicitly reviewed, privacy-preserving feature is approved. Do not convert rule-based extraction into autonomous diagnosis or treatment advice.
- OCR remains disabled unless deliberately implemented, validated, and documented. Image-only PDFs must fail visibly as unsupported.
- Every generated value or summary must remain traceable to its source report and parsing outcome.
- Clinicians must verify summaries against source reports before clinical decisions.

## Architectural ownership

- `shared/nims-web/nimsReportCore.js` is the canonical shared navigation/report-fetch core. Do not fork equivalent logic into extension and Android implementations.
- `extension/` owns Chrome UI, side-panel controls, browser-session fetching, sanitized diagnostics, and background/helper communication.
- `helper/` owns HTTP parsing/summarization endpoints, authentication for remote mode, cache policy, and server-side report classification.
- `mobile/android/` owns the Android WebView wrapper, on-device processing, encrypted local summary/notes storage, and mobile lifecycle.
- `scripts/sync_navigation_core.py` owns synchronization checks for the canonical shared JavaScript.
- Tests and `.github/workflows/ci.yml` define the executable regression contract.

Do not add parallel navigation engines, silent popup fallback, alternate credential paths, raw-report caches, or duplicated parser ownership.

## Safe report-processing rules

- Classify fetched content before parsing: supported PDF/text, login/session page, viewer shell, duplicate-report page, generic HTML, empty response, wrong endpoint, or unsupported format.
- A candidate request mapping is not validated until `Test Direct Fetch` retrieves and parses a supported report.
- Bulk modes must not silently fall back to visible popup capture.
- Row indexes are not trusted cache keys. Preserve safe hashed report-key behavior.
- Diagnostics must remain sanitized and exclude identifiers, query values, session material, raw source content, and transient filenames.
- Never infer a negative or normal result from absent text.
- Preserve units, dates, amendments, duplicate reports, organisms, susceptibilities, and parse errors explicitly.

## Change workflow

1. Define the exact symptom, intended behavior, affected platform, trust boundary, and files that should not change.
2. Reproduce with a synthetic/de-identified fixture or focused test.
3. Establish root cause before editing.
4. Change the owning module or canonical shared core only.
5. Add regression coverage for normal, malformed, unsupported, session-expired, privacy-leak, and platform-specific paths as applicable.
6. Verify shared-core synchronization when navigation logic changes.
7. Review the diff for credential/session leakage, raw-data persistence, unsafe fallback, and source-provenance loss.
8. Run the complete required CI on the final branch head.
9. Open one focused PR and merge only after all checks and review threads pass.

## Minimum validation

Use the exact repository CI commands for affected paths:

```bash
pip install -r helper/requirements-dev.txt
python -m pytest -q
python -m py_compile helper/main.py helper/models.py helper/cache.py
npm ci
npm test
python scripts/sync_navigation_core.py --check
cd mobile/android && ./gradlew clean test lintDebug assembleDebug
```

For Android PDF or WebView changes, also require the configured instrumented tests. For Docker/helper changes, validate the helper Docker build. Never test with identifiable live reports in CI.

## Release evidence

A feature is not complete because one happy-path report works. Completion requires final-head CI, hazard-path tests, source-to-summary traceability, sanitized diagnostics, and an explicit residual-risk statement. Use the `session-worklog` skill when work will continue across chats.
