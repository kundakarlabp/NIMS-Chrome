from __future__ import annotations

import json
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def run_node(script: str) -> dict:
    completed = subprocess.run(
        ["node", "-e", script],
        cwd=ROOT,
        check=True,
        text=True,
        capture_output=True,
    )
    return json.loads(completed.stdout)


def test_url_extraction_date_sorting_tags_and_sanitization() -> None:
    script = r"""
    const utils = require('./extension/src/contentUtils.js');
    const base = 'https://nimsts.edu.in/AHIMSG5/result/list';
    const out = {
      windowOpen: utils.parseUrlFromOnclick("window.open('/AHIMSG5/path?id=LAB123')", base),
      openReport: utils.parseUrlFromOnclick("openReport('relative/report?id=ABC')", base),
      dataUrl: utils.parseUrlFromOnclick("window.open('data:text/plain;base64,SGk=')", base),
      tags: utils.inferReportTags('RFT Electrolytes LFT'),
      dates: ['19-May-2026', '19/05/2026', '19-05-2026', '2026-05-19'].map(utils.parseDateValue),
      selected: utils.selectRowsForMode([
        { date_sent: '14-May-2026', report_name: 'CBC', report_tags: ['cbc'] },
        { date_sent: '19-May-2026', report_name: 'CBC', report_tags: ['cbc'] },
        { date_sent: '16-May-2026', report_name: 'RFT Electrolytes LFT', report_tags: ['rft', 'electrolytes', 'lft'] },
        { date_sent: '', report_name: 'Blood Culture', report_tags: ['culture'] }
      ], 'fast').map(r => r.report_name + ':' + r.date_sent),
      sanitized: utils.sanitizeState({
        mode: 'fast',
        rows: [{ date_sent: '19-May-2026', report_name: 'CBC', raw_row_text: 'Patient Name X CR 123', onclick: 'secret()', href: 'https://secret', source_url: 'https://secret', report_id: 'row-1' }],
        selected: [],
        parsedReports: [{ report_id: 'row-1', report_name: 'CBC', raw_text_preview: 'Name X', parameters: [] }],
        result: { summary: { source_url: 'https://secret', raw_text_preview: 'Name X', ok: true } }
      }, false)
    };
    console.log(JSON.stringify(out));
    """
    out = run_node(script)
    assert out["windowOpen"] == "https://nimsts.edu.in/AHIMSG5/path?id=LAB123"
    assert out["openReport"] == "https://nimsts.edu.in/AHIMSG5/result/relative/report?id=ABC"
    assert out["dataUrl"].startswith("data:text/plain")
    assert out["tags"] == ["rft", "electrolytes", "lft"]
    assert len(set(out["dates"])) == 1
    assert out["selected"][0] == "CBC:19-May-2026"
    serialized = json.dumps(out["sanitized"])
    assert "raw_row_text" not in serialized
    assert "source_url" not in serialized
    assert "onclick" not in serialized
    assert "https://secret" not in serialized
    assert "row-1" not in serialized


def test_dynamic_toolbar_scaffolding_present() -> None:
    content_script = (ROOT / "extension" / "src" / "contentScript.js").read_text(encoding="utf-8")
    delayed_page = (ROOT / "extension" / "test_pages" / "delayed_mock_report_list.html").read_text(encoding="utf-8")
    assert "MutationObserver" in content_script
    assert "setInterval" in content_script
    assert "nims-fast-summary-toolbar" in content_script
    assert "setTimeout" in delayed_page
    assert "View Report" in delayed_page
