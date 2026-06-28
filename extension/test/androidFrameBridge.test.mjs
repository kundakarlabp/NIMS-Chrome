import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const bridge = require("../../shared/nims-web/nimsAndroidFrameBridge.js");

function livePrintReport(name) {
  const mode = "PRINTREPORT";
  const url = "/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt?hmode=" + mode + "&fileName=" + name;
  AddRowToTableAddMoreValues(url);
}

function fakeDocument(printReport = livePrintReport) {
  return {
    location: { href: "https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt" },
    defaultView: { printReport },
    querySelectorAll: () => []
  };
}

function fakeUtils(rows, tokens = {}) {
  return {
    hasReportRows: () => rows.length > 0,
    extractReportRows: () => rows,
    safeRuntimeRow: (row) => ({
      row_index: row.row_index,
      view_report_button_index: row.view_report_button_index ?? row.row_index,
      date_sent: row.date_sent || "",
      department: row.department || "",
      report_name: row.report_name || "",
      report_type: row.report_type || "other",
      report_tags: row.report_tags || [],
      onclick_function_name: "printReport",
      onclick_arg_count: 1
    }),
    getTransientReportRequestPayload: (row) => ({
      ok: true,
      transient_print_report_arg: tokens[row.row_index] || ""
    })
  };
}

const rowA = { row_index: 0, report_name: "CBC", source_url: "sensitive", onclick: "sensitive" };
const rowB = { row_index: 1, report_name: "ESR", source_url: "sensitive", onclick: "sensitive" };
const tokenA = "123456_111111_20260628153000.pdf";
const tokenB = "123456_222222_20260628153100.pdf";

test("buildFrameReport returns null when the frame has no report rows", () => {
  assert.equal(bridge.buildFrameReport(fakeUtils([]), fakeDocument(), "www.nimsts.edu.in/path"), null);
});

test("buildFrameReport sends safe metadata, transient tokens, and verified GET template", () => {
  const report = bridge.buildFrameReport(
    fakeUtils([rowA, rowB], { 0: tokenA, 1: tokenB }),
    fakeDocument(),
    "www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt"
  );
  assert.equal(report.type, "nims_report_frame");
  assert.equal(report.rowCount, 2);
  assert.equal(report.rows[1].transientPrintReportArg, tokenB);
  assert.equal(report.rows[1].source_url, undefined);
  assert.equal(report.rows[1].onclick, undefined);
  assert.deepEqual(report.template, {
    origin: "https://www.nimsts.edu.in",
    pathname: "/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt",
    modeParamName: "hmode",
    modeParamValue: "PRINTREPORT",
    argumentParameterName: "fileName"
  });
});

test("buildFrameReport rejects unsafe or missing report tokens", () => {
  const report = bridge.buildFrameReport(
    fakeUtils([rowA, rowB], { 0: "../secret.pdf", 1: tokenB }),
    fakeDocument(),
    "host/path"
  );
  assert.equal(report.rowCount, 1);
  assert.equal(report.rows[0].transientPrintReportArg, tokenB);
  assert.equal(bridge.isSafeTransientToken("https://example.com/a.pdf"), false);
  assert.equal(bridge.isSafeTransientToken(tokenA), true);
});

test("buildFrameReport requires the verified live printReport implementation", () => {
  function unrelatedPrintReport(name) { return name; }
  const report = bridge.buildFrameReport(
    fakeUtils([rowA], { 0: tokenA }),
    fakeDocument(unrelatedPrintReport),
    "host/path"
  );
  assert.equal(report, null);
});

test("frameReportKey changes when transient report rows change", () => {
  const a = bridge.buildFrameReport(fakeUtils([rowA], { 0: tokenA }), fakeDocument(), "h");
  const b = bridge.buildFrameReport(fakeUtils([rowB], { 1: tokenB }), fakeDocument(), "h");
  assert.notEqual(bridge.frameReportKey(a), bridge.frameReportKey(b));
  assert.equal(bridge.frameReportKey(a), bridge.frameReportKey(a));
});
