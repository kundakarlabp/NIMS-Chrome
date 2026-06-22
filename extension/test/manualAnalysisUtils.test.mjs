import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const utils = require("../src/manualAnalysisUtils.js");

test("accepts only genuine one-argument printReport rows", () => {
  assert.equal(utils.isGenuineReportRow({ onclick_function_name: "printReport", onclick_arg_count: 1, onclick_parse_status: "function_detected" }), true);
  assert.equal(utils.isGenuineReportRow({ onclick_function_name: "printReport", onclick_arg_count: 2 }), false);
  assert.equal(utils.isGenuineReportRow({ onclick_function_name: "openReport", onclick_arg_count: 1 }), false);
});

test("exact visible result iframe wins even when another frame has more rows", () => {
  const selected = utils.selectResultsFrame([
    { frameId: 9, ready: true, visible: true, exactResultFrame: false, rowCount: 25 },
    { frameId: 4, ready: true, visible: true, exactResultFrame: true, rowCount: 3 }
  ]);
  assert.equal(selected.frameId, 4);
});

test("hidden frame loses to a visible result frame", () => {
  const selected = utils.selectResultsFrame([
    { frameId: 1, ready: true, visible: false, exactResultFrame: false, rowCount: 99 },
    { frameId: 2, ready: true, visible: true, exactResultFrame: false, rowCount: 4 }
  ]);
  assert.equal(selected.frameId, 2);
});

test("returns null when no frame contains genuine rows", () => {
  assert.equal(utils.selectResultsFrame([{ frameId: 1, ready: false, rowCount: 0 }]), null);
});
