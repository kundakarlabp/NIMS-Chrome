import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const background = fs.readFileSync(new URL('../src/background.js', import.meta.url), 'utf8');

function isolate(name, env) {
  const index = background.search(new RegExp('\\b(?:async\\s+)?function\\s+' + name + '\\s*\\('));
  assert.ok(index >= 0, 'Missing ' + name);
  const rest = background.slice(index);
  const next = /\n[ \t]*(?:async\s+)?function\s+[A-Za-z]\w*\s*\(/.exec(rest.slice(1));
  const context = vm.createContext(env);
  vm.runInContext(rest.slice(0, next ? next.index + 1 : rest.length), context);
  return context[name];
}

function classifier() {
  return isolate('classifyReportResponse', {
    Uint8Array, TextDecoder,
    classificationResult: (classification, byteLength) => ({ classification, byteLength }),
    safeKeywords: () => [],
    hasReportValues: text => /creatinine/.test(text) && /2\\.4/.test(text)
  });
}

test('valid HTML lab report with Login audit text is not a session expiry', async () => {
  const classify = classifier();
  const detect = isolate('detectHtmlAuthFailure', { classifyReportResponse: classify });
  const html = new TextEncoder().encode(
    '<html><body>Laboratory Report. Last Login recorded. Creatinine: 2.4 mg/dL</body></html>'
  ).buffer;
  assert.equal(classify(html, 'text/html', 200, '').classification, 'html_report_content');
  assert.equal(await detect(html, 'text/html'), '');
});

test('explicit expired session is rejected instead of parsed as a report', async () => {
  const classify = classifier();
  const html = new TextEncoder().encode('<html>Session has expired. Please login again.</html>').buffer;
  assert.equal(classify(html, 'text/html', 200, '').classification, 'html_login_or_session');
});

test('login destination is rejected even with apparently valid report values', () => {
  const classify = classifier();
  const html = new TextEncoder().encode('<html>Creatinine: 2.4 mg/dL</html>').buffer;
  assert.equal(classify(html, 'text/html', 200, 'www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action').classification, 'html_login_or_session');
});
