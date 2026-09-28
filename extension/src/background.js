if (typeof importScripts === 'function') importScripts('nimsRestApi.js');

const DASHBOARD_URL_PATTERN = 'https://nims-results-cockpit-ne5nig.v2.appdeploy.ai/*';
const NIMS_LOGIN_URL = 'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action';
const MAX_REPORT_BYTES = 12 * 1024 * 1024;
const NIMS_TAB_PATTERNS = [
  'https://nimsts.edu.in/AHIMSG5/*',
  'https://www.nimsts.edu.in/AHIMSG5/*',
  'https://nimsts.edu.in/HISInvestigationG5/*',
  'https://www.nimsts.edu.in/HISInvestigationG5/*',
  'https://nimsts.edu.in/hislogin/*',
  'https://www.nimsts.edu.in/hislogin/*'
];

let dashboardLoginWindowId = null;
let dashboardHostWindowId = null;
let dashboardWorkerTabId = null;

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function arrayBufferToBase64(buffer) {
  let binary = '';
  const bytes = new Uint8Array(buffer);
  for (let index = 0; index < bytes.byteLength; index += 1) {
    binary += String.fromCharCode(bytes[index]);
  }
  return btoa(binary);
}

function hasReportValues(text) {
  return /\b(?:result|hemoglobin|haemoglobin|wbc|tlc|platelet|creatinine|urea|sodium|potassium|bilirubin|sgot|sgpt|crp|procalcitonin|culture|organism|sensitive|resistant|no growth)\b/i.test(text)
    && /\d/.test(text);
}

function classifyReportResponse(buffer, contentType, status) {
  const byteLength = buffer ? buffer.byteLength : 0;
  const type = String(contentType || '').toLowerCase();
  if (!buffer || byteLength === 0) return 'empty_response';

  const bytes = new Uint8Array(buffer.slice(0, 5));
  const startsPdf = bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 && bytes[3] === 0x46;
  if (type.includes('application/pdf') || startsPdf) return 'pdf_report';

  if (!type.includes('text/html') && !type.includes('text/plain')) {
    return 'unsupported_content_type';
  }

  const text = new TextDecoder('utf-8').decode(buffer.slice(0, 50000)).toLowerCase();
  if (/\b(login|session expired|session has expired|authentication|captcha|otp|sign in|password)\b/.test(text)) {
    return 'html_login_or_session';
  }
  if (/duplicate\s+result\s+report/.test(text)) return 'html_duplicate_report_page';
  if (/<(?:iframe|embed|object)\b|pdfviewer|viewer|window\.print/.test(text) && !hasReportValues(text)) {
    return 'html_report_viewer';
  }
  if (type.includes('text/plain') || hasReportValues(text)) {
    return type.includes('text/html') ? 'html_report_content' : 'text_report';
  }
  if ([404, 405, 500].includes(Number(status))) return 'wrong_endpoint';
  return 'html_unrecognized_report_candidate';
}

function classificationError(classification) {
  if (['pdf_report', 'text_report', 'html_report_content'].includes(classification)) return '';
  if (classification === 'html_login_or_session') return 'NIMS session expired or authentication is required.';
  if (classification === 'empty_response') return 'NIMS returned an empty report response.';
  if (classification === 'html_report_viewer') return 'NIMS returned a report viewer instead of the report content.';
  if (classification === 'html_duplicate_report_page') return 'NIMS returned the duplicate-report page instead of the report content.';
  if (classification === 'wrong_endpoint') return 'NIMS returned the wrong report endpoint.';
  return 'NIMS returned unsupported report content.';
}

function makeError(message, code = '') {
  const error = new Error(message);
  if (code) error.code = code;
  return error;
}

async function pushDashboardEvent(eventType, payload) {
  const tabs = await chrome.tabs.query({ url: [DASHBOARD_URL_PATTERN] }).catch(() => []);
  await Promise.all(
    tabs.map(tab => (
      tab.id
        ? chrome.tabs.sendMessage(tab.id, {
            type: 'NIMS_DASHBOARD_EVENT',
            eventType,
            payload: payload || {}
          }).catch(() => {})
        : Promise.resolve()
    ))
  );
}

