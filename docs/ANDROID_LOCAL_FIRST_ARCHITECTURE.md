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

PDF processing remains Railway-backed in AUTO mode. This PR does not claim full offline/local PDF support. Railway remains optional by mode but is not removed. Android rejects remote uploads larger than approximately 18 MB before Base64 encoding; 18 MB of binary report data expands to roughly 24 MB Base64 plus JSON overhead, keeping requests below the 25 MB Railway helper body limit.

## Privacy behavior

NIMS credentials are not stored. NIMS cookies remain on-device and are used only for direct NIMS report fetches. Cookies are not uploaded to Railway. Raw HTML, raw PDF bytes, and raw report text are not persisted. Railway receives report content only when remote processing is used. The helper source metadata is sanitized to an approved NIMS HTTPS host/path without query strings, fragments, transient filenames, cookies, or hidden session values.

## Limitations

- Local PDF parsing is not implemented.
- No OCR is included.
- Local parsing is conservative and may mark unfamiliar formats unsupported.
- Source NIMS reports must be verified before clinical decisions.

## Migration roadmap

- validated local PDF extraction;
- parser parity tests with de-identified NIMS PDFs;
- optional encrypted structured database;
- removal of Railway only after local parity is proven.

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

## PR #20 corrections

The production Android processing path now routes fetched report bytes through `ProcessingRouter` instead of calling helper parse/summarize directly from `MainActivity`. `LOCAL_ONLY` does not require Railway helper settings and never calls Railway. `AUTO` uses local parsing for supported text/HTML, Railway for PDFs, and blocks login/session/captcha/OTP pages from remote fallback. `REMOTE_ONLY` preserves Railway behavior and maps helper JSON back into domain summaries.

Parser safety was tightened: culture results are parsed per block, resistance acronyms use explicit word boundaries, lab label extraction is case-insensitive and position-based, comparator values such as `<0.5` and `>100` are retained, and summaries sort normalized dates chronologically. Bulk processing is coroutine-based with structured child tasks, concurrency capped at two, and an active job can be cancelled. Popup WebView navigation is restricted to approved NIMS HTTPS hosts and paths; rejected popup URLs are not forwarded to the main WebView.

Remaining roadmap:

- validated local PDF extraction;
- parser parity tests with de-identified NIMS PDFs;
- optional encrypted structured database;
- removal of Railway only after parity.
