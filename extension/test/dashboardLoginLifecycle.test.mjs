import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const bg = fs.readFileSync(new URL('../src/background.js', import.meta.url), 'utf8');

function functionText(name) {
  const start = bg.search(new RegExp('\\basync function ' + name + '\\('));
  assert.ok(start >= 0, name + ' missing');
  const rest = bg.slice(start);
  const next = /\n(?:async )?function [A-Za-z]\w*\(/.exec(rest.slice(1));
  return rest.slice(0, next ? next.index + 1 : undefined);
}

function makeStatusContext({ popupWindowId = 20, hostWindowId = 10, nimsWindowId = 20, recorded = true } = {}) {
  const calls = [];
  const pending = recorded ? { popupWindowId, hostWindowId } : null;
  const ctx = {
    Number, Date,
    dashboardLoginWindowId: null,
    dashboardHostWindowId: null,
    dashboardWorkerTabId: null,
    dashboardLastAuthenticated: null,
    DASHBOARD_URL_PATTERN: 'https://nims-results-cockpit-ne5nig.v2.appdeploy.ai/*',
    NIMS_URL_FILTERS: ['https://www.nimsts.edu.in/HIS/*'],
    chrome: {
      storage: { session: {
        get: async () => ({ nimsDashboardPendingLogin: pending }),
        remove: async () => { calls.push('clear'); }
      } },
      tabs: {
        query: async arg => arg.windowId ? [{ id: 9, windowId: hostWindowId }] : [{ id: 8, windowId: nimsWindowId }],
        move: async (id, args) => { calls.push(['move', id, args.windowId]); },
        update: async (id, args) => { calls.push(['activate', id, args.active]); }
      },
      windows: {
        get: async () => ({ id: popupWindowId }),
        remove: async id => { calls.push(['close', id]); },
        update: async (id, args) => { calls.push(['focus', id, args.focused]); }
      }
    },
    probeNimsTab: async () => ({ authenticated: true }),
  };
  vm.createContext(ctx);
  vm.runInContext(
    bg.slice(bg.indexOf('const DASHBOARD_PENDING_LOGIN_KEY ='), bg.indexOf('\nasync function probeNimsTab('))
      + '\n' + functionText('getDashboardSessionStatus'), ctx
  );
  return { ctx, calls, status: () => ctx.getDashboardSessionStatus() };
}

test('NIMS popup is closed and dashboard refocused after service-worker state loss', async () => {
  const { status, calls } = makeStatusContext();
  const result = await status();
  assert.equal(result.loggedIn, true);
  assert.deepEqual(calls.map(x => Array.isArray(x) ? x[0] : x), ['move', 'activate', 'close', 'focus', 'clear']);
  assert.deepEqual(calls[0], ['move', 8, 10]);
  assert.deepEqual(calls[2], ['close', 20]);
});

test('pre-existing NIMS tabs never cause an unrelated popup to be closed', async () => {
  const { status, calls } = makeStatusContext({ nimsWindowId: 99 });
  assert.equal((await status()).loggedIn, true);
  assert.deepEqual(calls, []);
});

test('no popup-close action without a recorded connector-owned popup', async () => {
  const { status, calls } = makeStatusContext({ recorded: false });
  assert.equal((await status()).loggedIn, true);
  assert.deepEqual(calls, []);
});

test('failed structured API exposes reason without removing fallback stages', () => {
  assert.match(bg, /reason: bulk\.ok \? "" : \(bulk\.reason/);
  assert.match(bg, /reason: rest\.ok \? "" : \(rest\.reason/);
  assert.match(bg, /reason: item\.reason \|\| ""/);
  const flow = bg.slice(bg.indexOf('async function fetchCrForDashboard(rawCrNo, sender, rawRequestId)'));
  assert.ok(flow.indexOf('tryBulkApiForDashboard(workerTabId, crNo)') < flow.indexOf('tryRestApiForDashboard(crNo)'));
});
