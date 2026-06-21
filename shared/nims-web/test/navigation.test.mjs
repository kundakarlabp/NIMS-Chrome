import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const corePath = '../../../shared/nims-web/nimsReportCore.js';

function loadCore(html, url = 'https://nimsts.edu.in/AHIMSG5/home') {
  const dom = new JSDOM(html, { url, runScripts: 'dangerously' });
  dom.window.menuSelected = () => {};
  dom.window.callMenu = () => {};
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.location = dom.window.location;
  delete require.cache[require.resolve(corePath)];
  const core = require(corePath);
  return { dom, core };
}

const crMenu = `<a id="Cr_No_Wise_Result_Report_Printing_New" onclick="callMenu('/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt','Cr_No_Wise_Result_Report_Printing_New')">Cr No Wise Result Report Printing New</a>`;
const crForm = `<form name="viewExternalInvFB" method="post" action="/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt"><label>CR Number</label><input type="hidden" name="hmode" value="SHOWPATDETAILS"><input name="patCrNo" maxlength="15" value="SHOULD-STAY"><button>Go</button></form>`;

test('selects exact Investigation onclick', () => {
  const { core } = loadCore(`<!doctype html><button onclick="menuSelected('Investigation', true)">Investigation</button><button onclick="menuSelected('Investigation Enquiry', true)">Investigation Enquiry</button>`);
  const target = core.findInvestigationModuleTarget(document);
  assert.equal(target.ok, true);
  assert.equal(target.method, 'exact_onclick');
});

test('Investigation exact-text fallback works inside table and Enquiry is not selected', () => {
  const { core } = loadCore(`<!doctype html><table><tr><td><a href="#">Investigation</a></td><td><a href="#">Investigation Enquiry</a></td></tr></table>`);
  const target = core.findInvestigationModuleTarget(document);
  assert.equal(target.ok, true);
  assert.equal(target.method, 'exact_text');
  assert.equal(target.element.textContent, 'Investigation');
});

test('selects exact CR-wise New target by ID and ignores old non-New label', () => {
  const { core } = loadCore(`<!doctype html>${crMenu}<a id="old">Cr No Wise Result Report Printing</a>`);
  const target = core.findCrWiseReportMenuTarget(document);
  assert.equal(target.ok, true);
  assert.equal(target.method, 'exact_id');
});

test('selects canonical endpoint onclick inside table', () => {
  const { core } = loadCore(`<!doctype html><table><tr><td><a onclick="callMenu('/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt','x')">Cr No Wise Result Report Printing New</a></td></tr></table>`);
  const target = core.findCrWiseReportMenuTarget(document);
  assert.equal(target.ok, true);
  assert.equal(target.method, 'exact_endpoint');
});

test('old non-New CR-wise menu is not selected', () => {
  const { core } = loadCore(`<!doctype html><a id="Cr_No_Wise_Result_Report_Printing" onclick="callMenu('/HISInvestigationG5/new_investigation/viewold.cnt','Cr_No_Wise_Result_Report_Printing')">Cr No Wise Result Report Printing</a>`);
  assert.equal(core.findCrWiseReportMenuTarget(document).ok, false);
});

test('real patCrNo form is classified as cr_search and wins over CR-wise menu', () => {
  const { core } = loadCore(`<!doctype html>${crMenu}${crForm}`, 'https://nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt?ignored=1');
  assert.equal(core.detectCurrentDocumentStage(document).stage, 'cr_search');
  assert.equal(core.detectNimsPageStage(document).stage, 'cr_search');
});

test('loginLogin.action without credential fields is not login, with genuine credentials is login', () => {
  let loaded = loadCore(`<!doctype html><h1>NIMS authenticated shell</h1><button onclick="menuSelected('Investigation', true)">Investigation</button>`, 'https://nimsts.edu.in/AHIMSG5/hissso/loginLogin.action');
  assert.equal(loaded.core.detectCurrentDocumentStage(document).stage, 'home');
  loaded = loadCore(`<!doctype html><form><label>User ID</label><input name="userName"><label>Password</label><input type="password"><button>Login</button></form>`, 'https://nimsts.edu.in/AHIMSG5/hissso/loginLogin.action');
  assert.equal(loaded.core.detectCurrentDocumentStage(document).stage, 'login');
});

