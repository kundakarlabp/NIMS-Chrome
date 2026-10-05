import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const manifest = JSON.parse(fs.readFileSync(new URL('../manifest.json', import.meta.url), 'utf8'));
const dashboardBridge = fs.readFileSync(new URL('../src/dashboardBridge.js', import.meta.url), 'utf8');
const sessionBridge = fs.readFileSync(new URL('../src/nimsSessionBridge.js', import.meta.url), 'utf8');
const background = fs.readFileSync(new URL('../src/background.js', import.meta.url), 'utf8');
const processor = fs.readFileSync(new URL('../src/contentScript.js', import.meta.url), 'utf8');

test('manifest contains only dashboard retrieval runtime surfaces', () => {
  assert.equal(manifest.version, '0.5.10');
  assert.equal(manifest.side_panel, undefined);
  assert.equal(manifest.action, undefined);
  assert.equal(manifest.content_scripts.some(entry => entry.match_origin_as_fallback), false);
  const files = manifest.content_scripts.flatMap(entry => entry.js || []);
  assert.equal(files.some(path => /manualAnalysis|sidepanel/i.test(path)), false);
  assert.ok(files.includes('src/contentUtils.js'));
  assert.ok(files.includes('src/contentScript.js'));
  assert.ok(files.includes('src/nimsSessionBridge.js'));
});

test('dashboard origin is explicitly bridged', () => {
  const target = 'https://nims-results-cockpit-ne5nig.v2.appdeploy.ai/*';
  assert.ok(manifest.host_permissions.includes(target));
  const dashboardScript = manifest.content_scripts.find(entry => entry.matches.includes(target));
  assert.deepEqual(dashboardScript.js, ['src/dashboardBridge.js']);
});

test('dashboard bridge preserves manual login and CR fetch contracts', () => {
  for (const token of [
    'KBP_NIMS_STATUS_REQUEST',
    'KBP_NIMS_LOGIN_REQUEST',
    'KBP_NIMS_FETCH_REQUEST',
    'KBP_NIMS_BULK_RESULTS'
  ]) {
    assert.match(dashboardBridge + background, new RegExp(token));
  }
  assert.match(dashboardBridge, /chrome\.runtime\.getManifest\(\)\.version/);
});

test('REST is primary and proven authenticated browser path is fallback', () => {
  const start = background.indexOf('async function fetchCrForDashboard(rawCrNo, sender)');
  const body = background.slice(start);
  assert.ok(start >= 0);
  assert.ok(body.indexOf('ensureDashboardWorkerTab()') >= 0);
  assert.ok(body.indexOf('tryRestApiForDashboard(crNo)') >= 0);
  assert.ok(body.indexOf('tryRestApiForDashboard(crNo)') < body.indexOf('fetchCrForDashboardLegacy(crNo)'));
  assert.match(body, /KBP_NIMS_SOURCE_FALLBACK/);
  assert.match(body, /authenticated_browser_fallback/);
});

test('browser fallback uses the previously validated bridge processor', () => {
  assert.match(background, /NIMS_BRIDGE_RUN_SUMMARY/);
  assert.match(sessionBridge, /NIMS_BRIDGE_RUN_SUMMARY/);
  assert.match(processor, /runSummary/);
  assert.match(processor, /NIMS_FETCH_REPORT_DIRECT/);
  assert.match(processor, /NIMS_HELPER_PARSE_REPORT/);
});

test('identity mismatch fails closed before fallback', () => {
  const start = background.indexOf('const rest = await tryRestApiForDashboard(crNo);');
  const end = background.indexOf('await pushDashboardEvent("KBP_NIMS_BULK_RESULTS"', start);
  const body = background.slice(start, end);
  assert.ok(body.indexOf('NIMS_IDENTITY_MISMATCH') >= 0);
  assert.ok(body.indexOf('NIMS_IDENTITY_MISMATCH') < body.indexOf('KBP_NIMS_SOURCE_FALLBACK'));
});

test('credentials and CAPTCHA are never collected by the bridge', () => {
  assert.match(sessionBridge, /patcrno/i);
  assert.match(sessionBridge, /SHOWPATDETAILS/);
  assert.doesNotMatch(sessionBridge, /password\s*[:=]/i);
  assert.doesNotMatch(sessionBridge, /captcha\s*[:=]/i);
});

test('legacy visible toolbar is disabled', () => {
  assert.match(processor, /Dashboard-driven only/);
  const start = processor.indexOf('function start()');
  const body = processor.slice(start, processor.indexOf('function scanAndInject', start));
  assert.doesNotMatch(body, /scanAndInject\(\)/);
});

