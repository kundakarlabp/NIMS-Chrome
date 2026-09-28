(function (root) {
  "use strict";

  const REPORT_LIST_PATH = "/HBIMS/services/restful/invService/reportList";
  const DEFAULT_HOSPITAL_CODE = "33101";

  function clean(value) {
    return value == null ? "" : String(value).replace(/\s+/g, " ").trim();
  }

  function first(obj, names) {
    if (!obj || typeof obj !== "object") return "";
    for (const name of names) {
      if (Object.prototype.hasOwnProperty.call(obj, name)) {
        const value = clean(obj[name]);
        if (value && value.toLowerCase() !== "null") return value;
      }
    }
    const lower = new Map(Object.keys(obj).map(key => [key.toLowerCase(), key]));
    for (const name of names) {
      const key = lower.get(String(name).toLowerCase());
      if (key) {
        const value = clean(obj[key]);
        if (value && value.toLowerCase() !== "null") return value;
      }
    }
    return "";
  }

  function arrays(value, out) {
    if (Array.isArray(value)) {
      if (value.some(item => item && typeof item === "object" && !Array.isArray(item))) out.push(value);
      for (const item of value) arrays(item, out);
      return;
    }
    if (!value || typeof value !== "object") return;
    for (const child of Object.values(value)) arrays(child, out);
  }

  function stableId(row, index) {
    return first(row, ["reportId","report_id","requisitionNo","requisition_no","sampleNo","sample_no","labNo","lab_no","testCode","test_code"])
      || "api-report-" + index;
  }

  function safeReportUrl(raw) {
    const value = clean(raw);
    if (!value) return "";
    try {
      const url = new URL(value, "https://nimsts.edu.in");
      if (!/^(?:www\.)?nimsts\.edu\.in$/i.test(url.hostname)) return "";
      if (!/^https:$/.test(url.protocol)) return "";
      if (!/^\/(?:AHIMSG5|HISInvestigationG5|HIS|hislogin|HISUtilities|HBIMS)\//.test(url.pathname)) return "";
      return url.href;
    } catch {
      return "";
    }
  }

  function safeReportToken(raw) {
    const token = clean(raw);
    if (!token || token.length > 512) return "";
    if (token.includes("..") || token.includes("://") || /[\\/]/.test(token)) return "";
    for (let i = 0; i < token.length; i += 1) {
      const code = token.charCodeAt(i);
      if (code < 0x20 || code === 0x7f) return "";
    }
    return token;
  }

  function verifiedReportUrlForToken(raw) {
    const token = safeReportToken(raw);
    if (!token) return "";
    const url = new URL("/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt", "https://www.nimsts.edu.in");
    url.searchParams.set("hmode", "PRINTREPORT");
    url.searchParams.set("fileName", token);
    return url.href;
  }

  function normalizeRow(row, index) {
    const title = first(row, ["reportName","report_name","testName","test_name","investigationName","investigation_name","test","investigation"]);
    const date = first(row, ["reportDate","report_date","resultDate","result_date","dateSent","date_sent","sampleDate","sample_date","date"]);
    const department = first(row, ["department","departmentName","department_name","labName","lab_name","section"]);
    const rawUrl = first(row, ["reportUrl","report_url","pdfUrl","pdf_url","url","downloadUrl","download_url","reportLink","report_link"]);
    const directUrl = safeReportUrl(rawUrl);
    const token = safeReportToken(first(row, [
      "fileName","filename","file_name","reportToken","report_token","token",
      "reportFileName","report_file_name","pdfFileName","pdf_file_name"
    ])) || (!directUrl ? safeReportToken(rawUrl) : "");
    return {
      id: stableId(row, index),
      title: title || "Investigation report",
      date,
      department,
      url: directUrl,
      token
    };
  }

  function scoreArray(list) {
    if (!Array.isArray(list) || !list.length) return -1;
    let score = 0;
    for (const row of list.slice(0, 20)) {
      if (!row || typeof row !== "object" || Array.isArray(row)) continue;
      const keys = Object.keys(row).join(" ").toLowerCase();
      if (/report|investigation|test/.test(keys)) score += 3;
      if (/date|sample|requisition|lab/.test(keys)) score += 1;
      if (/url|filename|token/.test(keys)) score += 2;
    }
    return score;
  }

  function normalizeReportListPayload(payload, requestedCrNo) {
    const candidates = [];
    arrays(payload, candidates);
    if (Array.isArray(payload)) candidates.unshift(payload);
    const rowsSource = candidates.sort((a,b) => scoreArray(b) - scoreArray(a))[0] || [];
    const reports = rowsSource.map(normalizeRow).filter(row => row.title || row.url || row.token);
    const patientObject = payload && typeof payload === "object" && !Array.isArray(payload)
      ? (payload.patient || payload.patientDetails || payload.patient_details || payload.data || payload)
      : {};
    const patientObjectCr = first(patientObject, ["crNo","cr_no","crNumber","cr_number","patientCrNo","patient_cr_no","patCrNo","pat_cr_no"]);
    const returnedCrNo = patientObjectCr
      || first(rowsSource[0] || {}, ["crNo","cr_no","crNumber","cr_number","patientCrNo","patient_cr_no","patCrNo","pat_cr_no"]);
    const rowCrNos = rowsSource.map(row => first(row, ["crNo","cr_no","crNumber","cr_number","patientCrNo","patient_cr_no","patCrNo","pat_cr_no"]).replace(/\D/g, "")).filter(Boolean);
    const patientName = first(patientObject, ["patientName","patient_name","patName","pat_name"])
      || (patientObjectCr ? first(patientObject, ["name"]) : "")
      || first(rowsSource[0] || {}, ["patientName","patient_name","patName","pat_name"]);
    return {
      requestedCrNo,
      returnedCrNo: returnedCrNo.replace(/\D/g, ""),
      rowCrNos,
      patientName,
      reports,
      source: "nims_rest_report_list"
    };
  }

  function authLike(response, text) {
    const body = clean(text).toLowerCase();
    const finalUrl = clean(response && response.url).toLowerCase();
    return response && (response.status === 401 || response.status === 403)
      || /loginlogin\.action|session expired|please login again/.test(finalUrl + " " + body)
      || (/captcha/.test(body) && /password|user id|username/.test(body));
  }

  async function fetchReportList(crNo, fetchImpl, hosCode) {
    const normalizedCr = clean(crNo).replace(/\D/g, "");
    if (!/^\d{15}$/.test(normalizedCr)) throw new Error("Enter the 15-digit NIMS CR number.");
    const fetcher = fetchImpl || fetch;
    const url = new URL(REPORT_LIST_PATH, "https://nimsts.edu.in");
    url.searchParams.set("crNo", normalizedCr);
    url.searchParams.set("hosCode", clean(hosCode) || DEFAULT_HOSPITAL_CODE);
    const response = await fetcher(url.href, {
      method: "GET",
      credentials: "include",
      redirect: "follow",
      headers: { "Accept": "application/json,text/plain,*/*" }
    });
    const text = await response.text();
    if (authLike(response, text)) {
      const error = new Error("NIMS authentication required.");
      error.code = "NIMS_AUTH_REQUIRED";
      throw error;
    }
    if (!response.ok) throw new Error("NIMS report-list API returned status " + response.status + ".");
    let payload;
    try { payload = JSON.parse(text); }
    catch { throw new Error("NIMS report-list API did not return JSON."); }
    const normalized = normalizeReportListPayload(payload, normalizedCr);
    if ((normalized.returnedCrNo && normalized.returnedCrNo !== normalizedCr)
      || normalized.rowCrNos.some(rowCrNo => rowCrNo !== normalizedCr)) {
      const error = new Error("NIMS report-list API returned a different CR number.");
      error.code = "NIMS_IDENTITY_MISMATCH";
      throw error;
    }
    if (!normalized.returnedCrNo && !normalized.rowCrNos.length) {
      const error = new Error("NIMS report-list API did not confirm the requested CR number.");
      error.code = "NIMS_IDENTITY_UNVERIFIED";
      throw error;
    }
    if (!normalized.reports.length) throw new Error("NIMS report-list API returned no usable report rows.");
    return normalized;
  }

  function safeDiagnostics(normalized) {
    return {
      source: normalized && normalized.source || "nims_rest_report_list",
      reportCount: normalized && Array.isArray(normalized.reports) ? normalized.reports.length : 0,
      hasReturnedCr: Boolean(normalized && normalized.returnedCrNo),
      hasPatientName: Boolean(normalized && normalized.patientName),
      directUrlCount: normalized && Array.isArray(normalized.reports) ? normalized.reports.filter(r => r.url).length : 0,
      tokenCount: normalized && Array.isArray(normalized.reports) ? normalized.reports.filter(r => r.token).length : 0
    };
  }

  root.NimsRestApi = {
    REPORT_LIST_PATH,
    DEFAULT_HOSPITAL_CODE,
    normalizeReportListPayload,
    fetchReportList,
    safeDiagnostics,
    safeReportUrl,
    safeReportToken,
    verifiedReportUrlForToken
  };
})(typeof self !== "undefined" ? self : globalThis);
