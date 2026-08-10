import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { JSDOM } from 'jsdom';

const shimSource = fs.readFileSync(new URL('../nimsWebviewShim.js', import.meta.url), 'utf8');

function loadShim(html, url = 'https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt') {
  const dom = new JSDOM(html, { url, runScripts: 'dangerously' });
  dom.window.eval(shimSource);
  return dom;
}

test('arbitrary portal text field is never treated as CR input', () => {
  const dom = loadShim(
    '<!doctype html><input type="text" name="search"><button type="button">Search</button>',
    'https://www.nimsts.edu.in/AHIMSG5/home'
  );
  assert.equal(dom.window.__nimsCrFieldReady(), false);
  const result = dom.window.__nimsSubmitCrNumber('331012100872674');
  assert.equal(result.ok, false);
  assert.equal(result.reason, 'cr_field_not_ready');
});

test('real patCrNo form is detected and Go is clicked with the requested CR', () => {
  const dom = loadShim(`<!doctype html>
    <form name="viewExternalInvFB" action="/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt">
      <input type="hidden" name="hmode" value="SHOWPATDETAILS">
      <input name="patCrNo" maxlength="15" value="">
      <button type="button" onclick="window.__goClicks=(window.__goClicks||0)+1">Go</button>
    </form>`);

  assert.equal(dom.window.__nimsCrFieldReady(), true);
  const result = dom.window.__nimsSubmitCrNumber('3310-121-00872674');
  assert.equal(result.ok, true);
  assert.equal(result.reason, 'clicked_cr_submit');
  assert.equal(dom.window.document.querySelector('[name="patCrNo"]').value, '331012100872674');
  assert.equal(dom.window.__goClicks, 1);
});

test('validated CR form fallback accepts a single text field only inside CR context', () => {
  const dom = loadShim(`<!doctype html>
    <form name="viewExternalInvFB" action="/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt">
      <input type="hidden" name="hmode" value="SHOWPATDETAILS">
      <input type="text" name="patientIdentifier">
      <button type="button" onclick="window.__goClicks=(window.__goClicks||0)+1">Go</button>
    </form>`);

  assert.equal(dom.window.__nimsCrFieldReady(), true);
  const result = dom.window.__nimsSubmitCrNumber('331012100872674');
  assert.equal(result.ok, true);
  assert.equal(dom.window.__goClicks, 1);
});
