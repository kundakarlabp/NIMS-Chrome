from __future__ import annotations

import base64
import logging
from pathlib import Path
import sys

from fastapi.testclient import TestClient


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "helper"
sys.path.insert(0, str(HELPER))

from cache import clear_cache, get_cached  # noqa: E402
from main import _RATE_BUCKETS, allowed_origins, app  # noqa: E402


def remote_env(monkeypatch) -> None:
    _RATE_BUCKETS.clear()
    monkeypatch.setenv("NIMS_HELPER_REMOTE_MODE", "true")
    monkeypatch.setenv("NIMS_HELPER_API_KEY", "secret-key")
    monkeypatch.setenv("NIMS_HELPER_CACHE_ENABLED", "false")


def test_health_works_without_api_key_in_remote_mode(monkeypatch) -> None:
    remote_env(monkeypatch)
    response = TestClient(app).get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["ok"] is True
    assert data["service"] == "nims-fast-summary-helper"
    assert data["remote_mode"] is True
    assert data["cache_enabled"] is False
    assert data["api_key_configured"] is True
    assert data["max_body_mb"] == 25


def test_health_reports_unconfigured_remote_api_key(monkeypatch) -> None:
    monkeypatch.setenv("NIMS_HELPER_REMOTE_MODE", "true")
    monkeypatch.delenv("NIMS_HELPER_API_KEY", raising=False)
    response = TestClient(app).get("/health")
    assert response.status_code == 200
    assert response.json()["api_key_configured"] is False


def test_version_works_without_api_key(monkeypatch) -> None:
    remote_env(monkeypatch)
    response = TestClient(app).get("/version")
    assert response.status_code == 200
    data = response.json()
    assert data["ok"] is True
    assert data["service"] == "nims-fast-summary-helper"
    assert data["parser_version"] == 1
    assert "commit_sha" in data["build"]


def test_parse_report_rejects_missing_api_key_in_remote_mode(monkeypatch) -> None:
    remote_env(monkeypatch)
    response = TestClient(app).post("/parse-report", json={"report_name": "CBC"})
    assert response.status_code == 401
    assert response.json() == {"ok": False, "error": "unauthorized"}


def test_summarize_rejects_missing_api_key_in_remote_mode(monkeypatch) -> None:
    remote_env(monkeypatch)
    response = TestClient(app).post("/summarize", json={"mode": "fast", "reports": []})
    assert response.status_code == 401
    assert response.json() == {"ok": False, "error": "unauthorized"}


def test_parse_report_rejects_wrong_api_key_in_remote_mode(monkeypatch) -> None:
    remote_env(monkeypatch)
    response = TestClient(app).post(
        "/parse-report",
        headers={"X-NIMS-HELPER-KEY": "wrong"},
        json={"report_name": "CBC"},
    )
    assert response.status_code == 401
    assert response.json() == {"ok": False, "error": "unauthorized"}


def test_cache_lookup_rejects_missing_api_key_in_remote_mode(monkeypatch) -> None:
    remote_env(monkeypatch)
    response = TestClient(app).post("/cache-lookup", json={"reports": []})
    assert response.status_code == 401
    assert response.json() == {"ok": False, "error": "unauthorized"}


def test_correct_api_key_allows_parse_and_summarize_in_remote_mode(monkeypatch) -> None:
    remote_env(monkeypatch)
    client = TestClient(app)
    parsed = client.post(
        "/parse-report",
        headers={"X-NIMS-HELPER-KEY": "secret-key"},
        json={
            "report_id": "remote-cbc-1",
            "report_name": "CBC",
            "date_sent": "19-May-2026",
            "pdf_base64": base64.b64encode(b"Hemoglobin 8.9 g/dL range 13-17").decode("ascii"),
        },
    )
    assert parsed.status_code == 200
    assert parsed.json()["parameters"]
    assert parsed.json()["raw_text_preview"] == ""
    summarized = client.post(
        "/summarize",
        headers={"X-NIMS-HELPER-KEY": "secret-key"},
        json={"mode": "fast", "reports": [parsed.json()]},
    )
    assert summarized.status_code == 200
    assert "lab_trend_table" in summarized.json()


