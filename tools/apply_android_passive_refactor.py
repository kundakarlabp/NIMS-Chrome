from __future__ import annotations

import re
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def replace_regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.DOTALL)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one regex match, found {count}")
    return updated


def main() -> None:
    path = Path("mobile/android/app/src/main/java/org/kundakarlab/nimsfastsummarymobile/MainActivity.kt")
    text = path.read_text(encoding="utf-8")

    for unused_import in (
        "import androidx.webkit.WebViewCompat\n",
        "import androidx.webkit.WebViewFeature\n",
        "import org.kundakarlab.nimsfastsummarymobile.navigation.NimsNavigationCoordinator\n",
        "import org.kundakarlab.nimsfastsummarymobile.navigation.NimsNavigationOutcome\n",
        "import org.kundakarlab.nimsfastsummarymobile.navigation.NimsNavigationStep\n",
        "import kotlinx.coroutines.suspendCancellableCoroutine\n",
        "import kotlin.coroutines.resume\n",
    ):
        if unused_import not in text:
            raise RuntimeError(f"missing expected import: {unused_import.strip()}")
        text = text.replace(unused_import, "", 1)

    text = replace_once(
        text,
        "    private var navigationGeneration = 0L\n",
        "",
        "navigation generation field",
    )

    text = replace_regex_once(
        text,
        r"\n\n    private fun openCrWiseReports\(\) \{[\s\S]*?\n    private fun diagnosePage\(\) \{",
        '''

    private fun cancelNavigation() {
        navigationJob?.cancel()
        navigationJob = null
        navigationInProgress = false
    }

    private fun diagnosePage() {''',
        "automatic navigation block",
    )

    text = replace_regex_once(
        text,
        r'''            "nims_runtime_ready" -> \{[\s\S]*?            "nims_report_frame" -> \{ /\* fall through to handling below \*/ \}''',
        '''            "nims_report_frame" -> { /* fall through to handling below */ }''',
        "legacy runtime telemetry handlers",
    )

    text = replace_once(
        text,
        "    // Receives the report-frame announcement posted by nimsAndroidFrameBridge.js\n    // from whichever frame (even cross-origin) actually contains the result rows.",
        "    // Receives passive page-state and report-row announcements from the frame\n    // that actually owns the rendered NIMS content, including cross-origin frames.",
        "observer comment",
    )

    text = text.replace(
        "Report list detected ($rowCount visible). Tap Analyze Current Results.",
        "Report list detected ($rowCount visible). Tap Analyze Results.",
    )
    text = text.replace(
        "Report rows are inside a different-origin frame this app cannot read from the top frame ($blocked blocked, $reachable reachable). This needs the all-frames build, not a navigation retry.",
        "Advanced top-frame diagnostics cannot inspect the owning report frame ($blocked blocked, $reachable reachable). Keep the result list visible and use Analyze Results.",
    )

    path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
