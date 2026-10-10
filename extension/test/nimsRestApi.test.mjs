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


test("verified report token resolves only through the fixed NIMS report endpoint", () => {
  const url = api.verifiedReportUrlForToken("SAFE_FIXTURE_ARG");
  assert.match(url, /^https:\/\/www\.nimsts\.edu\.in\/HISInvestigationG5\/new_investigation\/invDuplicateResultReportPrinting\.cnt\?/);
  assert.match(url, /hmode=PRINTREPORT/);
  assert.match(url, /fileName=SAFE_FIXTURE_ARG/);
});

test("unsafe report tokens and off-contract NIMS paths are rejected", () => {
  assert.equal(api.verifiedReportUrlForToken("../secret"), "");
  assert.equal(api.verifiedReportUrlForToken("https://example.com/a"), "");
  assert.equal(api.safeReportUrl("https://nimsts.edu.in/not-approved/report.pdf"), "");
});


test("token-shaped reportUrl is treated as a safe opaque report token, not a URL", () => {
  const out = api.normalizeReportListPayload(
    [{ testName: "CBC", reportUrl: "SAFE_FIXTURE_ARG", crNo: "331012600000001" }],
    "331012600000001"
  );
  assert.equal(out.reports[0].url, "");
  assert.equal(out.reports[0].token, "SAFE_FIXTURE_ARG");
  assert.equal(Object.prototype.hasOwnProperty.call(out.reports[0], "raw"), false);
});
