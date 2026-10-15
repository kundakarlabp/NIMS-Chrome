import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const background = fs.readFileSync(new URL('../src/background.js', import.meta.url), 'utf8');
const bridge = fs.readFileSync(new URL('../src/nimsSessionBridge.js', import.meta.url), 'utf8');
const CR = '123456789012345';

function isolate(source, name, env) {
  const index = source.search(new RegExp('\\b(?:async\\s+)?function\\s+' + name + '\\s*\\('));
  assert.ok(index >= 0, 'Missing ' + name);
  const rest = source.slice(index);
  const next = /\n[ \t]*(?:async\s+)?function\s+[A-Za-z]\w*\s*\(/.exec(rest.slice(1));
  const context = vm.createContext(env);
  vm.runInContext(rest.slice(0, next ? next.index + 1 : rest.length), context);
  return context[name];
}

test('missing REST CR uses verified browser fallback rather than stopping', async () => {
  const events = [];
  let invoked = 0;
  const run = isolate(background, 'fetchCrForDashboard', {
    getDashboardSessionStatus: async () => ({ loggedIn: true }),
    ensureDashboardWorkerTab: async () => 42,
    pushDashboardEvent: async type => { events.push(type); },
    tryBulkApiForDashboard: async () => ({ ok: false, code: 'NIMS_BULK_FAILED', reason: 'Bulk fixture unavailable' }),
    tryRestApiForDashboard: async () => ({ ok: false, code: 'NIMS_IDENTITY_UNVERIFIED', reason: 'API did not confirm CR' }),
    fetchCrForDashboardLegacy: async requested => {
      assert.equal(requested, CR);
      invoked += 1;
      return { results: [{}], reports: [{}] };
    }
  });
  const outcome = await run(CR, {});
  assert.equal(outcome.ok, true);
  assert.equal(outcome.source, 'authenticated_browser_fallback');
  assert.equal(invoked, 1);
  assert.ok(events.includes('KBP_NIMS_SOURCE_FALLBACK'));
  assert.ok(events.includes('KBP_NIMS_BULK_RESULTS'));
});

test('explicit REST identity mismatch prevents wrong-patient browser retrieval', async () => {
  let invoked = 0;
  const run = isolate(background, 'fetchCrForDashboard', {
    getDashboardSessionStatus: async () => ({ loggedIn: true }),
    ensureDashboardWorkerTab: async () => 42,
    pushDashboardEvent: async () => {},
    tryBulkApiForDashboard: async () => ({ ok: false, code: 'NIMS_BULK_FAILED', reason: 'Bulk fixture unavailable' }),
    tryRestApiForDashboard: async () => ({ ok: false, code: 'NIMS_IDENTITY_MISMATCH' }),
    fetchCrForDashboardLegacy: async () => { invoked += 1; }
  });
  const outcome = await run(CR, {});
  assert.equal(outcome.ok, false);
  assert.match(outcome.error, /different CR/);
  assert.equal(invoked, 0);
});

test('missing browser report CR blocks parsing and delivery', async () => {
  let parsed = false;
  const run = isolate(background, 'fetchCrForDashboardLegacy', {
    ensureDashboardWorkerTab: async () => 42,
    probeNimsTab: async () => ({ reportFrame: { frameId: 3, patient: { crNo: CR } } }),
    chrome: { tabs: { sendMessage: async (_tab, message) =>
      message.type === 'NIMS_BRIDGE_EXTRACT_DASHBOARD_DATA'
        ? { patient: { crNo: '' } }
        : (parsed = true, { ok: true })
    } }
  });
  await assert.rejects(run(CR), /does not display a verifiable CR/);
  assert.equal(parsed, false);
});

test('conflicting browser displayed CR blocks parsing and delivery', async () => {
  let parsed = false;
  const run = isolate(background, 'fetchCrForDashboardLegacy', {
    ensureDashboardWorkerTab: async () => 42,
    probeNimsTab: async () => ({ reportFrame: { frameId: 3, patient: { crNo: CR } } }),
    chrome: { tabs: { sendMessage: async (_tab, message) =>
      message.type === 'NIMS_BRIDGE_EXTRACT_DASHBOARD_DATA'
        ? { patient: { crNo: CR, identityConflict: true } }
        : (parsed = true, { ok: true })
    } }
  });
  await assert.rejects(run(CR), /conflicting CR/);
  assert.equal(parsed, false);
});

function identity(nodes, searchCr) {
  return isolate(bridge, 'patientIdentity', {
    findCrInput: () => searchCr ? { name: 'patCrNo', value: searchCr } : null,
    document: { querySelectorAll: () => nodes.map(innerText => ({ innerText })) },
    text: node => node.innerText
  })();
}

test('extracts inline CR No from patient display', () => {
  const result = identity(['CR No: ' + CR], null);
  assert.equal(result.crNo, CR);
  assert.equal(result.identitySource, 'patient_display');
});
test('flags stale report when returned displayed CR differs from searched CR', () => {
  const result = identity(['CR Number: 999999999999999'], CR);
  assert.equal(result.identityConflict, true);
  assert.equal(result.crNo, '999999999999999');
});
test('accepts consistent search and displayed CR', () => {
  const result = identity(['CR No: ' + CR], CR);
  assert.equal(result.identityConflict, false);
  assert.equal(result.crNo, CR);
});
