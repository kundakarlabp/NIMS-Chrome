(function () {
  'use strict';
  if (window.__KBP_NIMS_DASHBOARD_BRIDGE_INSTALLED__) return;
  window.__KBP_NIMS_DASHBOARD_BRIDGE_INSTALLED__ = true;

  const BRIDGE_VERSION = chrome.runtime.getManifest().version;

  function bridgePayload(payload) {
    return { ...(payload || {}), version: BRIDGE_VERSION };
  }

  function post(type, payload) {
    window.postMessage({ type, ...bridgePayload(payload) }, window.location.origin);
  }

  async function send(type, payload) {
    try {
      return await chrome.runtime.sendMessage({ type, ...(payload || {}) });
    } catch (error) {
      return {
        ok: false,
        error: error && error.message ? error.message : 'NIMS browser bridge unavailable.'
      };
    }
  }

  async function emitStatus() {
    const response = await send('NIMS_DASHBOARD_STATUS');
    post('KBP_NIMS_STATUS', response || { ok: false, loggedIn: false });
  }

  function parseThroughDashboard(message, sendResponse) {
    const requestId = String(message.requestId || '');
    if (!requestId || !message.payload) {
      sendResponse({ ok: false, error: 'Invalid local parser request.' });
      return false;
    }

    let acknowledged = false;
    let finished = false;
    let retries = 0;

    const cleanup = () => {
      window.removeEventListener('message', onMessage);
      window.clearInterval(retryTimer);
      window.clearTimeout(timeoutTimer);
    };

    const complete = response => {
      if (finished) return;
      finished = true;
      cleanup();
      sendResponse(response);
    };

    const onMessage = event => {
      if (event.source !== window || event.origin !== window.location.origin || !event.data) return;
      if (String(event.data.requestId || '') !== requestId) return;

      if (event.data.type === 'KBP_NIMS_PARSE_ACK') {
        acknowledged = true;
        return;
      }
      if (event.data.type === 'KBP_NIMS_PARSE_RESPONSE') {
        if (event.data.ok === false) {
          complete({ ok: false, error: event.data.error || 'Local dashboard parsing failed.' });
        } else {
          complete({ ok: true, parsed: event.data.parsed });
        }
      }
    };

    const dispatch = () => {
      post('KBP_NIMS_PARSE_REQUEST', {
        requestId,
        payload: message.payload
      });
    };

    window.addEventListener('message', onMessage);
    dispatch();

    const retryTimer = window.setInterval(() => {
      if (finished || acknowledged) return;
      retries += 1;
      if (retries <= 8) dispatch();
    }, 750);

    const timeoutTimer = window.setTimeout(() => {
      complete({
        ok: false,
        error: acknowledged
          ? 'Dashboard report parsing timed out.'
          : 'Dashboard parser is not ready. Refresh the dashboard and retry.'
      });
    }, 45000);

    return true;
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
      const response = await send('NIMS_DASHBOARD_FETCH_CR', {
        crNo: String(event.data.crNo || '')
      });
      if (response && response.ok === false) {
        post('KBP_NIMS_FETCH_ERROR', {
          error: response.error || 'NIMS retrieval failed.'
        });
      }
    }
  });

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (!message) return false;

    if (message.type === 'NIMS_DASHBOARD_EVENT') {
      post(message.eventType, message.payload || {});
      return false;
    }
    if (message.type === 'NIMS_DASHBOARD_PARSE_REQUEST') {
      return parseThroughDashboard(message, sendResponse);
    }
    return false;
  });

  try {
    document.documentElement.setAttribute('data-nims-connector-ready', '1');
  } catch {}

  post('KBP_NIMS_BRIDGE_READY', { ready: true });
  emitStatus();
})();