test('hidden stale report-list iframe does not override visible CR search', () => {
  const { core } = loadCore(`<!doctype html>${crForm}<iframe id="old" style="display:none"></iframe>`, 'https://nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt');
  document.getElementById('old').contentDocument.body.innerHTML = `<table><tr><td>CBC</td><td><button onclick="printReport('old')">View Report</button></td></tr></table>`;
  assert.equal(core.detectNimsPageStage(document).stage, 'cr_search');
});

test('legacy visible iframe is not rejected solely because client rect is empty', () => {
  const { core } = loadCore(`<!doctype html><iframe id="active"></iframe>`);
  const child = document.getElementById('active').contentDocument;
  child.open();
  child.write(`<!doctype html>${crForm}`);
  child.close();
  assert.equal(core.detectNimsPageStage(document).stage, 'cr_search');
});

test('one navigation step performs at most one action and first CR-wise click is provisional', () => {
  const { core } = loadCore(`<!doctype html><a id="Cr_No_Wise_Result_Report_Printing_New" onclick="window.clicked=(window.clicked||0)+1; callMenu('/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt','Cr_No_Wise_Result_Report_Printing_New')">Cr No Wise Result Report Printing New</a>`);
  const first = core.navigateToCrWiseReports(document);
  assert.equal(first.action, 'clicked_cr_wise_menu');
  assert.equal(first.done, false);
  assert.equal(window.clicked, 1);
});

test('if still in Investigation after provisional CR-wise click, canonical fallback is used', () => {
  const { core } = loadCore(`<!doctype html>${crMenu}`);
  const first = core.navigateToCrWiseReports(document);
  const second = core.navigateToCrWiseReports(document);
  assert.equal(first.action, 'clicked_cr_wise_menu');
  assert.equal(second.action, 'canonical_endpoint_fallback');
  assert.equal(second.canonicalFallbackAttempted, true);
  assert.equal(second.safePath, 'nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt');
});

test('canonical fallback resolves only to approved NIMS HTTPS origin', () => {
  const { core } = loadCore(`<!doctype html>`);
  assert.equal(core.resolveCanonicalCrWiseUrl('https://nimsts.edu.in/AHIMSG5/home').ok, true);
  assert.equal(core.resolveCanonicalCrWiseUrl('https://www.nimsts.edu.in/AHIMSG5/home').ok, true);
  assert.equal(core.resolveCanonicalCrWiseUrl('http://nimsts.edu.in/AHIMSG5/home').ok, false);
  assert.equal(core.resolveCanonicalCrWiseUrl('https://evil.example/AHIMSG5/home').ok, false);
  assert.equal(core.resolveCanonicalCrWiseUrl('not a url').ok, false);
  assert.equal(core.resolveCanonicalCrWiseUrl('javascript:alert(1)').ok, false);
});

test('CR input value is never read, cleared, modified or submitted', () => {
  const { core } = loadCore(`<!doctype html>${crForm}`, 'https://nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt');
  let submitted = false;
  document.querySelector('form').addEventListener('submit', () => { submitted = true; });
  const result = core.navigateCurrentDocumentStep(document);
  assert.equal(result.stage, 'cr_search');
  assert.equal(result.done, true);
  assert.equal(document.querySelector('[name="patCrNo"]').value, 'SHOULD-STAY');
  assert.equal(submitted, false);
});

