import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync(new URL("../src/nimsRestApi.js", import.meta.url), "utf8");
const context = { URL, URLSearchParams, console };
context.self = context;
vm.createContext(context);
vm.runInContext(source, context);
const api = context.NimsRestApi;

test("normalizes nested report-list JSON without depending on one exact key casing", () => {
  const payload = {
    patientDetails: { patientName: "Synthetic Patient", crNo: "331012600000001" },
    data: { reportList: [
      { investigationName: "CBC", resultDate: "28/09/2026", reportUrl: "/HBIMS/report/cbc" },
      { test_name: "Blood Culture", report_date: "27/09/2026", file_name: "synthetic-token" }
    ]}
  };
  const out = api.normalizeReportListPayload(payload, "331012600000001");
  assert.equal(out.returnedCrNo, "331012600000001");
  assert.equal(out.reports.length, 2);
  assert.equal(out.reports[0].title, "CBC");
  assert.match(out.reports[0].url, /^https:\/\/nimsts\.edu\.in\//);
  assert.equal(out.reports[1].token, "synthetic-token");
});

test("rejects off-domain report URLs", () => {
  assert.equal(api.safeReportUrl("https://example.com/report.pdf"), "");
});

test("fetchReportList uses the fixed NIMS endpoint, credentials, CR and hospital code", async () => {
  let seen;
  const fakeFetch = async (url, options) => {
    seen = { url, options };
    return { ok: true, status: 200, url, text: async () => JSON.stringify([{ testName: "CBC", crNo: "331012600000001" }]) };
  };
  const out = await api.fetchReportList("331012600000001", fakeFetch);
  assert.equal(out.reports.length, 1);
  assert.match(seen.url, /\/HBIMS\/services\/restful\/invService\/reportList/);
  assert.match(seen.url, /crNo=331012600000001/);
  assert.match(seen.url, /hosCode=33101/);
  assert.equal(seen.options.credentials, "include");
});

test("fetchReportList fails closed on identity mismatch", async () => {
  const fakeFetch = async url => ({
    ok: true, status: 200, url,
    text: async () => JSON.stringify([{ testName: "CBC", crNo: "331012600000999" }])
  });
  await assert.rejects(() => api.fetchReportList("331012600000001", fakeFetch), /different CR number/);
});

test("diagnostics contain counts only and no identifiers or raw rows", () => {
  const normalized = api.normalizeReportListPayload([{ testName: "CBC", crNo: "331012600000001", fileName: "secret-token" }], "331012600000001");
  const diagnostic = api.safeDiagnostics(normalized);
  const text = JSON.stringify(diagnostic);
  assert.doesNotMatch(text, /331012600000001|secret-token/);
  assert.equal(diagnostic.reportCount, 1);
});
