import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const manifest = JSON.parse(fs.readFileSync(new URL('../manifest.json', import.meta.url), 'utf8'));
const dashboardBridge = fs.readFileSync(new URL('../src/dashboardBridge.js', import.meta.url), 'utf8');
const sessionBridge = fs.readFileSync(new URL('../src/nimsSessionBridge.js', import.meta.url), 'utf8');
const background = fs.readFileSync(new URL('../src/background.js', import.meta.url), 'utf8');

test('dashboard origin is explicitly bridged without broad host access', () => {
  const target = 'https://nims-results-cockpit-ne5nig.v2.appdeploy.ai/*';
  assert.ok(manifest.host_permissions.includes(target));
  const dashboardScript = manifest.content_scripts.find(entry => entry.matches.includes(target));
  assert.deepEqual(dashboardScript.js, ['src/dashboardBridge.js']);
});

test('dashboard bridge exposes status login and CR fetch contracts', () => {
  for (const token of ['KBP_NIMS_STATUS_REQUEST','KBP_NIMS_LOGIN_REQUEST','KBP_NIMS_FETCH_REQUEST','KBP_NIMS_BULK_RESULTS']) {
    assert.match(dashboardBridge + background, new RegExp(token));
  }
});

test('NIMS session bridge uses exact CR form contract and never collects credentials', () => {
  assert.match(sessionBridge, /patcrno/i);
  assert.match(sessionBridge, /SHOWPATDETAILS/);
  assert.doesNotMatch(sessionBridge, /password\s*[:=]/i);
  assert.doesNotMatch(sessionBridge, /captcha\s*[:=]/i);
  assert.ok(sessionBridge.includes('if (!/^\\d{15}$/.test(crNo))'));
  assert.ok(background.includes('if (!/^\\d{15}$/.test(crNo))'));
});

test('background keeps per-browser authenticated session and moves login tab out before closing popup', () => {
  assert.match(background, /chrome\.tabs\.move/);
  assert.match(background, /chrome\.windows\.remove/);
  assert.match(background, /ensureDashboardWorkerTab/);
  assert.match(background, /bundleFromParsedReports/);
});

test('dashboard bridge marks connector readiness for dashboard polling fallback', () => {
  assert.match(dashboardBridge, /data-nims-connector-ready/);
});

test('restored bridge scripts are syntactically valid JavaScript', () => {
  assert.doesNotThrow(() => new Function(dashboardBridge));
  assert.doesNotThrow(() => new Function(sessionBridge));
  assert.doesNotThrow(() => new Function(background));
});


test('dashboard retrieval prefers REST reportList and preserves authenticated browser fallback', () => {
  assert.match(background, /tryRestApiForDashboard/);
  assert.match(background, /fetchCrForDashboardLegacy/);
  assert.match(background, /nims_rest_api/);
  assert.match(background, /authenticated_browser_fallback/);
  assert.match(background, /KBP_NIMS_SOURCE_FALLBACK/);
  assert.match(background, /pdf_base64/);
});


test('REST-primary dashboard path remains behind the authenticated NIMS session gate', () => {
  const start = background.indexOf('async function fetchCrForDashboard(rawCrNo, sender)');
  const body = background.slice(start, start + 2600);
  assert.ok(body.indexOf('ensureDashboardWorkerTab()') >= 0);
  assert.ok(body.indexOf('ensureDashboardWorkerTab()') < body.indexOf('tryRestApiForDashboard(crNo)'));
});


test('REST parsing does not export transient resolved report URLs into the canonical bundle', () => {
  const restStart = background.indexOf('async function tryRestApiForDashboard');
  const restEnd = background.indexOf('async function fetchCrForDashboardLegacy', restStart);
  const restBody = background.slice(restStart, restEnd);
  assert.match(restBody, /fetch\(report\.resolvedUrl/);
  assert.doesNotMatch(restBody, /sourceReports\.push\([^\n]*resolvedUrl/);
});


test('dashboard bridge exposes a unique runtime version for compatibility checks', () => {
  assert.equal(manifest.version, '0.4.2');
  assert.match(dashboardBridge, /chrome\.runtime\.getManifest\(\)\.version/);
  assert.match(dashboardBridge, /version:\s*BRIDGE_VERSION/);
});

test('desktop REST identity mismatch fails closed instead of entering browser fallback', () => {
  const start = background.indexOf('const rest = await tryRestApiForDashboard(crNo);');
  const end = background.indexOf('await pushDashboardEvent("KBP_NIMS_BULK_RESULTS"', start);
  const body = background.slice(start, end);
  const mismatch = body.indexOf('NIMS_IDENTITY_MISMATCH');
  const fallback = body.indexOf('KBP_NIMS_SOURCE_FALLBACK');
  assert.ok(mismatch >= 0, 'identity mismatch guard is required');
  assert.ok(fallback >= 0, 'explicit fallback event is required');
  assert.ok(mismatch < fallback, 'identity mismatch must be handled before fallback');
  assert.match(body, /NIMS identity verification failed\. Results were not retrieved\./);
});
