from pathlib import Path

path = Path("mobile/android/app/src/main/java/org/kundakarlab/nimsfastsummarymobile/ChromeModeUi.kt")
text = path.read_text(encoding="utf-8")
line = "import androidx.compose.foundation.layout.weight\n"
if text.count(line) != 1:
    raise SystemExit("expected exactly one weight import")
path.write_text(text.replace(line, "", 1), encoding="utf-8")
