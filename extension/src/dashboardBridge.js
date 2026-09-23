(function () {
  "use strict";
  if (window.__KBP_NIMS_DASHBOARD_BRIDGE_INSTALLED__) return;
  window.__KBP_NIMS_DASHBOARD_BRIDGE_INSTALLED__ = true;

  function post(type, payload) {
    window.postMessage({ type, ...(payload || {}) }, window.location.origin);
  }

  async function send(type, payload) {
    try {
      return await chrome.runtime.sendMessage({ type, ...(payload || {}) });
    } catch (error) {
      return { ok: false, error: error && error.message ? error.message : "NIMS browser bridge unavailable." };
    }
  }

  async function emitStatus() {
    const response = await send("NIMS_DASHBOARD_STATUS");
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

  post("KBP_NIMS_BRIDGE_READY", { ready: true });
  emitStatus();
})();