async function handleDashboardSessionState(state, sender) {
  const tab = sender && sender.tab;
  if (!tab || !tab.id || !state || !state.authenticated) return;

  dashboardWorkerTabId = tab.id;
  if (dashboardLoginWindowId && tab.windowId === dashboardLoginWindowId && dashboardHostWindowId) {
    try {
      await chrome.tabs.move(tab.id, { windowId: dashboardHostWindowId, index: -1 });
      await chrome.tabs.update(tab.id, { active: false });
    } catch {}
    try {
      await chrome.windows.remove(dashboardLoginWindowId);
    } catch {}
    dashboardLoginWindowId = null;
  }

  await pushDashboardEvent('KBP_NIMS_STATUS', {
    ok: true,
    loggedIn: true,
    state: 'logged_in'
  });
}

async function frameIdsForTab(tabId) {
  const frames = await chrome.webNavigation.getAllFrames({ tabId }).catch(() => []);
  const ids = frames.map(frame => frame.frameId);
  return ids.length ? ids : [0];
}

async function messageFrames(tabId, message) {
  const output = [];
  for (const frameId of await frameIdsForTab(tabId)) {
    try {
      const response = await chrome.tabs.sendMessage(tabId, message, { frameId });
      if (response) output.push({ frameId, response });
    } catch {}
  }
  return output;
}

async function probeNimsTab(tabId) {
  const responses = await messageFrames(tabId, { type: 'NIMS_BRIDGE_PROBE' });
  const states = responses.map(item => ({
    frameId: item.frameId,
    ...(item.response.state || {})
  }));
  return {
    authenticated: states.some(state => state.authenticated),
    crFrame: states.find(state => state.crFieldReady) || null,
    reportFrame: states.find(state => Number(state.reportRows || 0) > 0) || null
  };
}

async function getDashboardSessionStatus() {
  const tabs = await chrome.tabs.query({ url: NIMS_TAB_PATTERNS }).catch(() => []);
  for (const tab of tabs) {
    if (!tab.id) continue;
    const probe = await probeNimsTab(tab.id);
    if (probe.authenticated) {
      dashboardWorkerTabId = tab.id;
      return { ok: true, loggedIn: true, state: 'logged_in' };
    }
  }
  return {
    ok: true,
    loggedIn: false,
    state: dashboardLoginWindowId ? 'signing_in' : 'logged_out'
  };
}

async function openDashboardLogin(sender) {
  const current = await getDashboardSessionStatus();
  if (current.loggedIn) return current;

  dashboardHostWindowId = sender && sender.tab ? sender.tab.windowId : null;
  if (dashboardLoginWindowId) {
    try {
      await chrome.windows.update(dashboardLoginWindowId, { focused: true });
      return { ok: true, loggedIn: false, state: 'signing_in' };
    } catch {
      dashboardLoginWindowId = null;
    }
  }

  const created = await chrome.windows.create({
    url: NIMS_LOGIN_URL,
    type: 'popup',
    width: 1160,
    height: 820,
    focused: true
  });
  dashboardLoginWindowId = created.id || null;
  await pushDashboardEvent('KBP_NIMS_STATUS', {
    ok: true,
    loggedIn: false,
    state: 'signing_in'
  });
  return { ok: true, loggedIn: false, state: 'signing_in' };
}

async function waitForTabComplete(tabId, timeoutMs = 15000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    try {
      const tab = await chrome.tabs.get(tabId);
      if (tab.status === 'complete') return true;
    } catch {
      return false;
    }
    await delay(250);
  }
  return false;
}

async function ensureDashboardWorkerTab() {
  if (dashboardWorkerTabId) {
    try {
      await chrome.tabs.get(dashboardWorkerTabId);
      if ((await probeNimsTab(dashboardWorkerTabId)).authenticated) {
        return dashboardWorkerTabId;
      }
    } catch {}
  }

  const status = await getDashboardSessionStatus();
  if (status.loggedIn && dashboardWorkerTabId) return dashboardWorkerTabId;

  const created = await chrome.tabs.create({ url: NIMS_LOGIN_URL, active: false });
  if (!created.id) throw new Error('Unable to open the NIMS session.');
  dashboardWorkerTabId = created.id;
  await waitForTabComplete(created.id);

  for (let attempt = 0; attempt < 20; attempt += 1) {
    if ((await probeNimsTab(created.id)).authenticated) return created.id;
    await delay(350);
  }
  throw new Error('NIMS session is not authenticated. Sign in again.');
}

async function findCrFrame(tabId) {
  for (let attempt = 0; attempt < 18; attempt += 1) {
    const probe = await probeNimsTab(tabId);
    if (probe.crFrame) return probe.crFrame.frameId;

    const frameIds = await frameIdsForTab(tabId);
    for (const frameId of [0, ...frameIds.filter(id => id !== 0)]) {
      try {
        const response = await chrome.tabs.sendMessage(
          tabId,
          { type: 'NIMS_BRIDGE_OPEN_CR' },
          { frameId }
        );
        if (response && response.ok) break;
      } catch {}
    }
    await delay(attempt < 5 ? 550 : 900);
  }
  throw new Error('Unable to open the NIMS CR-wise results page.');
}

