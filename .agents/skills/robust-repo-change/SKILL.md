---
name: robust-repo-change
description: Diagnose and implement repository changes through root-cause analysis, narrow diffs, architecture-preserving edits, regression tests, pull requests, review resolution, CI validation, and controlled merge.
---

# Robust Repository Change

## Purpose

Prevent repeated code churn, path drift, duplicate architecture, and unverified merges. Use repository evidence rather than speculative edits.

## Workflow

### 1. Establish repository truth

- Read all applicable `AGENTS.md` files.
- Inspect the authoritative runtime path, architecture documents, tests, recent commits, open PRs, and CI configuration.
- Identify the owning module and interfaces that must remain stable.
- State the intended scope and files that should not change.

### 2. Reproduce or define the failure

- Capture the exact symptom, expected behavior, environment, inputs, and failure evidence.
- Prefer a deterministic failing test, de-identified fixture replay, minimal harness, or focused command.
- For intermittent failures, instrument one boundary at a time and collect evidence before editing.

### 3. Establish root cause

- Trace bad state or data to its source.
- Compare with a working path in the same repository.
- Form a falsifiable hypothesis and test the smallest variable.
- Do not patch symptoms, suppress broad exceptions, or add hidden fallbacks.

### 4. Implement narrowly

- Create a branch from current `main`.
- Add or update a regression test first when a valid seam exists.
- Make the smallest change in the owning module or canonical shared core.
- Preserve public interfaces, configuration semantics, privacy boundaries, and state ownership unless the task explicitly requires migration.
- Keep refactoring, feature work, and bug fixes separate.

### 5. Review the diff

Check for:

- unrelated formatting or renames
- duplicate navigation, parser, cache, storage, or helper paths
- stale-data, race, idempotency, retry, reconnect, and restart failures
- secrets, credentials, session material, identifiers, or raw report data
- unsafe defaults or silent fallbacks
- test mocks that bypass production behavior
- missing observability, sanitized diagnostics, source provenance, and recovery behavior

### 6. Validate

Run the exact repository-required commands plus focused tests for the changed path. Fresh evidence must include:

- compilation/build success
- lint checks when configured
- focused regression and privacy tests
- complete required test suite
- CI result on the final PR head

For a regression test, verify that it would fail without the fix when practical.

### 7. PR and merge

- Open one focused PR with root cause, fix, affected paths, privacy/clinical impact, validation commands, and residual risk.
- Inspect automated review suggestions technically; do not accept them blindly.
- Resolve all valid review threads.
- Re-run CI after the final change.
- Merge only when the final head is mergeable and all required checks pass.
- Report the PR and merge commit accurately.

## Validation

Completion requires fresh command output or CI evidence for the final branch head. A previous run, partial suite, plausible diff, or agent statement is not sufficient evidence.

## Production safeguards

- Never weaken privacy, authentication, manual-login, local-first, source-verification, or sanitized-diagnostic boundaries to make tests pass.
- Never use real patient data, credentials, cookies, session values, or identifiable reports during testing or development.
- Preserve Android on-device-first behavior and keep remote helper processing explicitly optional.
- Preserve explicit failure classification for unsupported, incomplete, session-expired, or wrong-endpoint content.
- Keep clinician verification against source reports mandatory.

## Failure and uncertainty handling

If the issue cannot be reproduced or required tests cannot run, do not claim completion. Report the evidence collected, the exact blocker, the remaining privacy/clinical risk, and the next diagnostic action.
