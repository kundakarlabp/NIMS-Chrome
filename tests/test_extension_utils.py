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
    for button_id in ("testFirstReport", "runFast", "runCultures", "runFull", "diagnosePage", "copyMappingDiagnostics"):
        assert f'id="{button_id}"' in html
    assert "Test First Report" in html
    assert 'runSummaryFromBestFrame("test_first")' in js
    assert "allFrames: true" in js
    assert "frameIds: [best.frameId]" in js
    assert "collectFrameDiagnostic" in js
    assert "NIMS_HELPER_HEALTH" in js
    assert "Helper status" in js
    assert "copySafeMappingDiagnostics" in js


def test_background_helper_routes_and_content_script_avoids_localhost_fetch() -> None:
    background = (ROOT / "extension" / "src" / "background.js").read_text(encoding="utf-8")
    content_script = (ROOT / "extension" / "src" / "contentScript.js").read_text(encoding="utf-8")
    for message_type in (
        "NIMS_HELPER_HEALTH",
        "NIMS_HELPER_PARSE_REPORT",
        "NIMS_HELPER_SUMMARIZE",
        "NIMS_HELPER_CLEAR_CACHE",
    ):
        assert message_type in background
    assert "async function callHelper" in background
    assert "Local helper is not reachable at 127.0.0.1:8765" in background
    assert "NIMS_HELPER_PARSE_REPORT" in content_script
    assert "NIMS_HELPER_SUMMARIZE" in content_script
    assert "http://127.0.0.1:8765" not in content_script
    assert "Failed to fetch" not in content_script


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


def test_global_post_form_with_onclick_is_not_unsupported_and_sanitized() -> None:
    script = r"""
    const utils = require('./extension/src/contentUtils.js');
    const form = {
      getAttribute: (name) => name === 'method' ? 'post' : '',
      querySelectorAll: () => [
        { getAttribute: (name) => name === 'name' ? 'hmode' : 'SECRET_VALUE' },
        { getAttribute: (name) => name === 'name' ? 'selectedLabNo' : 'LAB_SECRET' }
      ]
    };
    const clickNode = { getAttribute: () => "viewReport('123456','LAB-SECRET-99','abc')" };
    const row = {
      querySelectorAll: (selector) => selector === '[onclick]' ? [clickNode] : [],
      querySelector: () => null,
      closest: () => form,
      outerHTML: '<tr><input type=\"hidden\" name=\"selectedLabNo\" value=\"LAB_SECRET\"></tr>',
      getAttribute: () => ''
    };
    const info = utils.extractUrlFromNode(row, 'https://nimsts.edu.in/HISInvestigationG5/page');
    const sanitized = utils.sanitizeState({
      mode: 'fast',
      rows: [{
        date_sent: '19-May-2026',
        report_name: 'CBC',
        onclick: "viewReport('123456','LAB-SECRET-99','abc')",
        raw_row_text: 'Patient Name Test CR No: 123456',
        ...info
      }],
      selected: [],
      parsedReports: [],
      result: null
    }, false);
    console.log(JSON.stringify({ info, sanitized }));
    """
    out = run_node(script)
    serialized = json.dumps(out["sanitized"])
    assert out["info"]["onclick_present"] is True
    assert out["info"]["global_form_present"] is True
    assert out["info"]["form_method"] == "post"
    assert out["info"]["post_workflow"] is False
    assert out["info"]["unsupported_post_only"] is False
    assert out["info"]["onclick_function_name"] == "viewReport"
    assert out["info"]["onclick_arg_count"] == 3
    assert out["info"]["onclick_parse_status"] == "function_detected"
    assert "123456" not in serialized
    assert "LAB-SECRET-99" not in serialized
    assert "LAB_SECRET" not in serialized
    assert "raw_row_text" not in serialized
    assert "hmode" in serialized
    assert "selectedLabNo" in serialized


