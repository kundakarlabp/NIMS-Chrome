import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const utils = require('../src/sidepanelUtils.js');

test('visible terminal stages win before actions', () => {
  assert.equal(utils.selectNavigationTarget([{ frameId: 1, visible: true, stage: 'session_expired' }, { frameId: 2, visible: true, actionable: true, stage: 'home' }]).frame.frameId, 1);
  assert.equal(utils.selectNavigationTarget([{ frameId: 3, visible: true, stage: 'cr_search' }]).kind, 'terminal');
  assert.equal(utils.selectNavigationTarget([{ frameId: 4, visible: true, stage: 'report_list' }]).kind, 'terminal');
});

test('visible action beats hidden report list and exactly one frame is selected', () => {
  const selected = utils.selectNavigationTarget([
    { frameId: 1, visible: false, stage: 'report_list', viewReportRows: 2 },
    { frameId: 2, visible: true, actionable: true, stage: 'investigation_menu', targetMethod: 'exact_id' },
    { frameId: 3, visible: true, actionable: true, stage: 'home', targetMethod: 'exact_onclick' }
  ]);
  assert.equal(selected.kind, 'action');
  assert.equal(selected.frame.frameId, 2);
});

test('no arbitrary unknown frame is selected', () => {
  assert.equal(utils.selectNavigationTarget([{ frameId: 1, visible: true, stage: 'unknown' }]), null);
});

test('lower depth and exact target win ties', () => {
  assert.equal(utils.selectNavigationTarget([
    { frameId: 4, visible: true, actionable: true, stage: 'home', depth: 2, targetMethod: 'exact_text' },
    { frameId: 5, visible: true, actionable: true, stage: 'home', depth: 1, targetMethod: 'exact_text' }
  ]).frame.frameId, 5);
  assert.equal(utils.selectNavigationTarget([
    { frameId: 6, visible: true, actionable: true, stage: 'investigation_menu', depth: 1, targetMethod: 'compatibility_fallback' },
    { frameId: 7, visible: true, actionable: true, stage: 'investigation_menu', depth: 2, targetMethod: 'exact_id' }
  ]).frame.frameId, 7);
});

test('deceptive and unsafe URLs are rejected', () => {
  assert.equal(utils.isAllowedNimsUrl('https://nimsts.edu.in.evil.example/AHIMSG5/'), false);
  assert.equal(utils.isAllowedNimsUrl('https://user:password@nimsts.edu.in/AHIMSG5/'), false);
  assert.equal(utils.isAllowedNimsUrl('https://nimsts.edu.in/HIS/%2e%2e/admin'), false);
});
