import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const bg = fs.readFileSync(new URL('../src/background.js', import.meta.url), 'utf8');
const session = fs.readFileSync(new URL('../src/nimsSessionBridge.js', import.meta.url), 'utf8');

function isolate(source, name, environment) {
  const expression = new RegExp('\\b(?:async\\s+)?function\\s+' + name + '\\s*\\(');
  const index = source.search(expression);
  assert.ok(index >= 0, 'Expected ' + name + ' in source');
  const remainder = source.slice(index);
  const nextMatch = /\n[ \t]*(?:async\s+)?function\s+[A-Za-z]\w*\s*\(/.exec(remainder.slice(1));
  const end = nextMatch ? nextMatch.index + 1 : remainder.length;
  const code = remainder.slice(0, end);
  const context = vm.createContext(environment);
  vm.runInContext(code, context);
  return (...args) => context[name](...args);
}

test('session rediscovery uses full authenticated NIMS allow-list', async () => {
  let queried = null;
  const allowed = ['https://www.nimsts.edu.in/HISUtilities/*', 'https://www.nimsts.edu.in/HBIMS/*'];
  const run = isolate(bg, 'getDashboardSessionStatus', {
    NIMS_URL_FILTERS: allowed,
    chrome: { tabs: { query: async opts => { queried = opts.url; return [{ id: 42 }]; } } },
    probeNimsTab: async () => ({ authenticated: true }),
    dashboardWorkerTabId: null,
    dashboardLastAuthenticated: null,
  });
  const status = await run();
  assert.deepEqual(Array.from(queried), allowed);
  assert.equal(status.loggedIn, true);
});

test('CR navigation probes every iframe, not merely the first successful shell', async () => {
  const visited = [];
  const run = isolate(bg, 'openOneCrNavigationStep', {
    frameIdsForTab: async () => [0, 3, 7],
    chrome: { tabs: { sendMessage: async (_tabId, _msg, opts) => {
      visited.push(opts.frameId);
      if (opts.frameId === 0) return { ok: true, action: 'waiting' };
      if (opts.frameId === 3) return { ok: true, stage: 'cr_search', action: 'clicked_crwise' };
      return { ok: false, action: 'none' };
    } } }
  });
  const response = await run(8);
  assert.deepEqual(visited, [0, 3, 7]);
  assert.equal(response.frameId, 3);
});

test('manual browser navigation prioritizes native frame menu before direct-leaf shim', () => {
  const calls = [];
  const run = isolate(session, 'openCrWise', {
    document: {},
    window: { NimsReportCore: {
      navigateCurrentDocumentStep: () => { calls.push('native'); return { ok: true, stage: 'cr_search' }; },
      openCrWiseResultsDirect: () => { calls.push('direct'); return { ok: true }; }
    } }
  });
  const response = run();
  assert.equal(response.ok, true);
  assert.deepEqual(calls, ['native']);
});

test('browser fallback reuses only matching CR report list and verifies returned identity', async () => {
  const crNo = '123456789012345';
  const messages = [];
  const run = isolate(bg, 'fetchCrForDashboardLegacy', {
    ensureDashboardWorkerTab: async () => 8,
    probeNimsTab: async () => ({ reportFrame: { frameId: 3, patient: { crNo } } }),
    chrome: { tabs: { sendMessage: async (_tabId, message) => {
      messages.push(message.type);
      if (message.type === 'NIMS_BRIDGE_EXTRACT_DASHBOARD_DATA') return { patient: { crNo } };
      if (message.type === 'NIMS_BRIDGE_RUN_SUMMARY') return { ok: true, state: {} };
      throw new Error('Matching CR must not be submitted twice');
    } } },
    bundleFromParsedReports: requested => ({ patient: { crNo: requested }, results: [{ parameter: 'test' }], reports: [] }),
  });
  const bundle = await run(crNo);
  assert.equal(bundle.source, 'authenticated_browser_fallback');
  assert.deepEqual(messages, ['NIMS_BRIDGE_EXTRACT_DASHBOARD_DATA', 'NIMS_BRIDGE_RUN_SUMMARY']);
});

test('browser fallback rejects a different returned CR before parsing', async () => {
  const crNo = '123456789012345';
  const messages = [];
  const run = isolate(bg, 'fetchCrForDashboardLegacy', {
    ensureDashboardWorkerTab: async () => 8,
    probeNimsTab: async () => ({ reportFrame: { frameId: 3, patient: { crNo } } }),
    chrome: { tabs: { sendMessage: async (_tabId, message) => {
      messages.push(message.type);
      return { patient: { crNo: '999999999999999' } };
    } } },
    bundleFromParsedReports: () => { throw new Error('Must not parse wrong patient'); },
  });
  await assert.rejects(run(crNo), /identity verification failed/i);
  assert.deepEqual(messages, ['NIMS_BRIDGE_EXTRACT_DASHBOARD_DATA']);
});
