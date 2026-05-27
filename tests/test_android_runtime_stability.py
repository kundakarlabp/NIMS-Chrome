from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_APP = ROOT / "mobile" / "android" / "app"


def test_android_asset_source_points_to_shared_web_core() -> None:
    build_gradle = (ANDROID_APP / "build.gradle.kts").read_text(encoding="utf-8")
    assert '"../../../shared/nims-web"' in build_gradle
    assert '"../../../../shared/nims-web"' not in build_gradle
    assert (ROOT / "shared" / "nims-web" / "nimsReportCore.js").is_file()


def test_android_bulk_workers_do_not_evaluate_webview_javascript() -> None:
    source = (
        ANDROID_APP
        / "src/main/java/org/kundakarlab/nimsfastsummarymobile/MainActivity.kt"
    ).read_text(encoding="utf-8")
    fetch_body = source.split("private fun fetchAndParseOne", 1)[1].split(
        "private fun fetchWithWebViewCookies", 1
    )[0]
    assert "evaluateCore" not in fetch_body
    assert "evaluateJson" not in fetch_body
    assert "transientArgFor" not in source
    assert "PreparedReportRequest" in source


def test_android_rendering_uses_optional_json_objects() -> None:
    source = (
        ANDROID_APP
        / "src/main/java/org/kundakarlab/nimsfastsummarymobile/MainActivity.kt"
    ).read_text(encoding="utf-8")
    assert ".getJSONObject(" not in source
    assert ".optJSONObject(" in source
