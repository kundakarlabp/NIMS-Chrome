from pathlib import Path

SOURCE = Path("mobile/android/app/src/main/java/org/kundakarlab/nimsfastsummarymobile/MainActivity.kt")


def replace_once(text: str, before: str, after: str, label: str) -> str:
    if after in text:
        return text
    if text.count(before) != 1:
        raise RuntimeError(f"Expected exactly one {label} target, found {text.count(before)}")
    return text.replace(before, after, 1)


def main() -> None:
    text = SOURCE.read_text(encoding="utf-8")

    text = replace_once(
        text,
        '                    onOpenCrReports = { runMode("bulk_fast") },',
        '''                    onOpenCrReports = {
                        val visibleRows = crossFrameReport?.optJSONArray("rows")?.length() ?: 0
                        if (visibleRows > 0) runMode("bulk_fast") else openCrWiseReports()
                    },''',
        "Open CR / Analyze action",
    )

    text = replace_once(
        text,
        "        crossFrameReport = json",
        '''        if (FrameReportNormalizer.normalize(json, webView.url ?: NIMS_LOGIN_URL) { log(it) } == null) return
        crossFrameReport = json''',
        "cross-frame normalization",
    )

    text = replace_once(
        text,
        '            item { Button(onClick = onOpenCrReports, enabled = !navigationInProgress) { Text("Analyze Current Results") } }',
        '            item { Button(onClick = onOpenCrReports, enabled = !navigationInProgress) { Text("Open CR / Analyze") } }',
        "primary button label",
    )

    text = replace_once(
        text,
        '                AppState.REPORT_PAGE_READY -> "Report list detected. Discover mapping."',
        '                AppState.REPORT_PAGE_READY -> "Enter the CR number if needed; after the report list appears, tap Open CR / Analyze."',
        "report-page guidance",
    )

    SOURCE.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
