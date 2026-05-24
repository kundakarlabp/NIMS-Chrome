from __future__ import annotations

import base64
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "helper"
sys.path.insert(0, str(HELPER))

from cache import clear_cache, is_safe_report_id, make_cache_key  # noqa: E402
from main import app  # noqa: E402
from parsers.culture_parser import parse_culture  # noqa: E402
from parsers.lab_parser import infer_report_tags, infer_report_type, parse_lab_parameters  # noqa: E402
from parsers.pdf_text import detect_non_report_payload  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402


FIXTURES = ROOT / "tests" / "fixtures"


def read_fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


def test_cbc_extraction() -> None:
    params = {p.canonical_name: p for p in parse_lab_parameters(read_fixture("sample_cbc_text.txt"), "19-May-2026")}
    assert params["Hb"].value == "8.9"
    assert params["Hb"].abnormal_flag == "low"
    assert params["TLC"].value == "18600"
    assert params["TLC"].abnormal_flag == "high"
    assert params["Platelets"].value == "150000"


def test_rft_electrolyte_lft_extraction() -> None:
    params = {p.canonical_name: p for p in parse_lab_parameters(read_fixture("sample_rft_lft_text.txt"), "18-May-2026")}
    assert params["Creatinine"].value == "1.7"
    assert params["Creatinine"].abnormal_flag == "high"
    assert params["Sodium"].abnormal_flag == "low"
    assert params["Potassium"].abnormal_flag == "high"
    assert params["AST/SGOT"].value == "85"
    assert params["ALT/SGPT"].value == "96"
    assert params["Albumin"].abnormal_flag == "low"


def test_report_type_inference() -> None:
    assert infer_report_type("CBC Hemogram", read_fixture("sample_cbc_text.txt")) == "cbc"
    assert infer_report_type("Blood Culture", read_fixture("sample_culture_positive_text.txt")) == "culture"
    assert infer_report_tags("RFT Electrolytes LFT", read_fixture("sample_rft_lft_text.txt")) == ["rft", "electrolytes", "lft"]


def test_culture_positive_extraction() -> None:
    culture = parse_culture(read_fixture("sample_culture_positive_text.txt"))
    assert culture.culture_number == "BC1234"
    assert culture.site == "Central line"
    assert culture.specimen == "Blood"
    assert culture.result_status == "positive"
    assert "Klebsiella pneumoniae" in culture.organisms
    assert "Meropenem" in culture.sensitivity_summary["sensitive"]
    assert "Ceftriaxone" in culture.sensitivity_summary["resistant"]
    assert culture.report_status == "final"


def test_culture_negative_extraction() -> None:
    culture = parse_culture(read_fixture("sample_culture_negative_text.txt"))
    assert culture.result_status == "no_growth"
    assert culture.report_status == "48_hour"


def test_culture_pending_and_table_sensitivity() -> None:
    pending = parse_culture(read_fixture("sample_culture_pending_text.txt"))
    assert pending.culture_number == "ACC-77"
    assert pending.result_status == "pending"
    assert pending.report_status == "preliminary"

    table = parse_culture(read_fixture("sample_culture_table_sensitivity_text.txt"))
    assert table.culture_number == "LAB-55"
    assert "Escherichia coli" in table.organisms
    assert "Meropenem" in table.sensitivity_summary["sensitive"]
    assert "Ceftriaxone" in table.sensitivity_summary["resistant"]
    assert "Ciprofloxacin" in table.sensitivity_summary["intermediate"]


def test_table_and_newline_style_lab_parsing() -> None:
    table_params = {p.canonical_name: p for p in parse_lab_parameters(read_fixture("sample_table_style_lab_text.txt"), "19-May-2026")}
    assert table_params["Hb"].value == "9.1"
    assert table_params["Creatinine"].value == "1.9"
    assert table_params["Sodium"].value == "132"
    assert table_params["Potassium"].value == "5.4"
    assert table_params["Bilirubin total"].value == "2.1"
    assert table_params["AST/SGOT"].value == "75"
    assert table_params["ALT/SGPT"].value == "82"
    assert table_params["CRP"].value == "96"

    newline_params = {p.canonical_name: p for p in parse_lab_parameters(read_fixture("sample_newline_style_lab_text.txt"), "19-May-2026")}
    assert newline_params["Hb"].value == "8.7"
    assert newline_params["Platelets"].value == "98000"
    assert newline_params["Creatinine"].value == "2.2"


def test_unsafe_row_cache_prevention() -> None:
    assert not is_safe_report_id("row-1")
    assert not is_safe_report_id("2")
    assert is_safe_report_id("LAB-2026-ABC")
    assert make_cache_key("row-1", b"first", "mock://same", "19-May-2026", "CBC").startswith("sha256:")
    assert make_cache_key("row-1", b"first", "mock://same", "19-May-2026", "CBC") != make_cache_key("row-1", b"second", "mock://same", "19-May-2026", "CBC")
    assert make_cache_key("LAB-2026-ABC", b"", "mock://same", "19-May-2026", "CBC").startswith("report_id:")