test('safe diagnostics exclude CR value, query strings, onclick code and hidden values', () => {
  const { core } = loadCore(`<!doctype html><button onclick="menuSelected('Investigation', true)">Investigation</button><input name="patCrNo" value="SECRET"><input type="hidden" name="hmode" value="SHOWPATDETAILS">`, 'https://nimsts.edu.in/AHIMSG5/home?token=SECRET');
  const serialized = JSON.stringify(core.getCurrentDocumentNavigationDiagnostic(document));
  assert.equal(serialized.includes('token=SECRET'), false);
  assert.equal(serialized.includes('menuSelected'), false);
  assert.equal(serialized.includes('SECRET'), false);
  assert.equal(serialized.includes('SHOWPATDETAILS'), false);
});

test('once cr_search is detected, no additional click or fallback occurs', () => {
  const { core } = loadCore(`<!doctype html>${crMenu}${crForm}`, 'https://nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt');
  const result = core.navigateToCrWiseReports(document);
  assert.equal(result.stage, 'cr_search');
  assert.equal(result.action, 'none');
  assert.equal(result.canonicalFallbackAttempted, false);
});

test('report list requires genuine View Report rows', () => {
  const { core } = loadCore(`<!doctype html><table><tr><td>01-Jan-2026</td><td>CBC</td><td><button onclick="printReport('fixture')">View Report</button></td></tr></table>`);
  assert.equal(core.detectCurrentDocumentStage(document).stage, 'report_list');
});

test('visible session expired is terminal over action target', () => {
  const { core } = loadCore(`<!doctype html><p>Session expired. Login required.</p><button onclick="menuSelected('Investigation', true)">Investigation</button>`);
  const result = core.navigateCurrentDocumentStep(document);
  assert.equal(result.stage, 'session_expired');
  assert.equal(result.errorCode, 'session_expired');
});

const g5Shell = `<!doctype html><body>
  <div>e-Sushrut G-5 Nizam's Institute of Medical Sciences</div>
  <div>Welcome, Kundakarla Bhanu Prasad</div>
  <nav><span>Registration</span><span>OPD</span><span>ADT</span><span class="active">Investigation</span><span>PIS</span><span>IPD</span><span>HEMS</span><span>Inventory</span><span>Tariff Search</span><span>MIS Reports</span></nav>
  <div>Home Menu</div>
</body>`;

test('live e-Sushrut G-5 shell without a menuSelected button is detected as home, not login', () => {
  const { core } = loadCore(g5Shell, 'https://nimsts.edu.in/AHIMSG5/hissso/loginLogin.action');
  assert.equal(core.detectCurrentDocumentStage(document).stage, 'home');
});

test('G-5 shell with a lingering password field is still home, not login', () => {
  const { core } = loadCore(g5Shell + '<form><input type="password"><input type="text" name="user"><button>Login</button></form>', 'https://nimsts.edu.in/AHIMSG5/home');
  assert.equal(core.detectCurrentDocumentStage(document).stage, 'home');
});

test('home with unresponsive Investigation menu falls back to canonical CR endpoint on retry', () => {
  const { core } = loadCore(g5Shell, 'https://nimsts.edu.in/AHIMSG5/home');
  const first = core.navigateToCrWiseReports(document);
  const second = core.navigateToCrWiseReports(document);
  assert.equal(first.action, 'clicked_investigation_module');
  assert.equal(second.action, 'canonical_endpoint_fallback');
  assert.equal(second.canonicalFallbackAttempted, true);
  assert.equal(second.safePath, 'nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt');
});

test('home with no Investigation control and no menu function falls back to canonical immediately', () => {
  const { core, dom } = loadCore(g5Shell, 'https://nimsts.edu.in/AHIMSG5/home');
  delete dom.window.menuSelected;
  const result = core.navigateToCrWiseReports(document);
  assert.equal(result.action, 'canonical_endpoint_fallback');
  assert.equal(result.canonicalFallbackAttempted, true);
});

test('genuine login page is still classified login (shell guard does not over-trigger)', () => {
  const { core } = loadCore(`<!doctype html><form><label>User ID</label><input name="userName"><label>Password</label><input type="password"><button>Login</button></form>`, 'https://nimsts.edu.in/AHIMSG5/hissso/loginLogin.action');
  assert.equal(core.detectCurrentDocumentStage(document).stage, 'login');
});
