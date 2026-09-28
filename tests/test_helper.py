import base64
import sys
from pathlib import Path
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "helper"))
from main import app  # noqa: E402

FIXTURES = ROOT / "tests" / "fixtures"

def read_fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")

def test_health_is_local_only():
    data = TestClient(app).get("/health").json()
    assert data["ok"] is True
    assert data["local_only"] is True

def test_parse_report_accepts_plain_text_bytes():
    text = read_fixture("sample_cbc_text.txt")
    data = TestClient(app).post("/parse-report", json={
        "report_id":"synthetic-cbc","report_name":"CBC Hemogram","date_sent":"19-May-2026",
        "content_type":"text/plain","pdf_base64":base64.b64encode(text.encode()).decode()
    }).json()
    assert data["report_type"] == "cbc"
    assert any(row["canonical_name"] == "Hb" for row in data["parameters"])
    assert data["raw_text_preview"] == ""

def test_session_expired_html_is_rejected():
    html = read_fixture("session_expired.html")
    data = TestClient(app).post("/parse-report", json={
        "report_id":"synthetic-session","report_name":"CBC","content_type":"text/html",
        "pdf_base64":base64.b64encode(html.encode()).decode()
    }).json()
    assert data["parameters"] == []
    assert data["errors"]
