importScripts('bulkNormalizer.js', 'nimsRestApi.js');

const DASHBOARD_FILTER = 'https://nims-results-cockpit-ne5nig.v2.appdeploy.ai/*';
const LOGIN_URL = 'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action';
const NIMS_URL_FILTERS = [
  'https://nimsts.edu.in/AHIMSG5/*',
  'https://www.nimsts.edu.in/AHIMSG5/*',
  'https://nimsts.edu.in/HISInvestigationG5/*',
  'https://www.nimsts.edu.in/HISInvestigationG5/*',
  'https://nimsts.edu.in/HIS/*',
  'https://www.nimsts.edu.in/HIS/*',
  'https://nimsts.edu.in/hislogin/*',
  'https://www.nimsts.edu.in/hislogin/*',
  'https://nimsts.edu.in/HISUtilities/*',
  'https://www.nimsts.edu.in/HISUtilities/*',
  'https://nimsts.edu.in/HBIMS/*',
  'https://www.nimsts.edu.in/HBIMS/*'
];

const pendingBulk = new Map();

function safeError(error, fallback = 'NIMS connector operation failed.') {
  return { ok: false, error: String(error?.message || fallback), code: error?.code || '' };
}

function respondAsync(promise, reply, fallback) {
  Promise.resolve(promise)
    .then(value => reply(value === undefined ? { ok: true } : value))
    .catch(error => reply(safeError(error, fallback)));
  return true;
}

async function dashboardTabs() {
  return chrome.tabs.query({ url: DASHBOARD_FILTER });
}

async function pushDashboardEvent(eventType, payload = {}) {
  const tabs = await dashboardTabs();
  await Promise.all(tabs.map(tab => tab.id
    ? chrome.tabs.sendMessage(tab.id, { type: 'NIMS_DASHBOARD_EVENT', eventType, payload }).catch(() => {})
    : Promise.resolve()));
}

async function probeNimsTab(tabId) {
  try {
    const response = await chrome.tabs.sendMessage(tabId, { type: 'NIMS_BULK_PROBE' });
    return response?.ok ? response.state : null;
  } catch {
    return null;
  }
}

async function getDashboardSessionStatus() {
  const tabs = await chrome.tabs.query({ url: NIMS_URL_FILTERS });
  let loginTab = null;
  for (const tab of tabs) {
    if (!tab.id) continue;
    const state = await probeNimsTab(tab.id);
    if (state?.authenticated) {
      return {
        ok: true,
        loggedIn: true,
        state: 'ready',
        tabId: tab.id,
        url: tab.url || ''
      };
    }
    if (state?.loginForm || state?.sessionExpired) loginTab = tab;
  }
  return {
    ok: true,
    loggedIn: false,
    state: loginTab ? 'logged_out' : 'idle',
    tabId: loginTab?.id || null
  };
}

async function openDashboardLogin(sender) {
  const status = await getDashboardSessionStatus();
  if (status.loggedIn && status.tabId) {
    await chrome.tabs.update(status.tabId, { active: true }).catch(() => {});
    if (sender?.tab?.windowId) {
      await chrome.windows?.update?.(sender.tab.windowId, { focused: true }).catch(() => {});
    }
    return { ok: true, loggedIn: true, state: 'ready' };
  }

  const tabs = await chrome.tabs.query({ url: NIMS_URL_FILTERS });
  const existingLogin = tabs.find(tab => /loginLogin\.action/i.test(String(tab.url || '')));
  if (existingLogin?.id) {
    await chrome.tabs.update(existingLogin.id, { active: true }).catch(() => {});
    return { ok: true, loggedIn: false, state: 'signing_in', tabId: existingLogin.id };
  }

  const created = await chrome.tabs.create({ url: LOGIN_URL, active: true });
  return { ok: true, loggedIn: false, state: 'signing_in', tabId: created.id || null };
}

async function handleSessionState(state, sender) {
  if (!sender?.tab?.id) return;
  if (state?.authenticated) {
    await pushDashboardEvent('KBP_NIMS_STATUS', {
      ok: true,
      loggedIn: true,
      state: 'ready'
    });
  } else if (state?.loginForm || state?.sessionExpired) {
    await pushDashboardEvent('KBP_NIMS_STATUS', {
      ok: true,
      loggedIn: false,
      state: 'logged_out'
    });
  }
}

function createRequestId() {
  return 'bulk-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
}

function waitForBulkCapture(tabId, requestId, timeoutMs = 40000) {
  const previous = pendingBulk.get(tabId);
  if (previous) {
    clearTimeout(previous.timer);
    previous.reject(new Error('A newer NIMS bulk request replaced the previous request.'));
  }

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pendingBulk.delete(tabId);
      reject(new Error('NIMS Custom Sheet bulk API response was not captured.'));
    }, timeoutMs);
    pendingBulk.set(tabId, { requestId, resolve, reject, timer });
  });
}

function acceptBulkCapture(message, sender) {
  const tabId = sender?.tab?.id;
  if (!tabId) return false;
  const pending = pendingBulk.get(tabId);
  if (!pending || pending.requestId !== String(message.requestId || '')) return false;
  clearTimeout(pending.timer);
  pendingBulk.delete(tabId);
  pending.resolve(message.body);
  return true;
}

