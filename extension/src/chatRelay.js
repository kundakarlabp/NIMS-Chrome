(function (root) {
  "use strict";
  if (root.__NIMS_CHAT_RELAY_INSTALLED__) return;
  root.__NIMS_CHAT_RELAY_INSTALLED__ = true;

  const VERSION = "1.0.0";
  const RELAY_URL = "https://dehdptgkqbrkyzyodicd.supabase.co/functions/v1/nims-chat-bridge";
  const IDENTITY_KEY = "nimsChatBridgeIdentity";
  const STATE_KEY = "nimsChatBridgeState";
  const POLL_ALARM = "nims-chat-relay-fallback";
  let pollInFlight = false;
  let registrationPromise = null;

  function nowIso() { return new Date().toISOString(); }

  async function setState(patch) {
    const stored = await chrome.storage.local.get(STATE_KEY);
    const current = stored[STATE_KEY] || {};
    await chrome.storage.local.set({ [STATE_KEY]: { ...current, ...patch, updatedAt: nowIso() } });
  }

  async function ensureIdentity() {
    const stored = await chrome.storage.local.get(IDENTITY_KEY);
    let identity = stored[IDENTITY_KEY];
    if (identity && identity.deviceId && identity.deviceSecret && identity.publicKeyJwk && identity.privateKeyJwk) {
      return identity;
    }
    const keys = await root.NimsChatRelayCrypto.generateRsaIdentity();
    identity = {
      deviceId: crypto.randomUUID(),
      deviceSecret: root.NimsChatRelayCrypto.randomSecret(32),
      publicKeyJwk: keys.publicKeyJwk,
      privateKeyJwk: keys.privateKeyJwk,
      createdAt: nowIso()
    };
    await chrome.storage.local.set({ [IDENTITY_KEY]: identity });
    return identity;
  }

  async function relayPost(body, identity, authenticated = true) {
    const headers = { "Content-Type": "application/json" };
    if (authenticated) {
      headers["X-NIMS-Device-Id"] = identity.deviceId;
      headers["X-NIMS-Device-Secret"] = identity.deviceSecret;
    }
    const response = await fetch(RELAY_URL, {
      method: "POST",
      headers,
      cache: "no-store",
      body: JSON.stringify(body)
    });
    let data = null;
    try { data = await response.json(); } catch {}
    if (!response.ok || !data || data.ok === false) {
      const code = data && data.error ? data.error : "relay_unavailable";
      throw new Error(code);
    }
    return data;
  }

  async function register() {
    const identity = await ensureIdentity();
    await relayPost({
      action: "register",
      deviceId: identity.deviceId,
      deviceSecret: identity.deviceSecret,
      publicKeyJwk: identity.publicKeyJwk,
      clientVersion: VERSION
    }, identity, false);
    await setState({
      deviceId: identity.deviceId,
      registered: true,
      clientVersion: VERSION,
      lastRegistrationAt: nowIso(),
      lastError: ""
    });
    return identity;
  }

  async function ensureRegistered() {
    if (!registrationPromise) {
      registrationPromise = register().finally(() => { registrationPromise = null; });
    }
    return registrationPromise;
  }

  function safeFailureCode(error) {
    const message = String(error && error.message || error || "").toLowerCase();
    if (/session|authenticated|sign in|login/.test(message)) return "session_login_required";
    if (/cr-wise|navigation|cr number field/.test(message)) return "cr_navigation_failed";
    if (/did not return a result|no structured result|no result/.test(message)) return "no_results";
    if (/helper|processor|structured value|parse/.test(message)) return "result_processing_failed";
    if (/unauthorized_device|device_conflict/.test(message)) return "relay_device_auth_failed";
    if (/relay|fetch|network/.test(message)) return "relay_unavailable";
    return "bridge_failed";
  }

  function sanitizeBundle(bundle) {
    const patient = bundle && bundle.patient ? bundle.patient : {};
    const results = Array.isArray(bundle && bundle.results) ? bundle.results : [];
    const reports = Array.isArray(bundle && bundle.reports) ? bundle.reports : [];
    return {
      patient: {
        name: String(patient.name || "").slice(0, 160),
        crNo: String(patient.crNo || "").replace(/\D/g, "")
      },
      results: results.map((item, index) => ({
        id: String(item.id || ("result-" + index)).slice(0, 120),
        group: String(item.group || "").slice(0, 160),
        test: String(item.test || "").slice(0, 240),
        parameter: String(item.parameter || "").slice(0, 240),
        date: String(item.date || "").slice(0, 80),
        value: String(item.value == null ? "" : item.value).slice(0, 4000),
        unit: String(item.unit || "").slice(0, 80),
        refRange: String(item.refRange || "").slice(0, 160),
        abnormal: String(item.abnormal || "unknown").slice(0, 40),
        reportId: String(item.reportId || "").slice(0, 160)
      })),
      reports: reports.map((report, index) => ({
        id: String(report.id || ("report-" + index)).slice(0, 160),
        title: String(report.title || "Investigation report").slice(0, 240),
        date: String(report.date || "").slice(0, 80),
        department: String(report.department || "").slice(0, 160)
      })),
      enquiryRows: []
    };
  }

  async function processJob(job, identity) {
    let request;
    try {
      request = await root.NimsChatRelayCrypto.decryptJson(job.request_envelope, identity.privateKeyJwk);
    } catch {
      await relayPost({ action: "fail", jobId: job.id, errorCode: "request_decryption_failed" }, identity);
      return;
    }

    const crNo = String(request && request.crNo || "").replace(/\D/g, "");
    if (!/^\d{6,20}$/.test(crNo)) {
      await relayPost({ action: "fail", jobId: job.id, errorCode: "invalid_cr_request" }, identity);
      return;
    }

    const api = root.NimsDashboardBridgeApi;
    if (!api || typeof api.fetchCrForDashboard !== "function") {
      await relayPost({ action: "fail", jobId: job.id, errorCode: "dashboard_bridge_unavailable" }, identity);
      return;
    }

    try {
      const result = await api.fetchCrForDashboard(crNo, null);
      if (!result || result.ok === false || !result.bundle) {
        throw new Error(result && result.error ? result.error : "no_results");
      }
      const safeBundle = sanitizeBundle(result.bundle);
      const resultEnvelope = await root.NimsChatRelayCrypto.encryptJson(safeBundle, job.result_public_key_jwk);
      await relayPost({ action: "complete", jobId: job.id, resultEnvelope }, identity);
      await setState({
        lastJobAt: nowIso(),
        lastJobStatus: "completed",
        lastError: "",
        lastResultCount: safeBundle.results.length,
        lastReportCount: safeBundle.reports.length
      });
    } catch (error) {
      const code = safeFailureCode(error);
      try { await relayPost({ action: "fail", jobId: job.id, errorCode: code }, identity); } catch {}
      await setState({ lastJobAt: nowIso(), lastJobStatus: "error", lastError: code });
    }
  }

  async function pollOnce(reason = "heartbeat") {
    if (pollInFlight) return;
    pollInFlight = true;
    try {
      const identity = await ensureRegistered();
      const data = await relayPost({ action: "poll" }, identity);
      await setState({
        deviceId: identity.deviceId,
        registered: true,
        online: true,
        lastPollAt: nowIso(),
        lastPollReason: reason,
        lastError: ""
      });
      if (data.job) await processJob(data.job, identity);
    } catch (error) {
      await setState({
        online: false,
        lastPollAt: nowIso(),
        lastPollReason: reason,
        lastError: safeFailureCode(error)
      });
    } finally {
      pollInFlight = false;
    }
  }

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (!message || message.type !== "NIMS_CHAT_RELAY_TICK") return false;
    pollOnce("nims_tab").then(() => sendResponse({ ok: true })).catch(() => sendResponse({ ok: false }));
    return true;
  });

  if (chrome.alarms) {
    chrome.alarms.create(POLL_ALARM, { periodInMinutes: 0.5 }).catch(() => {});
    chrome.alarms.onAlarm.addListener((alarm) => {
      if (alarm && alarm.name === POLL_ALARM) pollOnce("alarm").catch(() => {});
    });
  }

  ensureRegistered()
    .then(() => pollOnce("startup"))
    .catch((error) => setState({ registered: false, online: false, lastError: safeFailureCode(error) }));
})(typeof globalThis !== "undefined" ? globalThis : self);
