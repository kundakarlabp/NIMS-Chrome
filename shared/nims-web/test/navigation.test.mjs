import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const corePath = '../../../shared/nims-web/nimsReportCore.js';

function loadCore(html, url = 'https://nimsts.edu.in/AHIMSG5/home') {
  const dom = new JSDOM(html, { url, runScripts: 'dangerously' });
  dom.window.menuSelected = () => {};
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.location = dom.window.location;
  delete require.cache[require.resolve(corePath)];
  const core = require(corePath);
  return { dom, core };
}

test('selects exact Investigation onclick and ignores Enquiry', () => {
  const { core } = loadCore(`<!doctype html><button onclick="menuSelected('Investigation', true)">Investigation</button><button onclick="menuSelected('Investigation Enquiry', true)">Investigation Enquiry</button>`);
  const target = core.findInvestigationModuleTarget(document);
  assert.equal(target.ok, true);
  assert.equal(target.method, 'exact_onclick');
});

test('selects exact CR-wise New target and ignores old non-New label', () => {
  const { core } = loadCore(`<!doctype html><a id="Cr_No_Wise_Result_Report_Printing_New" onclick="callMenu('/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt','Cr_No_Wise_Result_Report_Printing_New')">Cr No Wise Result Report Printing New</a><a>Cr No Wise Result Report Printing</a>`);
  const target = core.findCrWiseReportMenuTarget(document);
  assert.equal(target.ok, true);
  assert.equal(target.method, 'exact_id');
});

test('CR search is distinct from report list and preserves CR value/submission', () => {
  const { core } = loadCore(`<!doctype html><h1>CR Wise Result Report Printing</h1><label for="crNo">CR No</label><input id="crNo" name="crNo" value="SHOULD-STAY"><button id="search">Search</button>`, 'https://nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt?ignored=1');
  let submitted = false;
  document.getElementById('search').addEventListener('click', () => { submitted = true; });
  const result = core.navigateCurrentDocumentStep(document);
  assert.equal(result.stage, 'cr_search');
  assert.equal(result.done, true);
  assert.equal(document.getElementById('crNo').value, 'SHOULD-STAY');
  assert.equal(submitted, false);
});

test('report list requires genuine View Report rows', () => {
  const { core } = loadCore(`<!doctype html><table><tr><td>01-Jan-2026</td><td>CBC</td><td><button onclick="printReport('fixture')">View Report</button></td></tr></table>`);
  assert.equal(core.detectCurrentDocumentStage(document).stage, 'report_list');
});

test('one call performs at most one click and cooldown prevents duplicate click', () => {
  const { core } = loadCore(`<!doctype html><button id="investigation" onclick="window.clicked=(window.clicked||0)+1">Investigation</button>`);
  document.getElementById('investigation').setAttribute('onclick', "window.clicked=(window.clicked||0)+1; menuSelected('Investigation', true)");
  let first = core.navigateCurrentDocumentStep(document);
  let second = core.navigateCurrentDocumentStep(document);
  assert.equal(first.action, 'clicked_investigation_module');
  assert.equal(second.action, 'cooldown');
  assert.equal(window.clicked, 1);
});

test('hidden stale report-list frame does not override visible CR search', () => {
  const { core } = loadCore(`<!doctype html><h1>CR Wise Result Report Printing</h1><label for="crNo">CR No</label><input id="crNo" name="crNo"><iframe id="old" style="display:none"></iframe>`, 'https://nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt');
  const frame = document.getElementById('old');
  frame.contentDocument.body.innerHTML = `<table><tr><td>CBC</td><td><button onclick="printReport('old')">View Report</button></td></tr></table>`;
  const detected = core.detectNimsPageStage(document);
  assert.equal(detected.stage, 'cr_search');
});

test('visible session expired is terminal over action target', () => {
  const { core } = loadCore(`<!doctype html><p>Session expired. Login required.</p><button onclick="menuSelected('Investigation', true)">Investigation</button>`);
  const result = core.navigateCurrentDocumentStep(document);
  assert.equal(result.stage, 'session_expired');
  assert.equal(result.errorCode, 'session_expired');
});

test('safe diagnostics exclude query strings, raw onclick and form values', () => {
  const { core } = loadCore(`<!doctype html><button onclick="menuSelected('Investigation', true)">Investigation</button><input name="crNo" value="SECRET">`, 'https://nimsts.edu.in/AHIMSG5/home?token=SECRET');
  const diagnostic = core.getCurrentDocumentNavigationDiagnostic(document);
  const serialized = JSON.stringify(diagnostic);
  assert.equal(serialized.includes('token=SECRET'), false);
  assert.equal(serialized.includes('menuSelected'), false);
  assert.equal(serialized.includes('SECRET'), false);
});
