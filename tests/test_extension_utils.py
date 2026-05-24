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
    assert "secret()" not in serialized
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


def test_manifest_includes_hisinvestigation_all_frames() -> None:
    manifest = json.loads((ROOT / "extension" / "manifest.json").read_text(encoding="utf-8"))
    expected_hosts = {
        "https://nimsts.edu.in/AHIMSG5/*",
        "https://www.nimsts.edu.in/AHIMSG5/*",
        "https://nimsts.edu.in/HISInvestigationG5/*",
        "https://www.nimsts.edu.in/HISInvestigationG5/*",
        "http://127.0.0.1:8765/*",
    }
    assert expected_hosts.issubset(set(manifest["host_permissions"]))
    script = manifest["content_scripts"][0]
    assert script["all_frames"] is True
    assert script["js"] == ["src/contentUtils.js", "src/contentScript.js"]
    assert script["run_at"] == "document_idle"
    assert expected_hosts - {"http://127.0.0.1:8765/*"} <= set(script["matches"])


def test_side_panel_buttons_and_frame_execution_present() -> None:
    html = (ROOT / "extension" / "src" / "sidepanel.html").read_text(encoding="utf-8")
    js = (ROOT / "extension" / "src" / "sidepanel.js").read_text(encoding="utf-8")
    for button_id in ("runFast", "runCultures", "runFull", "diagnosePage", "copyMappingDiagnostics"):
        assert f'id="{button_id}"' in html
    assert "allFrames: true" in js
    assert "frameIds: [best.frameId]" in js
    assert "collectFrameDiagnostic" in js
    assert "copySafeMappingDiagnostics" in js


def test_diagnostic_sanitization_and_best_frame_selection() -> None:
    script = r"""
    const utils = require('./extension/src/sidepanelUtils.js');
    const diag = utils.sanitizeDiagnosticResult({
      activeTabUrl: 'https://www.nimsts.edu.in/AHIMSG5/page?crno=123456&token=secret',
      frames: [
        {
          frameId: 1,
          url: 'https://www.nimsts.edu.in/AHIMSG5/menu?token=secret',
          title: 'Menu Patient Name: John Doe CR No: 123456',
          totalTr: 2,
          viewReportRows: 0,
          raw_row_text: 'Patient Name: John Doe CR No: 123456',
          onclick: 'openReport(secret)',
          source_url: 'https://secret/report?token=secret'
        },
        {
          frameId: 7,
          url: 'https://www.nimsts.edu.in/HISInvestigationG5/report?crno=123456&token=secret',
          title: 'Cr No Wise Result Report Printing New_iframe',
          totalTr: 20,
          viewReportRows: 9,
          rowPreviews: [
            {
              date_sent: '19-May-2026',
              report_name: 'CBC Patient Name: John Doe',
              department: 'Biochemistry',
              hasHref: true,
              hasOnclick: true,
              postWorkflowSuspected: false,
              raw_row_text: 'CR No: 123456',
              onclick: 'secret()'
            }
          ]
        }
      ]
    });
    const best = utils.selectBestFrameDiagnostic(diag.frames);
    console.log(JSON.stringify({ diag, best }));
    """
    out = run_node(script)
    serialized = json.dumps(out)
    assert out["diag"]["activeTabUrl"] == "www.nimsts.edu.in/AHIMSG5/page"
    assert out["diag"]["frames"][1]["url"] == "www.nimsts.edu.in/HISInvestigationG5/report"
    assert out["best"]["frameId"] == 7
    assert "token=secret" not in serialized
    assert "raw_row_text" not in serialized
    assert "onclick" not in serialized
    assert "source_url" not in serialized
    assert "CR No: 123456" not in serialized
    assert "Patient Name: John Doe" not in serialized


def test_global_post_form_with_onclick_is_not_skipped_and_onclick_diagnostics_are_safe() -> None:
    script = r"""
    const utils = require('./extension/src/contentUtils.js');
    const form = { getAttribute: (name) => name === 'method' ? 'post' : '' };
    const onclickNode = { getAttribute: () => "viewReport('123456','LAB-SECRET-99','abc',42)" };
    const rowWithOnclick = {
      querySelectorAll: (selector) => selector === '[onclick]' ? [onclickNode] : [],
      querySelector: () => null,
      closest: () => form,
      outerHTML: '<tr><input type="hidden" name="secret" value="CR123"></tr>',
      getAttribute: () => ''
    };
    const rowWithoutOnclick = {
      querySelectorAll: () => [],
      querySelector: () => null,
      closest: () => form,
      outerHTML: '<tr><input type="submit"></tr>',
      getAttribute: () => ''
    };
    const diag = utils.analyzeOnclickPattern("viewReport('123456','LAB-SECRET-99','abc',42)");
    const sanitized = utils.sanitizeState({
      mode: 'fast',
      rows: [{
        date_sent: '19-May-2026',
        report_name: 'CBC',
        onclick_present: true,
        onclick_parse_status: 'needs_mapping',
        onclick_diagnostics: diag,
        global_form_present: true,
        post_workflow: false,
        nearby_input_names: ['hmode', 'selectedLabNo'],
        onclick: "viewReport('123456','LAB-SECRET-99')",
        raw_row_text: 'Patient Name: Test CR No: 123456',
        source_url: 'https://secret/path?token=abc'
      }],
      selected: [],
      parsedReports: [],
      result: null
    }, false);
    console.log(JSON.stringify({
      onclickPost: utils.detectPostWorkflow(rowWithOnclick),
      formOnlyPost: utils.detectPostWorkflow(rowWithoutOnclick),
      diag,
      sanitized
    }));
    """
    out = run_node(script)
    serialized = json.dumps(out)
    assert out["onclickPost"] is False
    assert out["formOnlyPost"] is True
    assert out["diag"]["onclickPattern"] == "viewReport(args: 4)"
    assert out["diag"]["argumentCount"] == 4
    assert out["diag"]["argumentKinds"] == ["number_like", "sample_like", "unknown", "number_like"]
    assert "123456" not in serialized
    assert "LAB-SECRET-99" not in serialized
    assert "raw_row_text" not in serialized
    assert "source_url" not in serialized
    assert "viewReport('123456'" not in serialized
    assert out["sanitized"]["rows"][0]["onclick_present"] is True
    assert out["sanitized"]["rows"][0]["post_workflow"] is False


def test_fast_summary_small_live_selection_limits() -> None:
    script = r"""
    const utils = require('./extension/src/contentUtils.js');
    const rows = [];
    for (let i = 1; i <= 6; i++) rows.push({ date_sent: `0${i}-05-2026`, report_name: `CBC ${i}`, report_tags: ['cbc'] });
    for (let i = 1; i <= 6; i++) rows.push({ date_sent: `1${i}-05-2026`, report_name: `RFT LFT Electrolytes ${i}`, report_tags: ['rft', 'electrolytes', 'lft'] });
    rows.push({ date_sent: '01-05-2026', report_name: 'Blood Culture', report_tags: ['culture'] });
    const selected = utils.selectRowsForMode(rows, 'fast');
    console.log(JSON.stringify({
      selected: selected.map((row) => row.report_name),
      cbc: selected.filter((row) => row.report_tags.includes('cbc')).length,
      chemistry: selected.filter((row) => row.report_tags.includes('rft')).length,
      culture: selected.filter((row) => row.report_tags.includes('culture')).length
    }));
    """
    out = run_node(script)
    assert out["cbc"] == 3
    assert out["chemistry"] == 3
    assert out["culture"] == 1
