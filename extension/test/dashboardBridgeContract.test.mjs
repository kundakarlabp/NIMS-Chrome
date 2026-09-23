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
});

test('background keeps per-browser authenticated session and moves login tab out before closing popup', () => {
  assert.match(background, /chrome\.tabs\.move/);
  assert.match(background, /chrome\.windows\.remove/);
  assert.match(background, /ensureDashboardWorkerTab/);
  assert.match(background, /bundleFromParsedReports/);
});
