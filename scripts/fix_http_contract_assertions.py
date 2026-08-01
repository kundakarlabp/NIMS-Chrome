from pathlib import Path

path = Path("tests/test_extension_utils.py")
text = path.read_text(encoding="utf-8")
old = '''    assert "Set Railway helper URL first." in validator
    assert "Configure Railway helper for PDF and unsupported reports." in main_activity
    assert "responseCode >= 400" in main_activity
    assert "errorStream" in main_activity
    assert "ByteArrayOutputStream" in main_activity
'''
new = '''    assert "Set Railway helper URL first." in validator
    assert "Configure Railway helper for PDF and unsupported reports." in main_activity
    http_client = (ROOT / "mobile" / "android" / "app" / "src" / "main" / "java" / "org" / "kundakarlab" / "nimsfastsummarymobile" / "NimsReportHttpClient.kt").read_text(encoding="utf-8")
    assert "if (!response.isSuccessful)" in http_client
    assert "response.code" in http_client
    assert "body.source()" in http_client
    assert "total > maxBytes" in http_client
'''
if text.count(old) != 1:
    raise RuntimeError(f"expected one stale HTTP contract block, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
