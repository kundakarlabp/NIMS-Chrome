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
  assert.equal(manifest.version, '0.5.4');
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

test('parsed clinical state is kept in the content frame, not persistent extension storage', () => {
  assert.match(processor, /getSummaryState: \(\) => summaryState/);
  assert.doesNotMatch(processor, /storage\.local\.set\(\{ nimsFastSummaryState/);
  assert.doesNotMatch(sessionBridge, /storage\.local\.get\("nimsFastSummaryState"\)/);
});
