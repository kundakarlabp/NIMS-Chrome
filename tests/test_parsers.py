from pathlib import Path
from parsers.culture_parser import parse_culture, parse_cultures
from parsers.lab_parser import parse_lab_parameters

ROOT = Path(__file__).resolve().parent
FIXTURES = ROOT / "fixtures"

def read_fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")

def test_cbc_parses():
    params = {p.canonical_name: p for p in parse_lab_parameters(read_fixture("sample_cbc_text.txt"), "19-May-2026")}
    assert "Hb" in params
    assert "TLC" in params

def test_combined_rft_lft_electrolytes_parse():
    params = {p.canonical_name: p for p in parse_lab_parameters(read_fixture("sample_rft_lft_text.txt"), "19-May-2026")}
    assert {"Creatinine","Sodium","Potassium","Bilirubin total"}.issubset(params)

def test_positive_culture_parses():
    culture = parse_culture(read_fixture("sample_culture_positive_text.txt"))
    assert culture.result in {"positive","possible_contaminant"}
    assert culture.organism

def test_negative_and_pending_are_distinct():
    assert parse_culture(read_fixture("sample_culture_negative_text.txt")).result in {"negative","no_growth"}
    assert parse_culture(read_fixture("sample_culture_pending_text.txt")).result == "pending"

def test_multibottle_sections_remain_separate():
    text = """
    Sample Processed : Blood
    Lab/Study No. : B18598
    Specimen/26IBC07738
    Fan Blood Culture - First Bottle of first Set (48 Hrs Report)
    CULTURE SHOWS NO GROWTH AEROBICALLY AFTER INCUBATION FOR ABOUT 36to 48 HOURS.
    Fan Blood Culture - Second Bottle of first Set (48 Hrs Report)
    CULTURE SHOWS NO GROWTH AEROBICALLY AFTER INCUBATION FOR ABOUT 36to 48 HOURS.
    """
    rows = parse_cultures(text)
    assert len(rows) == 2
    assert {r.bottle_set for r in rows} == {"set_1_bottle_1","set_1_bottle_2"}

def test_culture_schema_does_not_add_identity_fields():
    data = parse_culture("Patient Name: Example\nCR No: 123456789012345\nSample Processed: SPUTUM\nCULTURE SHOWS HEAVY GROWTH OF KLEBSIELLA PNEUMONIAE.").model_dump()
    assert "patient_name" not in data
    assert "cr_no" not in data