def test_local_mode_still_returns_deidentified_preview(monkeypatch) -> None:
    monkeypatch.delenv("NIMS_HELPER_REMOTE_MODE", raising=False)
    response = TestClient(app).post(
        "/parse-report",
        json={
            "report_id": "local-preview-1",
            "report_name": "CBC",
            "date_sent": "19-May-2026",
            "text": (
                "Patient Name: Test Patient\nCR No: 123456\nUHID: AB123\n"
                "Age/Sex: 45/M\nMobile: 9876543210\nAddress: Secret Road\n"
                "Hemoglobin 8.9 g/dL range 13-17"
            ),
        },
    )
    assert response.status_code == 200
    preview = response.json()["raw_text_preview"]
    assert preview
    assert "Test Patient" not in preview
    assert "123456" not in preview
    assert "AB123" not in preview
    assert "9876543210" not in preview
    assert "Secret Road" not in preview


def test_local_mode_cache_enabled_by_default(monkeypatch) -> None:
    monkeypatch.delenv("NIMS_HELPER_REMOTE_MODE", raising=False)
    response = TestClient(app).get("/health")
    assert response.status_code == 200
    assert response.json()["cache_enabled"] is True


def test_railway_dockerfile_uses_repo_root_helper_context() -> None:
    dockerfile = (ROOT / "helper" / "Dockerfile").read_text(encoding="utf-8")
    assert "COPY helper/requirements.txt ./requirements.txt" in dockerfile
    assert "COPY helper/ ." in dockerfile


def test_local_mode_preserves_existing_no_key_behavior(monkeypatch) -> None:
    monkeypatch.delenv("NIMS_HELPER_REMOTE_MODE", raising=False)
    response = TestClient(app).post("/summarize", json={"mode": "fast", "reports": []})
    assert response.status_code == 200


def test_remote_cors_origin_list_never_contains_wildcard(monkeypatch) -> None:
    monkeypatch.setenv("NIMS_HELPER_REMOTE_MODE", "true")
    monkeypatch.setenv("NIMS_HELPER_ALLOWED_ORIGINS", "*,https://example.up.railway.app,chrome-extension://abc")
    origins = allowed_origins()
    assert "*" not in origins
    assert origins == ["https://example.up.railway.app", "chrome-extension://abc"]


def test_request_too_large_returns_413(monkeypatch) -> None:
    monkeypatch.setenv("NIMS_HELPER_MAX_BODY_MB", "1")
    response = TestClient(app).post(
        "/parse-report",
        content=b"{}",
        headers={"content-type": "application/json", "content-length": str(2 * 1024 * 1024)},
    )
    assert response.status_code == 413
    assert response.json()["error"] == "request body too large"


def test_remote_rate_limit_returns_429(monkeypatch) -> None:
    remote_env(monkeypatch)
    monkeypatch.setenv("NIMS_HELPER_RATE_LIMIT_PER_MINUTE", "1")
    client = TestClient(app)
    headers = {"X-NIMS-HELPER-KEY": "secret-key"}
    first = client.post("/summarize", headers=headers, json={"mode": "fast", "reports": []})
    second = client.post("/summarize", headers=headers, json={"mode": "fast", "reports": []})
    assert first.status_code == 200
    assert second.status_code == 429
    assert second.json()["error"] == "rate limit exceeded"


def test_raw_report_content_not_logged_on_parse_error(monkeypatch, caplog) -> None:
    remote_env(monkeypatch)
    caplog.set_level(logging.INFO, logger="nims_helper")
    raw = "Patient Name: Secret Person\nCR No: 123456\nHemoglobin 8.9 g/dL"
    response = TestClient(app).post(
        "/parse-report",
        headers={"X-NIMS-HELPER-KEY": "secret-key"},
        json={
            "report_id": "remote-log-1",
            "report_name": "CBC",
            "date_sent": "19-May-2026",
            "text": raw,
        },
    )
    assert response.status_code == 200
    assert "Secret Person" not in caplog.text
    assert "123456" not in caplog.text
    assert "Hemoglobin" not in caplog.text


def test_remote_cache_disabled_does_not_store_parsed_json(monkeypatch) -> None:
    remote_env(monkeypatch)
    clear_cache()
    report_id = "report_key:" + ("c" * 64)
    response = TestClient(app).post(
        "/parse-report",
        headers={"X-NIMS-HELPER-KEY": "secret-key"},
        json={
            "report_id": report_id,
            "report_name": "CBC",
            "date_sent": "19-May-2026",
            "pdf_base64": base64.b64encode(b"Hemoglobin 8.9 g/dL range 13-17").decode("ascii"),
        },
    )
    assert response.status_code == 200
    assert get_cached(report_id) is None