test('runtime scripts compile', () => {
  assert.doesNotThrow(() => new Function(dashboardBridge));
  assert.doesNotThrow(() => new Function(sessionBridge));
  assert.doesNotThrow(() => new Function(background));
  assert.doesNotThrow(() => new Function(processor));
});

test('browser fallback stops before parsing when the displayed CR differs', async () => {
  const start = background.indexOf('async function fetchCrForDashboardLegacy(crNo)');
  const end = background.indexOf('async function fetchCrForDashboard(rawCrNo, sender)', start);
  const source = background.slice(start, end);
  let ranProcessor = false;
  const context = {
    ensureDashboardWorkerTab: async () => 1,
    probeNimsTab: async () => ({ reportFrame: null }),
    findCrFrame: async () => 0,
    waitForReportFrame: async () => 0,
    chrome: { tabs: { sendMessage: async (_tab, message) => {
      if (message.type === 'NIMS_BRIDGE_SUBMIT_CR') return { ok: true };
      if (message.type === 'NIMS_BRIDGE_EXTRACT_DASHBOARD_DATA') return { patient: { crNo: '331012600000999' }, reports: [] };
      ranProcessor = true;
      return { ok: true, state: {} };
    } } }
  };
  vm.createContext(context);
  vm.runInContext(source, context);
  await assert.rejects(() => context.fetchCrForDashboardLegacy('331012600000001'), /identity verification failed/);
  assert.equal(ranProcessor, false);
});

test('browser fallback reuses an open report list only for the requested CR', async () => {
  const start = background.indexOf('async function fetchCrForDashboardLegacy(crNo)');
  const end = background.indexOf('async function fetchCrForDashboard(rawCrNo, sender)', start);
  const source = background.slice(start, end);
  const requestedCr = '331012600000001';
  const sent = [];
  const context = {
    ensureDashboardWorkerTab: async () => 1,
    probeNimsTab: async () => ({ reportFrame: { frameId: 0, patient: { crNo: requestedCr } } }),
    findCrFrame: async () => { throw new Error('should reuse the report list'); },
    bundleFromParsedReports: () => ({ results: [{}], reports: [{}] }),
    chrome: { tabs: { sendMessage: async (_tab, message) => {
      sent.push(message.type);
      if (message.type === 'NIMS_BRIDGE_EXTRACT_DASHBOARD_DATA') return { patient: { crNo: requestedCr }, reports: [] };
      if (message.type === 'NIMS_BRIDGE_RUN_SUMMARY') return { ok: true, state: {} };
      throw new Error('unexpected message');
    } } }
  };
  vm.createContext(context);
  vm.runInContext(source, context);
  const bundle = await context.fetchCrForDashboardLegacy(requestedCr);
  assert.deepEqual(sent, ['NIMS_BRIDGE_EXTRACT_DASHBOARD_DATA', 'NIMS_BRIDGE_RUN_SUMMARY']);
  assert.equal(bundle.source, 'authenticated_browser_fallback');
});

test('browser fallback waits for the new report after a form-submit port closes', async () => {
  const start = background.indexOf('async function fetchCrForDashboardLegacy(crNo)');
  const end = background.indexOf('async function fetchCrForDashboard(rawCrNo, sender)', start);
  const source = background.slice(start, end);
  const requestedCr = '331012600000001';
  let waited = false;
  const context = {
    ensureDashboardWorkerTab: async () => 1,
    probeNimsTab: async () => ({ reportFrame: null }),
    findCrFrame: async () => 0,
    waitForReportFrame: async () => { waited = true; return 0; },
    bundleFromParsedReports: () => ({ results: [{}], reports: [{}] }),
    chrome: { tabs: { sendMessage: async (_tab, message) => {
      if (message.type === 'NIMS_BRIDGE_SUBMIT_CR') throw new Error('The page keeping the extension port is moved into back/forward cache, so the message channel is closed.');
      if (message.type === 'NIMS_BRIDGE_EXTRACT_DASHBOARD_DATA') return { patient: { crNo: requestedCr }, reports: [] };
      if (message.type === 'NIMS_BRIDGE_RUN_SUMMARY') return { ok: true, state: {} };
    } } }
  };
  vm.createContext(context);
  vm.runInContext(source, context);
  await context.fetchCrForDashboardLegacy(requestedCr);
  assert.equal(waited, true);
});

