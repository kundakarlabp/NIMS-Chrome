(() => {
  'use strict';
  if (window.__KBP_NIMS_DASHBOARD_BRIDGE_INSTALLED__) return;
  window.__KBP_NIMS_DASHBOARD_BRIDGE_INSTALLED__ = true;

  const version = chrome.runtime.getManifest().version;

  const post = (type, payload = {}) => {
    window.postMessage({ type, ...payload, version }, window.location.origin);
  };

  const transient = (error) =>
    /message channel closed|port closed|receiving end does not exist/i.test(String(error?.message || ''));

  async function send(type, payload = {}, retry = false) {
    const attempts = retry ? 3 : 1;
    let lastError = null;
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      try {
        return await chrome.runtime.sendMessage({ type, ...payload });
      } catch (error) {
        lastError = error;
        if (!transient(error) || attempt === attempts - 1) break;
        await new Promise(resolve => setTimeout(resolve, 120 * (attempt + 1)));
      }
    }
    return {
      ok: false,
      transient: transient(lastError),
      error: transient(lastError)
        ? 'NIMS connector communication restarted. Retrying the session check…'
        : String(lastError?.message || 'NIMS browser bridge unavailable.')
    };
  }

  async function emitStatus() {
    const response = await send('NIMS_DASHBOARD_STATUS', {}, true);
    post('KBP_NIMS_STATUS', response || { ok: false, loggedIn: false });
  }

  window.addEventListener('message', async event => {
    if (event.source !== window || event.origin !== window.location.origin || !event.data) return;

    if (event.data.type === 'KBP_NIMS_PING') {
      post('KBP_NIMS_PONG', { ready: true });
      return;
    }
    if (event.data.type === 'KBP_NIMS_STATUS_REQUEST') {
      await emitStatus();
      return;
    }
    if (event.data.type === 'KBP_NIMS_LOGIN_REQUEST') {
      const response = await send('NIMS_DASHBOARD_LOGIN');
      post('KBP_NIMS_LOGIN_RESPONSE', response || { ok: false });
      return;
    }
    if (event.data.type === 'KBP_NIMS_FETCH_REQUEST') {
      const crNo = String(event.data.crNo || '');
      if (!/^\d{15}$/.test(crNo)) {
        post('KBP_NIMS_FETCH_ERROR', { error: 'Enter the 15-digit NIMS CR number.' });
        return;
      }
      const response = await send('NIMS_DASHBOARD_FETCH_CR', { crNo });
      if (response && response.ok === false) {
        post('KBP_NIMS_FETCH_ERROR', { error: response.error || 'NIMS retrieval failed.' });
      }
    }
  });

  chrome.runtime.onMessage.addListener(message => {
    if (!message || message.type !== 'NIMS_DASHBOARD_EVENT') return false;
    post(message.eventType, message.payload || {});
    return false;
  });

  try {
    document.documentElement.setAttribute('data-nims-connector-ready', '1');
  } catch {}

  post('KBP_NIMS_BRIDGE_READY', { ready: true });
  emitStatus();
})();
