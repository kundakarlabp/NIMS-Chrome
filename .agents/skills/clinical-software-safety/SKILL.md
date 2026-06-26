---
name: clinical-software-safety
description: Review clinical report software for privacy, source provenance, deterministic parsing, explicit failure classification, auditability, and clinician verification.
---

# Clinical Software Safety

## Purpose

Keep report retrieval and summarization subordinate to privacy, source fidelity, and clinician review.

## Workflow

1. Define intended users, data handled, supported formats, and prohibited autonomous decisions.
2. Map data movement across browser, device, helper, storage, cache, logs, diagnostics, and exports.
3. Use synthetic or de-identified test fixtures and minimize stored or transmitted data.
4. Preserve report provenance, retrieval time, parser version, completeness, omissions, and errors.
5. Classify input before parsing and reject unsupported, incomplete, or non-report content visibly.
6. Keep deterministic extraction separate from interpretation and require clinician verification.
7. Test malformed, duplicated, amended, incomplete, unsupported, and expired-session responses.
8. Review every release for new data flows, storage, remote calls, and residual clinical risk.

## Validation

Require focused privacy and parsing tests plus the complete repository CI on the final branch head. A single successful report is not sufficient evidence.

## Failure and uncertainty handling

When governance, source completeness, or clinical reliability is uncertain, keep the feature limited to supervised use and record the unresolved risk.
