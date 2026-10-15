(() => {
  'use strict';
  if (window.__KBP_ANDROID_DASHBOARD_BRIDGE__) return;
  window.__KBP_ANDROID_DASHBOARD_BRIDGE__ = true;
  const VERSION = '0.7.0';

  const emit = (type, payload = {}) => {
    try {
      window.postMessage({ type, version: VERSION, platform: 'android', ...payload }, window.location.origin);
    } catch {}
  };
  window.__KBP_ANDROID_EMIT = emit;

  window.addEventListener('message', (event) => {
    if (event.source !== window || event.origin !== window.location.origin || !event.data) return;
    const type = String(event.data.type || '');
    try {
      if (type === 'KBP_NIMS_PING') {
        AndroidDashboard.ping();
      } else if (type === 'KBP_NIMS_STATUS_REQUEST') {
        AndroidDashboard.status();
      } else if (type === 'KBP_NIMS_LOGIN_REQUEST') {
        AndroidDashboard.login();
      } else if (type === 'KBP_NIMS_FETCH_REQUEST') {
        AndroidDashboard.fetch(String(event.data.crNo || ''), String(event.data.requestId || ''));
      } else if (type === 'KBP_NIMS_HELPER_STATUS_REQUEST') {
        AndroidDashboard.helperStatus();
      } else if (type === 'KBP_NIMS_HELPER_SAVE_REQUEST') {
        AndroidDashboard.helperSave();
      }
    } catch {}
  });

  document.addEventListener('click', (event) => {
    try {
      const anchor = event.target && event.target.closest ? event.target.closest('a[href]') : null;
      if (!anchor) return;
      const url = new URL(anchor.href, window.location.href);
      if (url.protocol !== 'https:') return;
      if (url.hostname === 'nimsts.edu.in' || url.hostname === 'www.nimsts.edu.in') {
        event.preventDefault();
        event.stopPropagation();
        AndroidDashboard.openUrl(url.href);
      }
    } catch {}
  }, true);

  try {
    document.documentElement.setAttribute('data-nims-connector-ready', '1');
    document.documentElement.dataset.nimsConnector = 'android';
  } catch {}
  emit('KBP_NIMS_BRIDGE_READY', { ready: true });
  try { window.dispatchEvent(new Event('KBP_NIMS_CONNECTOR_READY')); } catch {}
})();