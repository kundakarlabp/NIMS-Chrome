from __future__ import annotations

import base64
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "helper"
sys.path.insert(0, str(HELPER))

from cache import clear_cache, is_safe_report_id, is_safe_report_key, make_cache_key  # noqa: E402
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


def test_nims_blood_culture_48_hour_no_growth_fields() -> None:
    text = """
    Fan Blood Culture - First Bottle of first Set (48 Hrs Report)
    Sample Processed : Blood
    CULTURE SHOWS NO GROWTH AEROBICALLY AFTER INCUBATION FOR ABOUT 36to 48 HOURS.
    FINAL REPORT AFTER 5 DAYS OF INCUBATION.
    Lab/Study No. : B18598
    Requisition Date: 13-May-2026 19:32
    Coll./Study Date : 13-May-2026 19:35
    Sample Type/No : Any Other
    Specimen/26IBC07738
    Reporting Date : 15-May-2026 15:07
    """
    culture = parse_culture(text)
    assert culture.culture_no == "B18598"
    assert culture.culture_number == "B18598"
    assert culture.specimen_no == "26IBC07738"
    assert culture.sample_processed == "Blood"
    assert culture.site_specimen == "Blood"
    assert culture.result == "no_growth"
    assert culture.result_status == "no_growth"
    assert culture.organism == ""
    assert culture.growth_quantity == ""
    assert culture.report_status == "48_hour"
    assert culture.bottle_set == "set_1_bottle_1"
    assert culture.collection_date == "13-May-2026 19:35"
    assert culture.reporting_date == "15-May-2026 15:07"


def test_nims_sputum_positive_aerobic_culture_fields() -> None:
    text = """
    Sample Processed: SPUTUM
    AEROBIC CULTURE
    CULTURE SHOWS SCANTY GROWTH OF GRAM NEGATIVE BACILLI.
    COLONIZATION OR CONTAMINATION ? PLEASE REPEAT IF NECESSARY.
    Lab/Study No. : E7654
    Requisition Date: 08-May-2026 18:45
    Coll./Study Date : 08-May-2026 19:04
    Specimen/26BCT08916
    Reporting Date : 09-May-2026 13:26
    """
    culture = parse_culture(text)
    assert culture.culture_no == "E7654"
    assert culture.specimen_no == "26BCT08916"
    assert culture.site_specimen == "Sputum"
    assert culture.culture_type == "Aerobic culture"
    assert culture.result == "positive"
    assert culture.growth_quantity == "scanty"
    assert culture.organism == "Gram negative bacilli"
    assert "Possible colonization/contamination" in culture.comment
    assert "repeat if necessary" in culture.comment
    assert culture.sensitivity_summary == {"sensitive": [], "resistant": [], "intermediate": []}


def test_nims_multi_section_blood_culture_creates_bottle_rows() -> None:
    from parsers.culture_parser import parse_cultures

    common = """
    Sample Processed : Blood
    Lab/Study No. : B18598
    Requisition Date: 13-May-2026 19:32
    Coll./Study Date : 13-May-2026 19:35
    Specimen/26IBC07738
    Reporting Date : 15-May-2026 15:07
    """
    text = common + """
    Fan Blood Culture - First Bottle of first Set (48 Hrs Report)
    CULTURE SHOWS NO GROWTH AEROBICALLY AFTER INCUBATION FOR ABOUT 36to 48 HOURS.
    Fan Blood Culture - Second Bottle of first Set (48 Hrs Report)
    CULTURE SHOWS NO GROWTH AEROBICALLY AFTER INCUBATION FOR ABOUT 36to 48 HOURS.
    Fan Blood Culture - First Bottle of Second Set (48 Hrs Report)
    CULTURE SHOWS NO GROWTH AEROBICALLY AFTER INCUBATION FOR ABOUT 36to 48 HOURS.
    Fan Blood Culture - Second Bottle of Second Set (48 Hrs Report)
    CULTURE SHOWS NO GROWTH AEROBICALLY AFTER INCUBATION FOR ABOUT 36to 48 HOURS.
    """
    cultures = parse_cultures(text)
    assert len(cultures) == 4
    assert {culture.bottle_set for culture in cultures} == {
        "set_1_bottle_1",
        "set_1_bottle_2",
        "set_2_bottle_1",
        "set_2_bottle_2",
    }
    assert {culture.culture_no for culture in cultures} == {"B18598"}
    assert {culture.specimen_no for culture in cultures} == {"26IBC07738"}
    assert all(culture.result == "no_growth" for culture in cultures)


