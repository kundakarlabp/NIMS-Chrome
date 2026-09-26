(function () {
  "use strict";
  if (window.top !== window || window.__NIMS_CHAT_RELAY_HEARTBEAT__) return;
  window.__NIMS_CHAT_RELAY_HEARTBEAT__ = true;

  let stopped = false;
  async function tick() {
    if (stopped) return;
    try { await chrome.runtime.sendMessage({ type: "NIMS_CHAT_RELAY_TICK" }); } catch {}
  }

  tick();
  const timer = setInterval(tick, 2000);
  window.addEventListener("pagehide", () => {
    stopped = true;
    clearInterval(timer);
  }, { once: true });
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) tick();
  });
})();