def test_two_different_row_one_payloads_do_not_reuse_cache() -> None:
    clear_cache()
    client = TestClient(app)
    first = client.post(
        "/parse-report",
        json={
            "report_id": "row-1",
            "report_name": "CBC",
            "date_sent": "19-May-2026",
            "source_url": "mock://same",
            "pdf_base64": base64.b64encode(b"Hemoglobin 8.9 g/dL range 13-17").decode("ascii"),
        },
    ).json()
    second = client.post(
        "/parse-report",
        json={
            "report_id": "row-1",
            "report_name": "CBC",
            "date_sent": "19-May-2026",
            "source_url": "mock://same",
            "pdf_base64": base64.b64encode(b"Hemoglobin 11.2 g/dL range 13-17").decode("ascii"),
        },
    ).json()
    assert first["parameters"][0]["value"] == "8.9"
    assert second["parameters"][0]["value"] == "11.2"


def test_session_expired_html_detection() -> None:
    html = read_fixture("session_expired.html")
    assert detect_non_report_payload(html, "text/html") == "session expired or report fetch failed"
    client = TestClient(app)
    response = client.post(
        "/parse-report",
        json={
            "report_id": "login-html",
            "report_name": "CBC",
            "date_sent": "19-May-2026",
            "content_type": "text/html",
            "pdf_base64": base64.b64encode(html.encode("utf-8")).decode("ascii"),
        },
    )
    data = response.json()
    assert data["errors"] == ["session expired or report fetch failed"]
    assert data["parameters"] == []


def test_parse_report_endpoint_accepts_plain_text_payload() -> None:
    client = TestClient(app)
    text = read_fixture("sample_cbc_text.txt")
    response = client.post(
        "/parse-report",
        json={
            "report_id": "fake-cbc-1",
            "report_name": "CBC Hemogram",
            "date_sent": "19-May-2026",
            "source_url": "mock://cbc",
            "pdf_base64": base64.b64encode(text.encode("utf-8")).decode("ascii"),
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["report_type"] == "cbc"
    assert any(param["canonical_name"] == "Hb" for param in data["parameters"])
    assert "Test Patient" not in data["raw_text_preview"]
    assert "000000" not in data["raw_text_preview"]


def test_summarize_latest_to_old_and_duplicate_skipping() -> None:
    client = TestClient(app)
    reports = [
        {
            "report_id": "old",
            "report_name": "CBC",
            "date_sent": "16-May-2026",
            "report_type": "cbc",
            "parameters": [{"name": "Hemoglobin", "canonical_name": "Hb", "value": "10.8", "unit": "g/dL", "date_sent": "16-May-2026"}],
            "culture": None,
            "errors": [],
        },
        {
            "report_id": "latest",
            "report_name": "CBC",
            "date_sent": "19-May-2026",
            "report_type": "cbc",
            "parameters": [{"name": "Hemoglobin", "canonical_name": "Hb", "value": "8.9", "unit": "g/dL", "date_sent": "19-May-2026"}],
            "culture": None,
            "errors": [],
        },
        {
            "report_id": "latest",
            "report_name": "CBC",
            "date_sent": "19-May-2026",
            "report_type": "cbc",
            "parameters": [{"name": "Hemoglobin", "canonical_name": "Hb", "value": "8.9", "unit": "g/dL", "date_sent": "19-May-2026"}],
            "culture": None,
            "errors": [],
        },
    ]
    response = client.post("/summarize", json={"mode": "fast", "reports": reports})
    assert response.status_code == 200
    data = response.json()
    assert data["lab_trend_table"]["columns"] == ["19-May-2026", "16-May-2026"]
    hb = next(row for row in data["lab_trend_table"]["rows"] if row["parameter"] == "Hb")
    assert hb["trend"] == "falling"
    assert len(data["source_reports"]) == 2


def test_combined_report_tags_survive_parse_and_summary() -> None:
    client = TestClient(app)
    text = read_fixture("sample_rft_lft_text.txt")
    parsed = client.post(
        "/parse-report",
        json={
            "report_id": "LAB-COMBINED-1",
            "report_name": "RFT Electrolytes LFT",
            "date_sent": "19-May-2026",
            "pdf_base64": base64.b64encode(text.encode("utf-8")).decode("ascii"),
        },
    ).json()
    assert parsed["report_tags"] == ["rft", "electrolytes", "lft"]
    summary = client.post("/summarize", json={"mode": "fast", "reports": [parsed]}).json()
    params = {row["parameter"] for row in summary["lab_trend_table"]["rows"]}
    assert {"Creatinine", "Sodium", "Bilirubin total"}.issubset(params)

