(function () {
  const HIDDEN_ADVANCED_IDS = [
    "openCrReports",
    "diagnosePage",
    "discoverMapping",
    "testDirectFetch",
    "runFast",
    "runCultures",
    "runFull",
    "manualPopupFallback",
    "copyMappingDiagnostics",
    "copyDirectFetchDiagnostics"
  ];

  document.addEventListener("DOMContentLoaded", () => {
    organizeAdvancedActions();
    const button = document.getElementById("analyzeCurrentResults");
    if (button) button.addEventListener("click", () => analyzeCurrentResults("bulk_fast"));
  });

  function organizeAdvancedActions() {
    const slot = document.getElementById("manualAdvancedActionSlot");
    if (!slot) return;
    HIDDEN_ADVANCED_IDS.forEach((id) => {
      const element = document.getElementById(id);
      if (element) slot.appendChild(element);
    });
  }

  async function analyzeCurrentResults(mode) {
    const status = document.getElementById("status");
    const button = document.getElementById("analyzeCurrentResults");
    if (button) button.disabled = true;
    try {
      status.textContent = "Checking the currently visible NIMS result list…";
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      if (!tab || !tab.id) throw new Error("No active tab found.");
      const sidepanelUtils = window.NimsSidepanelUtils;
      if (!sidepanelUtils || !sidepanelUtils.isAllowedNimsUrl(tab.url || "")) {
        throw new Error("Open the NIMS page, navigate manually to the submitted CR report list, then retry.");
      }

      await ensureManualAnalysis(tab.id);
      const probes = await chrome.scripting.executeScript({
        target: { tabId: tab.id, allFrames: true },
        func: () => window.NimsManualAnalysis ? window.NimsManualAnalysis.probe() : { ready: false, rowCount: 0 }
      }).catch(() => []);
      const frames = (probes || []).map((entry) => ({ frameId: entry.frameId, ...(entry.result || {}) }));
      const selected = window.NimsManualAnalysisUtils.selectResultsFrame(frames);
      if (!selected) {
        throw new Error("No visible genuine View Report rows were found. Submit the CR search and keep the report-result table visible.");
      }

      status.textContent = `Found ${selected.rowCount} visible reports. Learning one report request and validating it…`;
      const result = await chrome.scripting.executeScript({
        target: { tabId: tab.id, frameIds: [selected.frameId] },
        func: (selectedMode) => window.NimsManualAnalysis.analyze(selectedMode),
        args: [mode]
      });
      const outcome = result && result[0] && result[0].result;
      if (!outcome || !outcome.ok) throw new Error((outcome && outcome.error) || "Analysis failed.");
      status.textContent = `Analysis complete. ${outcome.rowCount} visible reports were detected.`;
    } catch (error) {
      status.textContent = `Error: ${error.message}`;
    } finally {
      if (button) button.disabled = false;
    }
  }

  async function ensureManualAnalysis(tabId) {
    const checks = await chrome.scripting.executeScript({
      target: { tabId, allFrames: true },
      func: () => Boolean(window.NimsManualAnalysis && window.NimsFastSummary && window.NimsFastSummaryUtils)
    }).catch(() => []);
    const missing = (checks || []).filter((entry) => !entry.result).map((entry) => entry.frameId);
    if (!missing.length) return;
    await chrome.scripting.executeScript({ target: { tabId, frameIds: missing }, files: ["src/contentUtils.js"] }).catch(() => {});
    await chrome.scripting.executeScript({ target: { tabId, frameIds: missing }, files: ["src/contentScript.js"] }).catch(() => {});
    await chrome.scripting.executeScript({ target: { tabId, frameIds: missing }, files: ["src/manualAnalysisUtils.js"] }).catch(() => {});
    await chrome.scripting.executeScript({ target: { tabId, frameIds: missing }, files: ["src/manualAnalysis.js"] }).catch(() => {});
  }
})();