async function waitForReportFrame(tabId) {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    const probe = await probeNimsTab(tabId);
    if (probe.reportFrame) return probe.reportFrame.frameId;
    await delay(500);
  }
  throw new Error('NIMS did not return a result list for this CR number.');
}

function cultureNarrative(culture) {
  if (!culture) return '';
  const organisms = Array.isArray(culture.organisms) && culture.organisms.length
    ? culture.organisms.join(', ')
    : String(culture.organism || '');

  return [
    culture.site_specimen || culture.site || culture.specimen || '',
    culture.result || culture.result_status || '',
    organisms,
    culture.growth_quantity || '',
    culture.comment || '',
    Array.isArray(culture.susceptible_antibiotics) && culture.susceptible_antibiotics.length
      ? 'Susceptible: ' + culture.susceptible_antibiotics.join(', ')
      : '',
    Array.isArray(culture.resistant_antibiotics) && culture.resistant_antibiotics.length
      ? 'Resistant: ' + culture.resistant_antibiotics.join(', ')
      : ''
  ].filter(Boolean).join(' · ');
}

function bundleFromParsedReports(crNo, patient, sourceReports, parsedReports, source, listedCount) {
  const results = [];
  let resultIndex = 0;

  for (const report of parsedReports) {
    for (const parameter of report.parameters || []) {
      results.push({
        id: 'nims-' + (++resultIndex),
        group: report.report_type || (report.report_tags || []).join(', '),
        test: report.report_name || parameter.name || 'Investigation',
        parameter: parameter.canonical_name || parameter.name || 'Result',
        date: parameter.date_sent || report.date_sent || '',
        value: parameter.value == null ? '' : String(parameter.value),
        unit: parameter.unit || '',
        refRange: parameter.reference_range || '',
        abnormal: parameter.abnormal_flag || 'unknown',
        reportId: report.report_id || ''
      });
    }

    const cultures = [];
    if (report.culture) cultures.push(report.culture);
    if (Array.isArray(report.culture_results)) cultures.push(...report.culture_results);
    for (const culture of cultures) {
      const value = cultureNarrative(culture);
      if (!value) continue;
      results.push({
        id: 'nims-' + (++resultIndex),
        group: 'Microbiology',
        test: report.report_name || 'Culture',
        parameter: 'Culture result',
        date: culture.reporting_date
          || culture.collection_date
          || culture.date_sent
          || report.date_sent
          || '',
        value,
        reportId: report.report_id || ''
      });
    }
  }

  return {
    schemaVersion: 'nims-dashboard-canonical-v1',
    patient: {
      name: patient && patient.name ? patient.name : '',
      crNo: patient && patient.crNo ? patient.crNo : crNo
    },
    source,
    coverage: {
      resultCount: results.length,
      reportCount: Number(listedCount || sourceReports.length),
      parsedReportCount: parsedReports.length
    },
    reports: sourceReports,
    results,
    enquiryRows: []
  };
}

async function dashboardTabId(preferredTabId) {
  if (preferredTabId) {
    try {
      const tab = await chrome.tabs.get(preferredTabId);
      if (tab && typeof tab.url === 'string' && tab.url.startsWith(DASHBOARD_URL_PATTERN.replace('*', ''))) {
        return preferredTabId;
      }
    } catch {}
  }

  const tabs = await chrome.tabs.query({ url: [DASHBOARD_URL_PATTERN] }).catch(() => []);
  const tab = tabs.find(candidate => candidate.id);
  return tab && tab.id ? tab.id : null;
}

async function parseReportInDashboard(payload, preferredTabId) {
  const tabId = await dashboardTabId(preferredTabId);
  if (!tabId) {
    throw makeError('The NIMS Results Dashboard tab is not available for local report parsing.', 'NIMS_DASHBOARD_PARSER_UNAVAILABLE');
  }

  try {
    const response = await chrome.tabs.sendMessage(tabId, {
      type: 'NIMS_DASHBOARD_PARSE_REQUEST',
      requestId: crypto.randomUUID(),
      payload
    });
    if (!response || response.ok === false || !response.parsed) {
      throw makeError(
        (response && response.error) || 'The dashboard could not parse the NIMS report locally.',
        'NIMS_DASHBOARD_PARSER_UNAVAILABLE'
      );
    }
    return response.parsed;
  } catch (error) {
    if (error && error.code === 'NIMS_DASHBOARD_PARSER_UNAVAILABLE') throw error;
    throw makeError(
      'The dashboard parser is not ready. Refresh the dashboard and retry.',
      'NIMS_DASHBOARD_PARSER_UNAVAILABLE'
    );
  }
}