test('parsed clinical state is kept in the content frame, not persistent extension storage', () => {
  assert.match(processor, /getSummaryState: \(\) => summaryState/);
  assert.doesNotMatch(processor, /storage\.local\.set\(\{ nimsFastSummaryState/);
  assert.doesNotMatch(sessionBridge, /storage\.local\.get\("nimsFastSummaryState"\)/);
});


test('canonical bundle carries versioned NIMS provenance for downstream clinical consumers', () => {
  const start = background.indexOf('function bundleFromParsedReports(crNo, extracted, state)');
  const end = background.indexOf('async function tryRestApiForDashboard(crNo)', start);
  const source = background.slice(start, end);
  const context = {};
  vm.createContext(context);
  vm.runInContext(source, context);
  const bundle = context.bundleFromParsedReports(
    '331012600000001',
    { patient: { name: 'Synthetic Patient', crNo: '331012600000001' }, reports: [{ id: 'r1', title: 'CBC', date: '04/10/2026', url: '' }] },
    { parsedReports: [{ report_id: 'r1', report_name: 'CBC', date_sent: '04/10/2026', parameters: [{ canonical_name: 'WBC', value: '12000', unit: '/uL' }] }] }
  );
  assert.equal(bundle.schemaVersion, 'nims-clinical-bundle/1.0');
  assert.equal(bundle.patient.provenance.source, 'nims');
  assert.equal(bundle.patient.provenance.identityVerified, true);
  assert.equal(bundle.results[0].provenance.source, 'nims');
  assert.equal(bundle.results[0].provenance.method, 'deterministic_parser');
  assert.equal(bundle.reports[0].provenance.source, 'nims');
});


test('manual session rediscovery scans every NIMS module supported by the bridge', () => {
  const start = background.indexOf('async function getDashboardSessionStatus()');
  const end = background.indexOf('async function openDashboardLogin', start);
  const body = background.slice(start, end);
  assert.ok(start >= 0);
  assert.match(body, /chrome\.tabs\.query\(\{ url: NIMS_URL_FILTERS \}\)/);
  for (const moduleName of ['AHIMSG5', 'HISInvestigationG5', 'HIS', 'hislogin', 'HISUtilities', 'HBIMS']) {
    assert.ok(background.includes('https://nimsts.edu.in/' + moduleName + '/*'));
    assert.ok(background.includes('https://www.nimsts.edu.in/' + moduleName + '/*'));
  }
});


test('CR fetch opens visible manual login instead of failing when session is absent', async () => {
  const start = background.indexOf('async function fetchCrForDashboard(rawCrNo, sender)');
  const source = background.slice(start);
  const events = [];
  let loginOpened = false;
  let workerTouched = false;
  const context = {
    getDashboardSessionStatus: async () => ({ ok: true, loggedIn: false, state: 'logged_out' }),
    openDashboardLogin: async () => { loginOpened = true; return { ok: true, state: 'signing_in' }; },
    pushDashboardEvent: async (type, payload) => { events.push({ type, payload }); },
    ensureDashboardWorkerTab: async () => { workerTouched = true; throw new Error('should not create hidden worker before login'); },
    tryRestApiForDashboard: async () => ({ ok: false }),
    fetchCrForDashboardLegacy: async () => ({ results: [], reports: [] })
  };
  vm.createContext(context);
  vm.runInContext(source, context);
  const result = await context.fetchCrForDashboard('331012600000001', { tab: { id: 99, windowId: 1 } });
  assert.equal(result.ok, true);
  assert.equal(result.pendingAuth, true);
  assert.equal(loginOpened, true);
  assert.equal(workerTouched, false);
  assert.equal(events.some(event => event.type === 'KBP_NIMS_AUTH_REQUIRED'), true);
});

test('authenticated session probe accepts supported protected routes but excludes the login endpoint', () => {
  assert.match(sessionBridge, /supportedProtectedRoute/);
  assert.match(sessionBridge, /HISInvestigationG5\|HIS\|hislogin\|HISUtilities\|HBIMS/);
  assert.match(sessionBridge, /explicitLoginRoute/);
  assert.match(sessionBridge, /loginLogin\\\.action/);
  assert.match(sessionBridge, /!explicitLoginRoute && protectedModule/);
});

test('auth-required event remains separate from REST fallback and identity mismatch', () => {
  const start = background.indexOf('async function fetchCrForDashboard(rawCrNo, sender)');
  const body = background.slice(start);
  assert.ok(body.indexOf('KBP_NIMS_AUTH_REQUIRED') < body.indexOf('tryRestApiForDashboard(crNo)'));
  assert.ok(body.indexOf('NIMS_IDENTITY_MISMATCH') < body.indexOf('KBP_NIMS_SOURCE_FALLBACK'));
});


test('REST authentication expiry reopens manual login instead of entering browser fallback', () => {
  const start = background.indexOf('const rest = await tryRestApiForDashboard(crNo);');
  const end = background.indexOf('await pushDashboardEvent("KBP_NIMS_BULK_RESULTS"', start);
  const body = background.slice(start, end);
  const auth = body.indexOf('NIMS_AUTH_REQUIRED');
  const mismatch = body.indexOf('NIMS_IDENTITY_MISMATCH');
  const fallback = body.indexOf('KBP_NIMS_SOURCE_FALLBACK');
  assert.ok(auth >= 0);
  assert.ok(auth < mismatch);
  assert.ok(auth < fallback);
  assert.match(body, /openDashboardLogin\(sender\)/);
  assert.match(body, /KBP_NIMS_AUTH_REQUIRED/);
});


test('desktop CR navigation uses native frame contract before direct-leaf fallback', () => {
  const start = sessionBridge.indexOf('function openCrWise()');
  const end = sessionBridge.indexOf('function setInputValue', start);
  const body = sessionBridge.slice(start, end);
  assert.ok(body.indexOf('navigateCurrentDocumentStep') >= 0);
  assert.ok(body.indexOf('openCrWiseResultsDirect') >= 0);
  assert.ok(body.indexOf('navigateCurrentDocumentStep') < body.indexOf('openCrWiseResultsDirect'));
});

test('fallback navigation advances all NIMS frames instead of returning on first ok frame', () => {
  const start = background.indexOf('async function openOneCrNavigationStep(tabId)');
  const end = background.indexOf('async function findCrFrame(tabId)', start);
  const body = background.slice(start, end);
  assert.match(body, /for \(const frameId of ordered\)/);
  assert.match(body, /responses\.push\(\{ frameId, response \}\)/);
  assert.match(body, /responses\.sort/);
  assert.doesNotMatch(body, /if \(response && response\.ok\) return/);
});

test('CR navigation timeout preserves last stage/action for safe diagnosis', () => {
  const start = background.indexOf('async function findCrFrame(tabId)');
  const end = background.indexOf('async function waitForReportFrame', start);
  const body = background.slice(start, end);
  assert.match(body, /lastStep/);
  assert.match(body, /last navigation:/);
  assert.match(body, /attempt < 24/);
});


test('all async background RPC paths return a structured response on rejection', () => {
  assert.match(background, /function respondAsync\(/);
  assert.match(background, /\.catch\(\(error\) => sendResponse\(safeAsyncError/);
  for (const token of [
    'NIMS_DASHBOARD_STATUS',
    'NIMS_DASHBOARD_LOGIN',
    'NIMS_DASHBOARD_FETCH_CR'
  ]) {
    const index = background.indexOf('message.type === "' + token + '"');
    assert.ok(index >= 0, token + ' handler missing');
    const body = background.slice(index, index + 450);
    assert.match(body, /respondAsync\(/);
  }
});

test('session-state notifications do not keep a navigation-sensitive message channel open', () => {
  const start = background.indexOf('message.type === "NIMS_SESSION_STATE"');
  const end = background.indexOf('message.type === "NIMS_DASHBOARD_STATUS"', start);
  const body = background.slice(start, end);
  assert.match(body, /void handleDashboardSessionState/);
  assert.match(body, /return false/);
  assert.doesNotMatch(body, /sendResponse/);
});

test('dashboard bridge retries only transient status-channel closures and hides raw Chrome port errors', () => {
  assert.match(dashboardBridge, /function isTransientChannelError/);
  assert.match(dashboardBridge, /message channel closed\|port closed\|receiving end does not exist/);
  assert.match(dashboardBridge, /retryTransient: true/);
  assert.match(dashboardBridge, /NIMS connector communication restarted/);
});

test('async browser-fallback summary always returns a structured error response', () => {
  const start = sessionBridge.indexOf('message.type === "NIMS_BRIDGE_RUN_SUMMARY"');
  const body = sessionBridge.slice(start, start + 650);
  assert.match(body, /\.catch\(\(error\) => sendResponse/);
  assert.match(body, /NIMS result processing failed/);
});
