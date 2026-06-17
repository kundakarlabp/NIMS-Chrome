# Android local-first architecture

## Current architecture

The Android app embeds the NIMS site in a WebView, requires manual NIMS login, discovers the report-list mapping with `nimsReportCore.js`, fetches selected reports with WebView cookies, and uses the configured Railway helper for parsing and summarization. Railway remains the reliable fallback path.

## Target local-first architecture

This PR introduces a small processing boundary:

- `ReportInput` carries safe metadata, content type, and transient in-memory bytes.
- `ReportProcessor` defines parse and summarize operations.
- `LocalTextReportProcessor` supports conservative on-device text/HTML parsing.
- `ProcessingRouter` selects local or Railway processing by mode.
- `RemoteReportProcessor` wraps the existing Railway helper protocol.

## Processing modes

- **Automatic**: process supported text/HTML reports on-device and use Railway for unsupported formats.
- **On-device only**: never use Railway; PDF reports are rejected with a clear unsupported-format message.
- **Railway only**: preserve existing Railway parsing and summary behavior.

## Supported local formats

Local parsing is intentionally conservative. It supports text/plain and report-like text/html with embedded text. It recognizes high-confidence lab rows such as hemoglobin, platelets, creatinine, electrolytes, bilirubin, SGOT/SGPT, CRP, procalcitonin, and INR. It recognizes explicit culture terms such as specimen, organism, no growth, susceptible/sensitive/intermediate/resistant, ESBL, MRSA, VRE, CRE, CRAB, carbapenem resistant, and colistin resistant.

## Railway fallback

PDF processing remains Railway-backed in AUTO mode. This PR does not claim full offline/local PDF support. Railway remains optional by mode but is not removed.

## Privacy behavior

NIMS credentials are not stored. NIMS cookies remain on-device and are used only for direct NIMS report fetches. Cookies are not uploaded to Railway. Raw HTML, raw PDF bytes, and raw report text are not persisted. Railway receives report content only when remote processing is used.

## Limitations

- Local PDF parsing is not implemented.
- No OCR is included.
- Local parsing is conservative and may mark unfamiliar formats unsupported.
- Source NIMS reports must be verified before clinical decisions.

## Migration roadmap

1. Move remaining Activity-owned processing state into a ViewModel.
2. Route all parse/summarize calls through `ProcessingRouter`.
3. Add cancellation-aware coroutine bulk processing with concurrency capped at two.
4. Add validated local PDF support only after memory and parser-parity tests.

## Test commands

```bash
python -m pytest -q
docker build -f helper/Dockerfile .
cd mobile/android
./gradlew clean
./gradlew test
./gradlew assembleDebug
./gradlew lintDebug
```

## Verification disclaimer

Auto-parsed summary. Verify with source NIMS reports before clinical decisions.
