import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const bridge = require("../../shared/nims-web/nimsAndroidFrameBridge.js");

// Fake NimsFastSummaryUtils (the extension's contentUtils API surface the bridge uses).
function fakeUtils(rows) {
  return {
    hasReportRows: () => rows.length > 0,
    extractReportRows: () => rows
  };
}

const rowA = { row_index: 0, report_name: "CBC", source_url: "https://nimsts.edu.in/r/AAA" };
const rowB = { row_index: 1, report_name: "ESR", source_url: "https://nimsts.edu.in/r/BBB" };

test("buildFrameReport returns null when the frame has no report rows", () => {
  assert.equal(bridge.buildFrameReport(fakeUtils([]), {}, "h/p"), null);
});

test("buildFrameReport returns the rows the extension extracted (with source_url)", () => {
  const report = bridge.buildFrameReport(fakeUtils([rowA, rowB]), {}, "host/path");
  assert.equal(report.type, "nims_report_frame");
  assert.equal(report.rowCount, 2);
  assert.equal(report.rows[1].source_url, "https://nimsts.edu.in/r/BBB");
});

test("frameReportKey changes when the patient's rows change (debounce)", () => {
  const a = bridge.buildFrameReport(fakeUtils([rowA]), {}, "h");
  const b = bridge.buildFrameReport(fakeUtils([rowB]), {}, "h");
  assert.notEqual(bridge.frameReportKey(a), bridge.frameReportKey(b));
  assert.equal(bridge.frameReportKey(a), bridge.frameReportKey(a));
});

test("buildFrameReport tolerates a utils without hasReportRows", () => {
  const report = bridge.buildFrameReport({ extractReportRows: () => [rowA] }, {}, "h");
  assert.equal(report.rowCount, 1);
});
