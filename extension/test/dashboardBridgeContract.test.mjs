import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const manifest = JSON.parse(
  fs.readFileSync(new URL('../manifest.json', import.meta.url), 'utf8')
);
const dashboardBridge = fs.readFileSync(
  new URL('../src/dashboardBridge.js', import.meta.url),
  'utf8'
);
const sessionBridge = fs.readFileSync(
  new URL('../src/nimsSessionBridge.js', import.meta.url),
  'utf8'
);
const background = fs.readFileSync(
  new URL('../src/background.js', import.meta.url),
  'utf8'
);

test('manifest is results-only and has no localhost parser dependency', () => {
  assert.equal(manifest.version, '0.5.1');
  assert.deepEqual(manifest.permissions.sort(), ['tabs', 'webNavigation'].sort());
  assert.equal(manifest.side_panel, undefined);
  assert.equal(manifest.action, undefined);
  assert.equal(
    manifest.host_permissions.some(host => host.includes('127.0.0.1')),
    false
  );
  const nims = manifest.content_scripts.find(entry =>
    entry.matches.some(match => match.includes('nimsts.edu.in'))
  );
  assert.deepEqual(
    nims.js,
    ['src/navigationCore.js', 'src/nimsSessionBridge.js']
  );
});

test('dashboard bridge responds to probes and supports local parse round trips', () => {
  for (const token of [
    'KBP_NIMS_PING',
    'KBP_NIMS_PONG',
    'KBP_NIMS_STATUS_REQUEST',
    'KBP_NIMS_LOGIN_REQUEST',
    'KBP_NIMS_FETCH_REQUEST',
    'KBP_NIMS_PARSE_REQUEST',
    'KBP_NIMS_PARSE_ACK',
    'KBP_NIMS_PARSE_RESPONSE',
    'NIMS_DASHBOARD_PARSE_REQUEST'
  ]) {
    assert.match(dashboardBridge + background, new RegExp(token));
  }
  assert.match(dashboardBridge, /chrome\.runtime\.getManifest\(\)\.version/);
});

test('runtime no longer calls a localhost helper', () => {
  assert.doesNotMatch(background, /127\.0\.0\.1:8765/);
  assert.doesNotMatch(background, /callHelper/);
  assert.doesNotMatch(background, /ensureHelper/);
  assert.match(background, /parseReportInDashboard/);
});

test('REST remains primary and browser is an explicit fallback', () => {
  const start = background.indexOf('async function fetchCrForDashboard(rawCrNo, sender)');
  const body = background.slice(start);
  assert.ok(start >= 0);
  assert.ok(body.indexOf('tryRestApiForDashboard') >= 0);
  assert.ok(
    body.indexOf('tryRestApiForDashboard')
    < body.indexOf('fetchCrForDashboardLegacy')
  );
  assert.match(body, /KBP_NIMS_SOURCE_FALLBACK/);
});

test('identity and parser failures fail closed', () => {
  assert.match(background, /NIMS_IDENTITY_MISMATCH/);
  assert.match(background, /NIMS_DASHBOARD_PARSER_UNAVAILABLE/);
  assert.match(background, /returnedCr && returnedCr !== crNo/);
});

test('browser fallback carries transient tokens only to the background worker', () => {
  assert.match(sessionBridge, /selectRowsForModeFromDoc\('bulk_full'/);
  assert.match(sessionBridge, /transientPrintReportArg/);
  assert.match(sessionBridge, /token:/);
  assert.match(background, /verifiedReportUrlForToken\(report\.token\)/);

  const bundleStart = background.indexOf('function bundleFromParsedReports');
  const bundleEnd = background.indexOf('async function dashboardTabId', bundleStart);
  const bundleBody = background.slice(bundleStart, bundleEnd);
  assert.doesNotMatch(bundleBody, /token:/);
  assert.doesNotMatch(bundleBody, /resolvedUrl:/);
});

test('retired mapping and UI machinery remain absent', () => {
  for (const token of [
    'NIMS_BRIDGE_RUN_SUMMARY',
    'NIMS_DISCOVER_MAPPING',
    'NIMS_GET_MAPPING_SUMMARY',
    'chrome.webRequest',
    'chrome.scripting',
    'sidePanel'
  ]) {
    assert.doesNotMatch(background, new RegExp(token));
  }
});

test('connector scripts compile', () => {
  assert.doesNotThrow(() => new Function(dashboardBridge));
  assert.doesNotThrow(() => new Function(sessionBridge));
  assert.doesNotThrow(() => new Function(background));
});
