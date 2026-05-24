from __future__ import annotations

import base64
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "helper"
sys.path.insert(0, str(HELPER))

from main import app  # noqa: E402
from parsers.culture_parser import parse_culture  # noqa: E402
from parsers.lab_parser import infer_report_type, parse_lab_parameters  # noqa: E402
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

