import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM, VirtualConsole } from 'jsdom';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const core = require('../nimsReportCore.js');

// Silence jsdom's "Not implemented: navigation" notice; the navigation side
// effect is intentional and asserted via the recorded ticketed URL instead.
function silentDom(html) {
  const vc = new VirtualConsole();
  return new JSDOM(html, {
    url: 'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action',
    virtualConsole: vc,
  });
}

function makeNims() {
  const dom = silentDom(
    `<!doctype html><html><body>
       <a id="Cr_No_Wise_Result_Report_Printing_New"
          onclick="callMenu('/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt','Cr No Wise Result Report Printing New')">CR</a>
     </body></html>`
  );
  const win = dom.window;
  win.__ticketedUrl = null;
  win.__originalAddTabCalled = 0;
  // Faithful page contract: callMenu appends the SSO ticket, then calls addTab.
  win.callMenu = function (url, menu) {
    const ticketed = url + (url.indexOf('?') === -1 ? '?' : '&') + 'ticket=SECRET';
    win.__ticketedUrl = ticketed;            // record the page-built ticketed URL
    win.addTab(menu, ticketed);              // whatever addTab is at call time
  };
  win.addTab = function () { win.__originalAddTabCalled += 1; }; // EasyUI path
  return win;
}

test('navigates top-level with the page-built ticketed URL and bypasses the EasyUI tab', () => {
  const win = makeNims();
  const result = core.openCrWiseResultsDirect(win.document);

  assert.equal(result.ok, true);
  assert.equal(result.action, 'navigated_direct_leaf');
  // The original EasyUI addTab must never run (our interceptor replaced it).
  assert.equal(win.__originalAddTabCalled, 0);
  // callMenu still ran the real ticket logic and produced the ticketed leaf URL.
  assert.match(win.__ticketedUrl, /viewcrnowisereportprocess\.cnt/);
  assert.match(win.__ticketedUrl, /ticket=SECRET/);
});

test('restores the original addTab after navigating', async () => {
  const win = makeNims();
  const sentinel = win.addTab;
  core.openCrWiseResultsDirect(win.document);
  await new Promise((r) => win.setTimeout(r, 5)); // allow the 0ms restore tick
  assert.equal(win.addTab, sentinel, 'addTab must be restored, not left patched');
});

test('fails closed when the CR menu anchor is absent', () => {
  const dom = silentDom('<!doctype html><html><body></body></html>');
  const result = core.openCrWiseResultsDirect(dom.window.document);
  assert.equal(result.ok, false);
  assert.equal(result.errorCode, 'cr_wise_menu_not_found');
});
