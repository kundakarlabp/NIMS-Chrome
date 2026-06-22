import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
globalThis.NimsManualAnalysisUtils = require("../src/manualAnalysisUtils.js");

let mappingValidated = false;
const calls = [];
globalThis.chrome = {
  runtime: {
    sendMessage: async (message) => {
      calls.push(message.type);
      if (message.type === "NIMS_HELPER_HEALTH") return { ok: true };
      if (message.type === "NIMS_GET_MAPPING_SUMMARY") {
        return mappingValidated
          ? { ok: true, summary: { status: "validated", lastTestDirectFetch: { ok: true, parsed: true } } }
          : { ok: true, summary: { status: "candidate" } };
      }
      return { ok: true };
    }
  }
};

globalThis.NimsFastSummary = {
  extractReportRows: () => [{ onclick_function_name: "printReport", onclick_arg_count: 1, onclick_parse_status: "function_detected" }],
  clearMapping: async () => { calls.push("clearMapping"); mappingValidated = false; },
  discoverMapping: async () => { calls.push("discoverMapping"); return { ok: true }; },
  runSummary: async (mode) => {
    calls.push(`runSummary:${mode}`);
    if (mode === "test_direct") mappingValidated = true;
  }
};

require("../src/manualAnalysis.js");

test("one click performs discovery, validation and requested analysis", async () => {
  calls.length = 0;
  mappingValidated = false;
  const result = await globalThis.NimsManualAnalysis.analyze("bulk_fast");
  assert.equal(result.ok, true);
  assert.equal(result.rowCount, 1);
  assert.ok(calls.includes("clearMapping"));
  assert.ok(calls.includes("discoverMapping"));
  assert.ok(calls.includes("runSummary:test_direct"));
  assert.ok(calls.includes("runSummary:bulk_fast"));
  assert.ok(calls.indexOf("runSummary:test_direct") < calls.indexOf("runSummary:bulk_fast"));
});

test("analysis fails closed when visible genuine rows are absent", async () => {
  const original = globalThis.NimsFastSummary.extractReportRows;
  globalThis.NimsFastSummary.extractReportRows = () => [];
  const result = await globalThis.NimsManualAnalysis.analyze("bulk_fast");
  globalThis.NimsFastSummary.extractReportRows = original;
  assert.equal(result.ok, false);
  assert.match(result.error, /No visible report-result rows/);
});
