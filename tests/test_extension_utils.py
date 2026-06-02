from __future__ import annotations

import json
import subprocess
import textwrap
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


def run_background_node(script: str) -> dict:
    background = (ROOT / "extension" / "src" / "background.js").read_text(encoding="utf-8")
    wrapped = f"""
    const vm = require('vm');
    const context = {{
      module: {{ exports: {{}} }},
      exports: {{}},
      console,
      URL,
      URLSearchParams,
      TextDecoder,
      TextEncoder,
      Uint8Array,
      setTimeout,
      clearTimeout,
      btoa: (value) => Buffer.from(value, 'binary').toString('base64'),
      chrome: {{
        runtime: {{ onInstalled: {{ addListener: () => {{}} }}, onMessage: {{ addListener: () => {{}} }} }},
        sidePanel: {{ setPanelBehavior: () => Promise.resolve() }},
        tabs: {{ onCreated: {{ addListener: () => {{}}, removeListener: () => {{}} }} }},
        webRequest: {{
          onBeforeRequest: {{ addListener: () => {{}}, removeListener: () => {{}} }},
          onHeadersReceived: {{ addListener: () => {{}}, removeListener: () => {{}} }},
          onCompleted: {{ addListener: () => {{}}, removeListener: () => {{}} }}
        }},
        webNavigation: {{ onCommitted: {{ addListener: () => {{}}, removeListener: () => {{}} }} }},
        storage: {{ local: {{ get: async () => ({{}}), set: async () => {{}}, remove: async () => {{}} }}, session: {{ get: async () => ({{}}), set: async () => {{}}, remove: async () => {{}} }} }}
      }}
    }};
    context.context = context;
    vm.createContext(context);
    vm.runInContext({json.dumps(background)}, context);
    vm.runInContext({json.dumps(script)}, context);
    """
    completed = subprocess.run(
        ["node"],
        cwd=ROOT,
        check=True,
        text=True,
        input=wrapped,
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
        "https://*.railway.app/*",
        "https://*.up.railway.app/*",
    }
    assert expected_hosts.issubset(set(manifest["host_permissions"]))
    assert "<all_urls>" not in json.dumps(manifest)
    assert {"webRequest", "webNavigation"}.issubset(set(manifest["permissions"]))
    assert set(manifest["host_permissions"]) == expected_hosts
    script = manifest["content_scripts"][0]
    assert script["all_frames"] is True
    assert script["js"] == ["src/contentUtils.js", "src/contentScript.js"]
    assert script["run_at"] == "document_idle"
    assert {
        "https://nimsts.edu.in/AHIMSG5/*",
        "https://www.nimsts.edu.in/AHIMSG5/*",
        "https://nimsts.edu.in/HISInvestigationG5/*",
        "https://www.nimsts.edu.in/HISInvestigationG5/*",
    } <= set(script["matches"])


def test_side_panel_buttons_and_frame_execution_present() -> None:
    html = (ROOT / "extension" / "src" / "sidepanel.html").read_text(encoding="utf-8")
    js = (ROOT / "extension" / "src" / "sidepanel.js").read_text(encoding="utf-8")
    for button_id in (
        "diagnosePage",
        "discoverMapping",
        "testDirectFetch",
        "runFast",
        "runCultures",
        "runFull",
        "clearMapping",
        "manualPopupFallback",
        "copyMappingDiagnostics",
        "copyDirectFetchDiagnostics",
        "saveHelperSettings",
        "testHelperConnection",
        "clearHelperSettings",
    ):
        assert f'id="{button_id}"' in html
    assert "Discover Mapping" in html
    assert "Test Direct Fetch" in html
    assert "Bulk Fast Summary" in html
    assert "Bulk Full Summary" in html
    assert "Manual Popup Fallback" in html
    assert "Copy Direct Fetch Diagnostics" in html
    assert "Remote Railway" in html
    assert "Helper API key" in html
    assert 'runSummaryFromBestFrame("test_direct")' in js
    assert 'runSummaryFromBestFrame("bulk_fast")' in js
    assert 'runSummaryFromBestFrame("bulk_full")' in js
    assert 'runSummaryFromBestFrame("manual_fallback")' in js
    assert "discoverMappingFromBestFrame" in js
    assert "clearDirectMapping" in js
    assert "allFrames: true" in js
    assert "frameIds: [best.frameId]" in js
    assert "collectFrameDiagnostic" in js
    assert "NIMS_HELPER_HEALTH" in js
    assert "Helper status" in js
    assert "copySafeMappingDiagnostics" in js
    assert "copyDirectFetchDiagnostics" in js
    assert "loadHelperSettings" in js
    assert "saveHelperSettings" in js


