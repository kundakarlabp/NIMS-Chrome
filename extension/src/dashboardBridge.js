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

  function transient(error) {
    return /message channel closed|port closed|receiving end does not exist/i.test(String(error && error.message || ""));
  }

  async function send(type, payload, retry) {
    const attempts = retry ? 3 : 1;
    let lastError = null;
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      try {
        return await chrome.runtime.sendMessage({ type, ...(payload || {}) });
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
        ? "NIMS connector communication restarted. Retry the request."
        : String(lastError && lastError.message || "NIMS browser bridge unavailable.")
    };
  }

  async function emitStatus() {
    const response = await send("NIMS_DASHBOARD_STATUS", {}, true);
    post("KBP_NIMS_STATUS", response || { ok: false, loggedIn: false });
  }

  window.addEventListener("message", async (event) => {
    if (event.source !== window || event.origin !== window.location.origin || !event.data) return;
    if (event.data.type === "KBP_NIMS_PING") {
      post("KBP_NIMS_PONG", { ready: true });
      return;
    }
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
      const crNo = String(event.data.crNo || "");
      const requestId = String(event.data.requestId || "").slice(0, 128);
      const response = await send("NIMS_DASHBOARD_FETCH_CR", { crNo, requestId }, true);
      if (response && response.ok === false) {
        post("KBP_NIMS_FETCH_ERROR", {
          error: response.error || "NIMS retrieval failed.",
          requestId,
          crNo
        });
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