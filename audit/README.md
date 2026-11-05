# NIMS Results — compatibility-first audit index (5 November 2026)

**Read this before making changes to NIMS retrieval, clinical parsers, source-PDF matching, or Android APK release.** This is a **documentation-only** audit of repository code and historical evidence, not a live HIS or installed-app validation.

| Document | Why it exists |
|---|---|
| [NIMS website integration contracts](NIMS_WEBSITE_INTEGRATION_CONTRACTS_2026-11-05.md) | Exact current Android/desktop/helper data paths; why NIMS login, frames, Custom Sheet and report APIs were difficult; source-of-truth and provenance rules |
| [98-item historical and current issue register](NIMS_ISSUE_REGISTER_2026-11-05.md) | Reported failures, verified source-code issues, implemented mitigations and unresolved test questions; unique IDs AUTH/BULK/SRC/CLIN/UI/PERF/DESK/BUILD/SEC |
| [No-regression validation plan](NO_REGRESSION_VALIDATION_2026-11-05.md) | Concrete evidence ledger, NIMS website compatibility matrix, clinical golden fixtures, Android/desktop/helper CI and actual phone acceptance gates |

**Audit baseline:** `main@21baeb0`, Android **2.0.5** (`versionCode=20006`). Claude's earlier v2.0.4 review was `1f9bda2`, so its findings must be reassessed against the newer baseline. The deployed AppDeploy dashboard frontend, authenticated NIMS portal, patient clinical PDFs and actual device runtime are **outside the verified scope** of this source-only audit.

## What matters most now

1. **The source of truth for clinical values is Custom Sheet GETMETABOLICDATA**, not the `reportList` catalogue. Current Android retrieves one complete selected Custom Sheet; desktop has structured bulk → REST/report parser → authenticated browser fallback.
2. **Report inventory is not result-to-PDF identity.** The HTML source index, REST catalogue, Custom Sheet and original PDF supply different fields. No title/date shortcut may create a verified link; patient CR, specimen, accession (when provided), report stage, set/bottle and narrative content matter.
3. **Do not undo hard-earned HIS compatibility:** login URL can remain after authentication; forms are nested in changing frames; `patCrNo` plus `SHOWPATDETAILS` matter; XHR/fetch responses and Chrome navigation callbacks can arrive late; wrong-patient results must be blocked.
4. **v2.0.5 introduced an important PDF evidence fix.** Compact verified evidence survives eviction of heavy PDF text; don't remove that separation without an eviction/identity test. PR [#201](https://github.com/kundakarlabp/NIMS-Chrome/pull/201) contains the code history, **not proof of live clinical success**.
5. **Clinical display/accuracy must be measured from source → accepted data → parser → All Results → verified PDF/export.** All inline values, full culture/AST narratives, proper timestamps and no raw HIS code interpretation are required.
6. **Investigate before refactoring:** Android `MainActivity.kt` is ~5,138 lines with four single-thread executors. Profile actual loading, PDF memory, stale callbacks and scrolling before restructuring code.
7. **APK upgrades:** v2's `org.kundakarlab.nimsresults.recovery` package and persistent signing certificate must remain unchanged. v1 remains separate; no uninstall, reset or new signer to force updates.
8. **Confirmed code risks are not all urgent user-facing restrictions:** authenticated manual-cookie redirect handling must be made safe without breaking login; compulsory app lock, screenshot restrictions and major hardening are **deferred** at the user's request.
9. **Repository documentation itself needs cleanup:** `AGENTS.md` forbids Android and PDF functions despite current app; some READMEs describe REST as primary even though desktop code tries Custom Sheet first; CI pins old connector version. These are tracked, not silently edited in this audit.
10. **Release claims must be evidence-based:** source review, green tests, signed APK, same-device install and live same-CR NIMS validation are separate outcomes.

## Practical repair rule

**Reproduce one issue → identify original NIMS/clinical contract → add a synthetic structural fixture and approved private real-source comparison → change the smallest necessary module → rerun source/identity/timeout tests → verify real authenticated NIMS behavior and v2 upgrade.**

Avoid removing the existing fallback just because a newer endpoint appears simpler. Do **not** conflate a merged historical PR with verified current clinical correctness. No app behavior or product security setting was changed by these documents.
