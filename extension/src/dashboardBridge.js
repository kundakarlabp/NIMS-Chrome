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
    if (event.data.type === "KBP_NIMS_HELPER_STATUS_REQUEST") {
      const settings = await send("NIMS_GET_HELPER_SETTINGS");
      const health = settings && settings.ok ? await send("NIMS_HELPER_HEALTH") : { ok: false, error: "Helper settings unavailable." };
      post("KBP_NIMS_HELPER_STATUS", {
        ok: Boolean(settings && settings.ok),
        settings: settings && settings.settings ? settings.settings : null,
        health: health ? { ok: Boolean(health.ok), error: health.error || "" } : { ok: false, error: "Helper health unavailable." }
      });
      return;
    }
    if (event.data.type === "KBP_NIMS_HELPER_SAVE_REQUEST") {
      const response = await send("NIMS_SAVE_HELPER_SETTINGS", {
        settings: {
          mode: "remote",
          url: String(event.data.url || ""),
          apiKey: String(event.data.apiKey || "")
        }
      });
      const health = response && response.ok ? await send("NIMS_HELPER_HEALTH") : { ok: false, error: response && response.error ? response.error : "Helper settings were not saved." };
      post("KBP_NIMS_HELPER_SAVE_RESPONSE", {
        ok: Boolean(response && response.ok),
        settings: response && response.settings ? response.settings : null,
        health: health ? { ok: Boolean(health.ok), error: health.error || "" } : { ok: false, error: "Helper health unavailable." },
        error: response && response.error ? response.error : ""
      });
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