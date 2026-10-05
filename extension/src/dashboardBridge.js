(function () {
  "use strict";
  if (window.__KBP_NIMS_DASHBOARD_BRIDGE_INSTALLED__) return;
  window.__KBP_NIMS_DASHBOARD_BRIDGE_INSTALLED__ = true;
  const BRIDGE_VERSION = chrome.runtime.getManifest().version;

  function bridgePayload(payload) {
    return { ...(payload || {}), version: BRIDGE_VERSION };
  }

  function post(type, payload) {
    window.postMessage({ type, ...bridgePayload(payload) }, window.location.origin);
  }

  function isTransientChannelError(error) {
    const message = String(error && error.message || "");
    return /message channel closed|port closed|receiving end does not exist/i.test(message);
  }

  function friendlyBridgeError(error) {
    if (isTransientChannelError(error)) {
      return "NIMS connector communication restarted. Retrying the browser-session check…";
    }
    return error && error.message ? error.message : "NIMS browser bridge unavailable.";
  }

  function delay(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  async function send(type, payload, options = {}) {
    const attempts = options.retryTransient ? 3 : 1;
    let lastError = null;
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      try {
        return await chrome.runtime.sendMessage({ type, ...(payload || {}) });
      } catch (error) {
        lastError = error;
        if (!isTransientChannelError(error) || attempt === attempts - 1) break;
        await delay(120 * (attempt + 1));
      }
    }
    return { ok: false, transient: isTransientChannelError(lastError), error: friendlyBridgeError(lastError) };
  }

  async function emitStatus() {
    const response = await send("NIMS_DASHBOARD_STATUS", null, { retryTransient: true });
    post("KBP_NIMS_STATUS", response || { ok: false, loggedIn: false });
  }

  window.addEventListener("message", async (event) => {
    if (event.source !== window || event.origin !== window.location.origin || !event.data) return;
    if (event.data.type === "KBP_NIMS_STATUS_REQUEST") {
      await emitStatus();
      return;
    }
    if (event.data.type === "KBP_NIMS_LOGIN_REQUEST") {
      const response = await send("NIMS_DASHBOARD_LOGIN");
      post("KBP_NIMS_LOGIN_RESPONSE", response || { ok: false });
      return;
    }
    if (event.data.type === "KBP_NIMS_FETCH_REQUEST") {
      const response = await send("NIMS_DASHBOARD_FETCH_CR", { crNo: String(event.data.crNo || "") });
      if (response && response.ok === false) {
        post("KBP_NIMS_FETCH_ERROR", { error: response.error || "NIMS retrieval failed." });
      }
    }
  });

  chrome.runtime.onMessage.addListener((message) => {
    if (!message || message.type !== "NIMS_DASHBOARD_EVENT") return false;
    post(message.eventType, message.payload || {});
    return false;
  });

  try { document.documentElement.setAttribute("data-nims-connector-ready", "1"); } catch {}
  post("KBP_NIMS_BRIDGE_READY", { ready: true });
  emitStatus();
})();