import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const shimSource = fs.readFileSync(path.join(here, '..', 'nimsWebviewShim.js'), 'utf8');

function domWithCore(core) {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', {
    url: 'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action',
    runScripts: 'outside-only',
  });
  dom.window.NimsReportCore = core;
  dom.window.eval(shimSource);
  return dom.window;
}

test('failed direct navigation stays in the authenticated shell contract', async () => {
  let navigationCalls = 0;
  const core = {
    openCrWiseResultsDirect() {
      return { ok: false, errorCode: 'cr_wise_menu_not_found' };
    },
    navigateToCrWiseReports() {
      navigationCalls += 1;
      return navigationCalls >= 2
        ? { ok: true, done: true, stage: 'cr_search' }
        : { ok: true, done: false, stage: 'home' };
    },
  };
  const win = domWithCore(core);
  await new Promise((resolve) => win.setTimeout(resolve, 5));

  const result = core.openCrWiseResultsDirect(win.document);
  assert.equal(result.ok, true);
  assert.equal(result.action, 'native_shell_navigation_started');
  assert.equal(result.fallbackFrom, 'cr_wise_menu_not_found');

  await new Promise((resolve) => win.setTimeout(resolve, 550));
  assert.ok(navigationCalls >= 2, 'the native shell navigation contract should be retried');
  win.close();
});

test('successful ticketed direct navigation remains unchanged', async () => {
  let fallbackCalls = 0;
  const core = {
    openCrWiseResultsDirect() {
      return { ok: true, action: 'navigated_direct_leaf' };
    },
    navigateToCrWiseReports() {
      fallbackCalls += 1;
      return { ok: true, done: true };
    },
  };
  const win = domWithCore(core);
  await new Promise((resolve) => win.setTimeout(resolve, 5));

  const result = core.openCrWiseResultsDirect(win.document);
  assert.equal(result.action, 'navigated_direct_leaf');
  assert.equal(fallbackCalls, 0);
  win.close();
});

test('manual login stops retries', async () => {
  let navigationCalls = 0;
  const core = {
    openCrWiseResultsDirect() {
      return { ok: false, errorCode: 'cr_wise_menu_not_found' };
    },
    navigateToCrWiseReports() {
      navigationCalls += 1;
      return { ok: false, done: false, errorCode: 'manual_login_required' };
    },
  };
  const win = domWithCore(core);
  await new Promise((resolve) => win.setTimeout(resolve, 5));

  core.openCrWiseResultsDirect(win.document);
  await new Promise((resolve) => win.setTimeout(resolve, 550));
  assert.equal(navigationCalls, 1);
  win.close();
});