async function parseFetchedReport(report, url, dashboardTab) {
  const response = await fetch(url, {
    method: 'GET',
    credentials: 'include',
    redirect: 'follow'
  });
  const contentType = response.headers.get('content-type') || '';
  const buffer = await response.arrayBuffer();
  const classification = classifyReportResponse(buffer, contentType, response.status);
  const reason = classificationError(classification);

  if (!response.ok || reason) {
    return { ok: false, error: reason || 'NIMS report request failed.' };
  }
  if (buffer.byteLength > MAX_REPORT_BYTES) {
    return { ok: false, error: 'NIMS report exceeded the local parsing size limit.' };
  }

  const parsed = await parseReportInDashboard({
    reportId: report.id || '',
    reportName: report.title || 'Investigation report',
    dateSent: report.date || '',
    contentType,
    classification,
    base64: arrayBufferToBase64(buffer)
  }, dashboardTab);

  return { ok: true, parsed };
}

async function parseUsableReports(reports, dashboardTab) {
  const parsedReports = [];
  const sourceReports = [];
  let firstError = '';
  let firstErrorCode = '';

  for (const report of reports) {
    try {
      const url = self.NimsRestApi && self.NimsRestApi.safeReportUrl
        ? self.NimsRestApi.safeReportUrl(report.resolvedUrl || report.url || '')
        : '';
      if (!url) continue;

      const result = await parseFetchedReport(report, url, dashboardTab);
      if (!result.ok) {
        if (!firstError) firstError = result.error || 'Report parsing failed.';
        continue;
      }

      parsedReports.push(result.parsed);
      sourceReports.push({
        id: report.id || '',
        title: report.title || 'Investigation report',
        date: report.date || '',
        department: report.department || ''
      });
    } catch (error) {
      if (!firstError) firstError = error && error.message ? error.message : 'Report parsing failed.';
      if (!firstErrorCode) firstErrorCode = error && error.code ? error.code : '';
    }
  }

  return { parsedReports, sourceReports, firstError, firstErrorCode };
}

async function tryRestApiForDashboard(crNo, dashboardTab) {
  if (!self.NimsRestApi) {
    return { ok: false, reason: 'REST adapter unavailable.' };
  }

  try {
    const reportList = await self.NimsRestApi.fetchReportList(crNo, fetch, '33101');
    const diagnostics = self.NimsRestApi.safeDiagnostics(reportList);
    const usable = reportList.reports
      .map(report => ({
        ...report,
        resolvedUrl: report.url || self.NimsRestApi.verifiedReportUrlForToken(report.token)
      }))
      .filter(report => report.resolvedUrl);

    if (!usable.length) {
      return {
        ok: false,
        reason: 'REST report rows expose neither a verified report URL nor a safe report token.',
        diagnostics
      };
    }

    const parsed = await parseUsableReports(usable, dashboardTab);
    if (!parsed.parsedReports.length && parsed.firstErrorCode === 'NIMS_DASHBOARD_PARSER_UNAVAILABLE') {
      return {
        ok: false,
        code: parsed.firstErrorCode,
        reason: parsed.firstError,
        diagnostics
      };
    }

    const bundle = bundleFromParsedReports(
      crNo,
      {
        name: reportList.patientName || '',
        crNo: reportList.returnedCrNo || crNo
      },
      parsed.sourceReports,
      parsed.parsedReports,
      'nims_rest_api',
      reportList.reports.length
    );

    if (!bundle.results.length) {
      return {
        ok: false,
        reason: parsed.firstError || 'REST reports produced no structured result values.',
        diagnostics
      };
    }

    return { ok: true, bundle, diagnostics };
  } catch (error) {
    return {
      ok: false,
      reason: error && error.message ? error.message : 'NIMS REST retrieval failed.',
      code: error && error.code ? error.code : ''
    };
  }
}