def test_nims_common_organism_casing() -> None:
    culture = parse_culture("AEROBIC CULTURE\nCULTURE SHOWS HEAVY GROWTH OF KLEBSIELLA PNEUMONIAE")
    assert culture.growth_quantity == "heavy"
    assert culture.organism == "Klebsiella pneumoniae"
    assert culture.result == "positive"


def test_nims_culture_object_excludes_phi_fields() -> None:
    culture = parse_culture(
        """
        Patient Name : Test Patient
        CR No : 123456
        Age/Sex : 50/M
        Ward : ICU
        Clinician : Doctor
        Sample Processed: SPUTUM
        CULTURE SHOWS SCANTY GROWTH OF GRAM NEGATIVE BACILLI.
        """
    )
    data = culture.model_dump()
    assert "patient_name" not in data
    assert "cr_no" not in data
    assert "age" not in data
    assert "ward" not in data
    assert "clinician" not in data


def test_nims_no_susceptibility_defaults() -> None:
    client = TestClient(app)
    parsed = {
        "report_id": "culture-1",
        "report_name": "Sputum culture",
        "date_sent": "09-May-2026",
        "report_type": "culture",
        "report_tags": ["culture"],
        "parameters": [],
        "culture_results": [
            parse_culture("Sample Processed: SPUTUM\nCULTURE SHOWS SCANTY GROWTH OF GRAM NEGATIVE BACILLI.").model_dump()
        ],
        "errors": [],
    }
    summary = client.post("/summarize", json={"mode": "cultures_only", "reports": [parsed]}).json()
    row = summary["culture_table"][0]
    assert row["sensitivity_summary"] == "No susceptibility table found"
    assert row["susceptible_antibiotics"] == []
    assert row["resistant_antibiotics"] == []
    assert row["intermediate_antibiotics"] == []


def test_nims_simple_susceptibility_table() -> None:
    culture = parse_culture(
        """
        Sample Processed: URINE
        CULTURE SHOWS HEAVY GROWTH OF KLEBSIELLA PNEUMONIAE.
        Amikacin S
        Ceftriaxone R
        Meropenem S
        """
    )
    assert "Amikacin" in culture.susceptible_antibiotics
    assert "Meropenem" in culture.susceptible_antibiotics
    assert "Ceftriaxone" in culture.resistant_antibiotics


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
    safe_key = "report_key:" + ("a" * 64)
    assert is_safe_report_key(safe_key)
    assert not is_safe_report_key("report_key:row-1")
    assert make_cache_key(safe_key, b"first", "mock://same", "19-May-2026", "CBC") == safe_key


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


def test_cache_lookup_reuses_safe_report_key_before_download() -> None:
    clear_cache()
    client = TestClient(app)
    report_key = "report_key:" + ("b" * 64)
    parsed = client.post(
        "/parse-report",
        json={
            "report_id": report_key,
            "report_name": "CBC",
            "date_sent": "19-May-2026",
            "source_url": "",
            "pdf_base64": base64.b64encode(b"Hemoglobin 8.9 g/dL range 13-17").decode("ascii"),
        },
    ).json()
    assert parsed["report_id"] == report_key
    lookup = client.post(
        "/cache-lookup",
        json={
            "reports": [
                {"report_key": report_key, "report_name": "CBC", "date_sent": "19-May-2026"},
                {"report_key": "row-1", "report_name": "CBC", "date_sent": "19-May-2026"},
            ]
        },
    ).json()
    assert report_key in lookup["hits"]
    assert lookup["hits"][report_key]["cached"] is True
    assert "row-1" in lookup["misses"]


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

