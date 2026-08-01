from pathlib import Path

path = Path("mobile/android/app/src/main/java/org/kundakarlab/nimsfastsummarymobile/ReportRequestOptimizer.kt")
text = path.read_text(encoding="utf-8")
old = 'listOf(stableLabId, row.optString("report_name"), row.optString("date_sent")).joinToString("|").lowercase()'
new = 'listOf(stableLabId, row.optString("date_sent")).joinToString("|").lowercase()'
if text.count(old) != 1:
    raise RuntimeError(f"expected one episode-key expression, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