async function fetchCrForDashboardLegacy(crNo, dashboardTab) {
  const tabId = await ensureDashboardWorkerTab();
  const crFrameId = await findCrFrame(tabId);
  const submitted = await chrome.tabs.sendMessage(
    tabId,
    { type: 'NIMS_BRIDGE_SUBMIT_CR', crNo },
    { frameId: crFrameId }
  );
  if (!submitted || submitted.ok === false) {
    throw new Error((submitted && submitted.error) || 'Unable to submit CR number.');
  }

  const reportFrameId = await waitForReportFrame(tabId);
  const extracted = await chrome.tabs.sendMessage(
    tabId,
    { type: 'NIMS_BRIDGE_EXTRACT_DASHBOARD_DATA' },
    { frameId: reportFrameId }
  );
  if (!extracted || extracted.ok === false) {
    throw new Error((extracted && extracted.error) || 'Unable to read the NIMS result list.');
  }

  const returnedCr = String(extracted.patient && extracted.patient.crNo || '').replace(/\D/g, '');
  if (returnedCr && returnedCr !== crNo) {
    throw new Error('NIMS identity verification failed. Results were not retrieved.');
  }

  const usable = (extracted.reports || [])
    .map(report => ({
      ...report,
      resolvedUrl: report.url
        || (self.NimsRestApi && self.NimsRestApi.verifiedReportUrlForToken
          ? self.NimsRestApi.verifiedReportUrlForToken(report.token)
          : '')
    }))
    .filter(report => report.resolvedUrl);

  if (!usable.length) {
    throw new Error('The browser fallback found report rows but no safe report token could be resolved.');
  }

  const parsed = await parseUsableReports(usable, dashboardTab);
  if (!parsed.parsedReports.length && parsed.firstErrorCode === 'NIMS_DASHBOARD_PARSER_UNAVAILABLE') {
    throw makeError(parsed.firstError, parsed.firstErrorCode);
  }

  const bundle = bundleFromParsedReports(
    crNo,
    extracted.patient || { crNo },
    parsed.sourceReports,
    parsed.parsedReports,
    'authenticated_browser_fallback',
    extracted.reportCount || extracted.reports.length
  );
  if (!bundle.results.length) {
    throw new Error(parsed.firstError || 'The browser fallback found reports but produced no structured result values.');
  }
  return bundle;
}

async function fetchCrForDashboard(rawCrNo, sender) {
  const crNo = String(rawCrNo || '').replace(/\D/g, '');
  if (!/^\d{15}$/.test(crNo)) {
    return { ok: false, error: 'Enter the 15-digit NIMS CR number.' };
  }

  try {
    const dashboardTab = sender && sender.tab ? sender.tab.id : null;
    await pushDashboardEvent('KBP_NIMS_FETCH_STARTED', { preferredSource: 'nims_rest_api' });
    await ensureDashboardWorkerTab();

    const rest = await tryRestApiForDashboard(crNo, dashboardTab);
    if (!rest.ok && ['NIMS_IDENTITY_MISMATCH', 'NIMS_DASHBOARD_PARSER_UNAVAILABLE'].includes(rest.code)) {
      throw makeError(rest.reason || 'NIMS retrieval stopped for safety.', rest.code);
    }

    let bundle;
    if (rest.ok) {
      bundle = rest.bundle;
    } else {
      await pushDashboardEvent('KBP_NIMS_SOURCE_FALLBACK', {
        from: 'nims_rest_api',
        to: 'authenticated_browser_fallback',
        reason: rest.reason || 'REST path unavailable.',
        diagnostics: rest.diagnostics || {}
      });
      bundle = await fetchCrForDashboardLegacy(crNo, dashboardTab);
    }

    await pushDashboardEvent('KBP_NIMS_BULK_RESULTS', { payload: bundle });
    return {
      ok: true,
      source: bundle.source,
      resultCount: bundle.results.length,
      reportCount: bundle.reports.length
    };
  } catch (error) {
    const message = error && error.message ? error.message : 'NIMS retrieval failed.';
    await pushDashboardEvent('KBP_NIMS_FETCH_ERROR', { error: message });
    return { ok: false, error: message };
  }
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (!message) return false;

  if (message.type === 'NIMS_SESSION_STATE') {
    handleDashboardSessionState(message.state || {}, sender)
      .then(() => sendResponse({ ok: true }));
    return true;
  }
  if (message.type === 'NIMS_DASHBOARD_STATUS') {
    getDashboardSessionStatus().then(sendResponse);
    return true;
  }
  if (message.type === 'NIMS_DASHBOARD_LOGIN') {
    openDashboardLogin(sender).then(sendResponse);
    return true;
  }
  if (message.type === 'NIMS_DASHBOARD_FETCH_CR') {
    fetchCrForDashboard(message.crNo, sender).then(sendResponse);
    return true;
  }
  return false;
});