def test_printreport_rows_have_safe_locator_and_are_not_failed_immediately() -> None:
    script = r"""
    const utils = require('./extension/src/contentUtils.js');
    globalThis.getComputedStyle = () => ({ display: 'table-row', visibility: 'visible' });
    const button = {
      value: '',
      getAttribute: (name) => name === 'onclick' ? "printReport('SECRET-ARG')" : '',
      innerText: 'View Report',
      textContent: 'View Report',
      hidden: false
    };
    const row = {
      cells: [{ innerText: '24-May-2026', textContent: '24-May-2026' }, { innerText: 'CBC', textContent: 'CBC' }, { innerText: 'View Report', textContent: 'View Report' }],
      querySelector: () => null,
      querySelectorAll: (selector) => selector === '[onclick]' ? [button] : [button],
      closest: () => ({ getAttribute: (name) => name === 'method' ? 'post' : '', querySelectorAll: () => [] }),
      innerText: '24-May-2026 CBC View Report',
      textContent: '24-May-2026 CBC View Report',
      hidden: false
    };
    const doc = {
      querySelectorAll: (selector) => selector === 'tr' ? [row] : [button]
    };
    const rows = utils.extractReportRows(doc, 'https://www.nimsts.edu.in/HISInvestigationG5/page');
    const sanitized = utils.sanitizeState({ mode: 'fast', rows, selected: rows, parsedReports: [], result: null }, false);
    console.log(JSON.stringify({ row: rows[0], sanitized }));
    """
    out = run_node(script)
    assert out["row"]["onclick_function_name"] == "printReport"
    assert out["row"]["onclick_arg_count"] == 1
    assert out["row"]["onclick_parse_status"] == "function_detected"
    assert out["row"]["status"] == "ready"
    assert out["row"]["row_index"] == 0
    assert out["row"]["view_report_button_index"] == 0
    serialized = json.dumps(out["sanitized"])
    assert "SECRET-ARG" not in serialized
    assert "printReport('SECRET-ARG')" not in serialized


def test_fast_summary_selection_is_capped_and_test_first_selects_latest_cbc() -> None:
    script = r"""
    const utils = require('./extension/src/contentUtils.js');
    const rows = [];
    for (let i = 1; i <= 10; i += 1) rows.push({ date_sent: `${String(i).padStart(2, '0')}-May-2026`, report_name: `CBC ${i}`, report_tags: ['cbc'] });
    for (let i = 1; i <= 10; i += 1) rows.push({ date_sent: `${String(i).padStart(2, '0')}-May-2026`, report_name: `RFT LFT Electrolytes ${i}`, report_tags: ['rft', 'lft', 'electrolytes'] });
    for (let i = 1; i <= 30; i += 1) rows.push({ date_sent: `${String(i).padStart(2, '0')}-May-2026`, report_name: `Blood Culture ${i}`, report_tags: ['culture'] });
    const fast = utils.selectRowsForMode(rows, 'fast');
    const testFirst = utils.selectRowsForMode(rows, 'test_first');
    console.log(JSON.stringify({ fastCount: fast.length, cbcCount: fast.filter(r => r.report_tags.includes('cbc')).length, rleCount: fast.filter(r => r.report_tags.includes('rft')).length, testFirst }));
    """
    out = run_node(script)
    assert out["fastCount"] <= 20
    assert out["cbcCount"] <= 3
    assert out["rleCount"] <= 3
    assert len(out["testFirst"]) == 1
    assert out["testFirst"][0]["report_name"] == "CBC 10"


def test_background_printreport_click_capture_static_contract() -> None:
    background = (ROOT / "extension" / "src" / "background.js").read_text(encoding="utf-8")
    content_script = (ROOT / "extension" / "src" / "contentScript.js").read_text(encoding="utf-8")
    assert "isSupportedPrintReportRow" in background
    assert 'row.onclick_function_name === "printReport"' in background
    assert "captureReportByClick(row, sender)" in background
    assert "chrome.tabs.onCreated.addListener" in background
    assert "chrome.tabs.onUpdated.addListener" in background
    assert 'world: "MAIN"' in background
    assert "No View Report button found for row" in background
    assert "printReport did not open a popup/tab" in background
    assert "Report popup opened but content could not be fetched" in background
    assert "Session expired or login page returned" in background
    assert "Unable to capture NIMS printReport output" in background
    assert "NIMS onclick/form workflow needs specific mapping" in content_script
    assert "Opening report" in content_script
    assert "Parsing report" in content_script