def test_side_panel_culture_columns_and_exports_present() -> None:
    js = (ROOT / "extension" / "src" / "sidepanel.js").read_text(encoding="utf-8")
    for label in (
        "Collection date",
        "Reporting date",
        "Culture no.",
        "Specimen no.",
        "Site/specimen",
        "Culture type",
        "Bottle/set",
        "Growth",
        "Organism",
        "Comment",
        "Sensitivity summary",
    ):
        assert label in js
    for field in (
        "culture_no",
        "specimen_no",
        "collection_date",
        "reporting_date",
        "culture_type",
        "bottle_set",
        "growth_quantity",
        "sensitivity_summary",
    ):
        assert field in js


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
    assert "Remote helper unauthorized. Check API key." in background
    assert "Set Railway helper URL first." in background
    assert "X-NIMS-HELPER-KEY" in background
    assert "nimsHelperSettings" in background
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
    assert "transient_print_report_arg" not in serialized


def test_fast_summary_selection_is_capped_and_test_first_selects_latest_cbc() -> None:
    script = r"""
    const utils = require('./extension/src/contentUtils.js');
    const rows = [];
    for (let i = 1; i <= 10; i += 1) rows.push({ date_sent: `${String(i).padStart(2, '0')}-May-2026`, report_name: `CBC ${i}`, report_tags: ['cbc'] });
    for (let i = 1; i <= 10; i += 1) rows.push({ date_sent: `${String(i).padStart(2, '0')}-May-2026`, report_name: `RFT LFT Electrolytes ${i}`, report_tags: ['rft', 'lft', 'electrolytes'] });
    for (let i = 1; i <= 30; i += 1) rows.push({ date_sent: `${String(i).padStart(2, '0')}-May-2026`, report_name: `Blood Culture ${i}`, report_tags: ['culture'] });
    const fast = utils.selectRowsForMode(rows, 'fast');
    const testFirst = utils.selectRowsForMode(rows, 'test_direct');
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
    assert 'mode === "manual_fallback"' in content_script
    assert "Opening report" in content_script
    assert "Parsing report" in content_script


def test_direct_bulk_static_contract_and_no_default_popup_fallback() -> None:
    background = (ROOT / "extension" / "src" / "background.js").read_text(encoding="utf-8")
    content_script = (ROOT / "extension" / "src" / "contentScript.js").read_text(encoding="utf-8")
    sidepanel_utils = (ROOT / "extension" / "src" / "sidepanelUtils.js").read_text(encoding="utf-8")
    assert "NIMS_DISCOVER_MAPPING" in background
    assert "chrome.webRequest.onBeforeRequest.addListener" in background
    assert "chrome.webRequest.onHeadersReceived.addListener" in background
    assert "chrome.webNavigation.onCommitted.addListener" in background
    assert "NIMS_FETCH_REPORT_DIRECT" in background
    assert "buildDirectRequest" in background
    assert "inferSetPdfMapping" in background
    assert "modeParameterName" in background
    assert "modeParameterValue" in background
    assert 'params.set(mapping.modeParameterName, mapping.modeParameterValue || "PRINTREPORT")' in background
    assert 'method: "POST"' in background
    assert 'method: "GET"' in background
    assert "Direct mapping not discovered. Click Discover Mapping first." in background
    assert "html_login_or_session" in background
    assert "Required dynamic form field missing in current NIMS page." in background
    assert "Direct fetch returned empty response." in background
    assert "Direct fetch returned unrecognized report candidate HTML." in background
    assert "chrome.storage.session" in background
    assert "safeHostPath" in background
    assert "runDirectBulk" in content_script
    assert "runTestDirectFetch" in content_script
    assert "NIMS_FETCH_REPORT_DIRECT" in content_script
    assert "runManualFallback" in content_script
    assert 'mode === "manual_fallback"' in content_script
    assert "captureReportByClick(row, sender)" not in content_script
    assert "transient_print_report_arg" in sidepanel_utils
    assert "print_report_arg" in sidepanel_utils
    assert "Direct report mapping is not validated. Run Discover Mapping, then Test Direct Fetch first." in content_script
    assert 'summary.status === "validated"' in content_script
    assert "summary.lastTestDirectFetch.ok === true" in content_script
    assert "summary.lastTestDirectFetch.parsed === true" in content_script
    assert 'mapping.status = mapping.validated ? "validated" : "failed"' in background
    assert "mapping.validated = Boolean(safe.ok && safe.parsed)" in background


def test_transient_printreport_arg_and_safe_report_key_static_contract() -> None:
    content_utils = (ROOT / "extension" / "src" / "contentUtils.js").read_text(encoding="utf-8")
    content_script = (ROOT / "extension" / "src" / "contentScript.js").read_text(encoding="utf-8")
    assert "parseFunctionArgs" in content_utils
    assert "getTransientPrintReportArg" in content_utils
    assert "getTransientReportRequestPayload" in content_utils
    assert "transient_print_report_arg" in content_utils
    assert "sanitizeState" in content_utils
    assert "print_report_arg" in content_utils
    assert "makeSafeReportKey" in content_script
    assert "crypto.subtle.digest" in content_script
    assert "report_key:" in content_script
    assert "Using cached result" in content_script
    assert "runQueue(misses, 3" in content_script
    assert "Math.min(Math.max(Number(concurrency) || 3, 1), 5)" in content_script


def test_direct_response_classifier_cases() -> None:
    script = r"""
    const c = context.module.exports.classifyReportResponse;
    const enc = new TextEncoder();
    const out = {
      pdf: c(enc.encode('%PDF-1.4 body'), 'application/pdf', 200, 'www.nimsts.edu.in/HISInvestigationG5/report').classification,
      empty: c(new ArrayBuffer(0), 'application/pdf', 200, 'www.nimsts.edu.in/HISInvestigationG5/report').classification,
      login: c(enc.encode('<html><input type="password"> session expired captcha</html>'), 'text/html', 200, 'www.nimsts.edu.in/HISInvestigationG5/report').classification,
      viewer: c(enc.encode('<html><iframe src="/HISInvestigationG5/report.pdf"></iframe><script>window.print()</script></html>'), 'text/html', 200, 'www.nimsts.edu.in/HISInvestigationG5/viewer').classification,
      duplicate: c(enc.encode('<html>Duplicate Result Report</html>'), 'text/html', 200, 'www.nimsts.edu.in/HISInvestigationG5/invDuplicateResultReportPrinting.cnt').classification,
      htmlContent: c(enc.encode('<html><table><tr><td>Hemoglobin</td><td>8.9</td><td>g/dL</td></tr></table></html>'), 'text/html', 200, 'www.nimsts.edu.in/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt').classification,
      setPdfGeneric: c(enc.encode('<html>Generated Report Page</html>'), 'text/html', 200, 'www.nimsts.edu.in/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt').classification,
      textReport: c(enc.encode('Hemoglobin 8.9 g/dL Platelet 150000 report'), 'text/plain', 200, 'www.nimsts.edu.in/HISInvestigationG5/report').classification,
      wrong: c(enc.encode('not found content that is long enough'), 'text/html', 404, 'www.nimsts.edu.in/HISInvestigationG5/missing').classification
    };
    console.log(JSON.stringify(out));
    """
    out = run_background_node(script)
    assert out == {
        "pdf": "pdf_report",
        "empty": "empty_response",
        "login": "html_login_or_session",
        "viewer": "html_report_viewer",
        "duplicate": "html_duplicate_report_page",
        "htmlContent": "html_report_content",
        "setPdfGeneric": "html_unrecognized_report_candidate",
        "textReport": "text_report",
        "wrong": "wrong_endpoint",
    }


def test_direct_diagnostics_are_safe_static_contract() -> None:
    background = (ROOT / "extension" / "src" / "background.js").read_text(encoding="utf-8")
    sidepanel = (ROOT / "extension" / "src" / "sidepanel.js").read_text(encoding="utf-8")
    assert "NIMS_GET_DIRECT_DIAGNOSTICS" in background
    assert "safeDiagnosticsForMapping" in background
    assert "safeRequestDiagnostic" in background
    assert "queryParamNames" in background
    assert "setPdfTemplateDiscovered" in background
    assert "reportModeParameterName" in background
    assert "reportArgumentParameterName" in background
    assert "postFieldNames" in background
    assert "responseSize" in background
    assert "toDirectFetchDiagnosticsText" in sidepanel
    assert "raw_text_preview" not in sidepanel
    assert "transient_print_report_arg" not in sidepanel


def test_setpdf_template_extraction_and_static_direct_request_contract() -> None:
    script = r"""
    const utils = require('./extension/src/contentUtils.js');
    globalThis.location = { href: 'https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt' };
    const doc = {
      querySelector: (selector) => selector === 'iframe#setPdf' ? {
        getAttribute: (name) => name === 'src' ? '/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt?hmode=PRINTREPORT&fileName=SECRET-FILE' : ''
      } : null
    };
    const template = utils.getSafeSetPdfTemplate(doc);
    console.log(JSON.stringify(template));
    """
    out = run_node(script)
    assert out["discovered"] is True
    assert out["endpoint"] == "www.nimsts.edu.in/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt"
    assert out["queryParamNames"] == ["hmode", "fileName"]
    assert out["modeParamName"] == "hmode"
    assert out["modeParamValue"] == "PRINTREPORT"
    assert out["argumentParameterName"] == "fileName"
    serialized = json.dumps(out)
    assert "SECRET-FILE" not in serialized

    background = (ROOT / "extension" / "src" / "background.js").read_text(encoding="utf-8")
    assert "fileName" in background
    assert "mode=PRINTREPORT" not in background


def test_shared_mobile_js_setpdf_template_and_selection() -> None:
    script = r"""
    const core = require('./shared/nims-web/nimsReportCore.js');
    globalThis.location = { href: 'https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt' };
    const doc = {
      querySelector: (selector) => selector === 'iframe#setPdf' ? {
        getAttribute: (name) => name === 'src' ? '/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt?hmode=PRINTREPORT&fileName=SECRET-FILE' : ''
      } : null,
      querySelectorAll: () => []
    };
    const template = core.discoverSetPdfTemplate(doc);
    const url = core.buildReportUrl(template, 'ABC 123');
    const selected = core.selectRowsForMode([
      { date_sent: '10-May-2026', report_name: 'CBC', report_tags: ['cbc'] },
      { date_sent: '11-May-2026', report_name: 'Blood Culture', report_tags: ['culture'] },
      { date_sent: '12-May-2026', report_name: 'RFT LFT Electrolytes', report_tags: ['rft', 'lft', 'electrolytes'] }
    ], 'bulk_fast');
    console.log(JSON.stringify({ template, url, selectedCount: selected.length }));
    """
    out = run_node(script)
    assert out["template"]["endpoint"] == "www.nimsts.edu.in/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt"
    assert out["template"]["queryParamNames"] == ["hmode", "fileName"]
    assert "hmode=PRINTREPORT" in out["url"]
    assert "fileName=ABC+123" in out["url"]
    assert "SECRET-FILE" not in json.dumps(out)
    assert out["selectedCount"] == 3


def test_android_project_static_security_contract() -> None:
    manifest = (ROOT / "mobile" / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
    main_activity = (ROOT / "mobile" / "android" / "app" / "src" / "main" / "java" / "org" / "kundakarlab" / "nimsfastsummarymobile" / "MainActivity.kt").read_text(encoding="utf-8")
    settings = (ROOT / "mobile" / "android" / "app" / "src" / "main" / "java" / "org" / "kundakarlab" / "nimsfastsummarymobile" / "SecureSettings.kt").read_text(encoding="utf-8")
    client = (ROOT / "mobile" / "android" / "app" / "src" / "main" / "java" / "org" / "kundakarlab" / "nimsfastsummarymobile" / "HelperApiClient.kt").read_text(encoding="utf-8")
    assert 'android:usesCleartextTraffic="false"' in manifest
    assert "javaScriptEnabled = true" in main_activity
    assert "allowFileAccess = false" in main_activity
    assert "allowUniversalAccessFromFileURLs = false" in main_activity
    assert "MIXED_CONTENT_COMPATIBILITY_MODE" in main_activity
    assert "MIXED_CONTENT_NEVER_ALLOW" not in main_activity
    assert "loginLogin.action" in main_activity
    assert "CookieManager.getInstance().getCookie" in main_activity
    assert "X-NIMS-HELPER-KEY" in client
    assert "AndroidKeyStore" in settings
    assert "username" not in main_activity.lower()
    assert "password" not in main_activity.lower()
    assert "raw response" not in client.lower()


def test_android_bulk_gating_queue_and_threading_contract() -> None:
    main_activity = (ROOT / "mobile" / "android" / "app" / "src" / "main" / "java" / "org" / "kundakarlab" / "nimsfastsummarymobile" / "MainActivity.kt").read_text(encoding="utf-8")
    queue = (ROOT / "mobile" / "android" / "app" / "src" / "main" / "java" / "org" / "kundakarlab" / "nimsfastsummarymobile" / "ReportFetchQueue.kt").read_text(encoding="utf-8")
    template = (ROOT / "mobile" / "android" / "app" / "src" / "main" / "java" / "org" / "kundakarlab" / "nimsfastsummarymobile" / "NimsReportTemplate.kt").read_text(encoding="utf-8")
    validator = (ROOT / "mobile" / "android" / "app" / "src" / "main" / "java" / "org" / "kundakarlab" / "nimsfastsummarymobile" / "HelperSettingsValidator.kt").read_text(encoding="utf-8")
    assert "private var mappingValidated = false" in main_activity
    assert "mappingValidated = false" in main_activity
    assert 'if (mode != "test_direct" && !mappingValidated)' in main_activity
    assert "Run Test One Report successfully before bulk summary." in main_activity
    assert "ReportFetchQueue(concurrency = 3)" in main_activity
    assert "concurrency.coerceIn(1, 5)" in queue
    assert "private var webViewUserAgent = \"\"" in main_activity
    assert "webViewUserAgent = webView.settings.userAgentString" in main_activity
    assert 'setRequestProperty("User-Agent", webViewUserAgent)' in main_activity
    assert "setRequestProperty(\"User-Agent\", webView.settings.userAgentString)" not in main_activity
    assert "Set Railway helper URL first." in validator
    assert "Set Railway helper API key first." in main_activity
    assert "responseCode >= 400" in main_activity
    assert "errorStream" in main_activity
    assert "ByteArrayOutputStream" in main_activity
    assert "raw" not in main_activity.lower()
    assert 'uri.scheme == "https"' in template
    assert 'uri.path.startsWith("/AHIMSG5/")' in template
    assert 'uri.path.startsWith("/HISInvestigationG5/")' in template
    assert "http://" not in template


def test_android_primary_summary_is_readable_not_raw_json_only() -> None:
    main_activity = (ROOT / "mobile" / "android" / "app" / "src" / "main" / "java" / "org" / "kundakarlab" / "nimsfastsummarymobile" / "MainActivity.kt").read_text(encoding="utf-8")
    formatter = (ROOT / "mobile" / "android" / "app" / "src" / "main" / "java" / "org" / "kundakarlab" / "nimsfastsummarymobile" / "ui" / "formatters" / "ClinicalSummaryFormatter.kt").read_text(encoding="utf-8")
    for section in ("NIMS Fast Summary", "Key labs", "Cultures", "Interpretation", "Physician note"):
        assert section in formatter
    assert "ReportsScreen(" in main_activity
    assert "TrendsScreen(" in main_activity
    assert "CulturesScreen(" in main_activity
    assert "SummaryScreen(" in main_activity
    assert "summary.toString(2)" not in main_activity


def test_android_gradle_wrapper_present() -> None:
    assert (ROOT / "mobile" / "android" / "gradlew").exists()
    assert (ROOT / "mobile" / "android" / "gradlew.bat").exists()
    assert (ROOT / "mobile" / "android" / "gradle" / "wrapper" / "gradle-wrapper.properties").exists()
    wrapper_jar = ROOT / "mobile" / "android" / "gradle" / "wrapper" / "gradle-wrapper.jar"
    assert wrapper_jar.exists()
    assert wrapper_jar.stat().st_size > 10_000
