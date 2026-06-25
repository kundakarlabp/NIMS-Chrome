import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const bridge = require("../../shared/nims-web/nimsAndroidFrameBridge.js");

function fakeCore(rows, template) {
  return {
    extractReportRows: () => rows,
    transientPayloadForRow: (row) => ({ ok: true, transientPrintReportArg: `arg-${row.row_index}` }),
    discoverSetPdfTemplate: () => template,
    clickFirstReportForMode: () => ({ ok: true })
  };
}

const genuine = { row_index: 0, onclick_function_name: "printReport", onclick_arg_count: 1 };
const notGenuine = { row_index: 1, onclick_function_name: "openSomething", onclick_arg_count: 2 };

test("buildFrameReport returns null for a frame with no genuine rows", () => {
  const core = fakeCore([notGenuine], { discovered: true, endpoint: "h/p" });
  assert.equal(bridge.buildFrameReport(core, {}, "h/p"), null);
});

test("buildFrameReport enriches genuine rows with the printReport argument", () => {
  const core = fakeCore([genuine, notGenuine], { discovered: true, endpoint: "h/p", origin: "https://x", pathname: "/p", modeParamName: "hmode", argumentParameterName: "fileName" });
  const report = bridge.buildFrameReport(core, {}, "host/path");
  assert.equal(report.type, "nims_report_frame");
  assert.equal(report.rowCount, 1);
  assert.equal(report.rows[0].transientPrintReportArg, "arg-0");
  assert.equal(report.template.endpoint, "h/p");
});

test("buildFrameReport omits an undiscovered template", () => {
  const core = fakeCore([genuine], null);
  const report = bridge.buildFrameReport(core, {}, "host/path");
  assert.equal(report.rowCount, 1);
  assert.equal(report.template, null);
});

test("frameReportKey changes when rows or template change (debounce)", () => {
  const a = bridge.buildFrameReport(fakeCore([genuine], null), {}, "h");
  const b = bridge.buildFrameReport(fakeCore([genuine], { discovered: true, endpoint: "h/p" }), {}, "h");
  assert.notEqual(bridge.frameReportKey(a), bridge.frameReportKey(b));
  assert.equal(bridge.frameReportKey(a), bridge.frameReportKey(a));
});