async function optionalReportMetadata(crNo) {
  if (!self.NimsRestApi?.fetchReportList) return [];
  try {
    const timeout = new Promise(resolve => setTimeout(() => resolve(null), 3500));
    const request = self.NimsRestApi.fetchReportList(crNo, fetch, '33101');
    const normalized = await Promise.race([request, timeout]);
    if (!normalized) return [];
    return (normalized.reports || []).map(report => ({
      id: report.id,
      title: report.title,
      date: report.date,
      url: report.url || (report.token && self.NimsRestApi.verifiedReportUrlForToken
        ? self.NimsRestApi.verifiedReportUrlForToken(report.token)
        : ''),
      token: report.token || ''
    }));
  } catch (error) {
    if (error?.code === 'NIMS_IDENTITY_MISMATCH') throw error;
    return [];
  }
}

async function fetchBulkResults(tabId, crNo) {
  const opened = await chrome.tabs.sendMessage(tabId, { type: 'NIMS_BULK_OPEN_TRENDS' });
  if (!opened?.ok) throw new Error(opened?.error || 'Unable to open NIMS Investigation Trends.');

  const requestId = createRequestId();
  const capturePromise = waitForBulkCapture(tabId, requestId);

  let run;
  try {
    run = await chrome.tabs.sendMessage(tabId, {
      type: 'NIMS_BULK_RUN_CUSTOM',
      crNo,
      requestId
    });
  } catch (error) {
    const pending = pendingBulk.get(tabId);
    if (pending?.requestId === requestId) {
      clearTimeout(pending.timer);
      pendingBulk.delete(tabId);
      pending.reject(error);
    }
    throw error;
  }

  if (!run?.ok) {
    const pending = pendingBulk.get(tabId);
    if (pending?.requestId === requestId) {
      clearTimeout(pending.timer);
      pendingBulk.delete(tabId);
      pending.reject(new Error(run?.error || 'NIMS Custom Sheet retrieval failed.'));
    }
    throw new Error(run?.error || 'NIMS Custom Sheet retrieval failed.');
  }

  const rawBulk = await capturePromise;
  const reportIndex = await optionalReportMetadata(crNo);
  return self.NimsBulkNormalizer.normalizeBulk(rawBulk, crNo, {
    patient: run.value?.patient || { crNo },
    reportIndex
  });
}

async function fetchCrForDashboard(rawCrNo, sender) {
  const crNo = String(rawCrNo || '').replace(/\D/g, '');
  if (!/^\d{15}$/.test(crNo)) return { ok: false, error: 'Enter the 15-digit NIMS CR number.' };

  const session = await getDashboardSessionStatus();
  if (!session.loggedIn || !session.tabId) {
    await openDashboardLogin(sender);
    await pushDashboardEvent('KBP_NIMS_AUTH_REQUIRED', {
      state: 'signing_in',
      preferredSource: 'nims_bulk_api'
    });
    return { ok: true, pendingAuth: true };
  }

  await pushDashboardEvent('KBP_NIMS_FETCH_STARTED', {
    crNo,
    preferredSource: 'nims_bulk_api'
  });

  try {
    const bundle = await fetchBulkResults(session.tabId, crNo);
    await pushDashboardEvent('KBP_NIMS_BULK_RESULTS', { payload: bundle });
    return {
      ok: true,
      source: 'nims_bulk_api',
      resultCount: bundle.results.length,
      reportCount: bundle.reports.length
    };
  } catch (error) {
    const message = String(error?.message || 'NIMS bulk retrieval failed.');
    if (/session expired|sign in again|authentication required/i.test(message)) {
      await openDashboardLogin(sender);
      await pushDashboardEvent('KBP_NIMS_AUTH_REQUIRED', {
        state: 'signing_in',
        reason: 'session_expired',
        preferredSource: 'nims_bulk_api'
      });
      return { ok: true, pendingAuth: true };
    }
    await pushDashboardEvent('KBP_NIMS_FETCH_ERROR', { error: message });
    return safeError(error, 'NIMS bulk retrieval failed.');
  }
}

chrome.runtime.onMessage.addListener((message, sender, reply) => {
  if (!message?.type) return false;

  if (message.type === 'NIMS_BULK_CAPTURE') {
    reply({ ok: acceptBulkCapture(message, sender) });
    return false;
  }

  if (message.type === 'NIMS_SESSION_STATE') {
    void handleSessionState(message.state || {}, sender).catch(() => {});
    return false;
  }

  if (message.type === 'NIMS_DASHBOARD_STATUS') {
    return respondAsync(getDashboardSessionStatus(), reply, 'Unable to check the NIMS browser session.');
  }

  if (message.type === 'NIMS_DASHBOARD_LOGIN') {
    return respondAsync(openDashboardLogin(sender), reply, 'Unable to open the NIMS login window.');
  }

  if (message.type === 'NIMS_DASHBOARD_FETCH_CR') {
    return respondAsync(fetchCrForDashboard(message.crNo, sender), reply, 'NIMS bulk retrieval failed.');
  }

  return false;
});
