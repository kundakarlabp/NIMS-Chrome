(function (root) {
  const ALLOWED_NIMS_HOSTS = new Set(["nimsts.edu.in", "www.nimsts.edu.in"]);
  const ALLOWED_NIMS_PATH_PREFIXES = ["/AHIMSG5/", "/HISInvestigationG5/", "/HIS/", "/hislogin/", "/HISUtilities/", "/HBIMS/"];
  const FORBIDDEN_KEYS = new Set(["raw_row_text", "onclick", "href", "source_url", "raw_text_preview", "transient_print_report_arg", "transient_form_fields", "transient_form_action"]);

  function isAllowedNimsUrl(url) {
    try {
      const parsed = new URL(url || "");
      return classifyNimsUrl(parsed) === "ALLOWED_NIMS";
    } catch {
      return false;
    }
  }

  function safeDisplayUrl(url) {
    try {
      const parsed = new URL(url || "");
      return `${parsed.hostname}${parsed.pathname}`;
    } catch {
      return "";
    }
  }

  function sanitizeDiagnosticText(value) {
    return String(value == null ? "" : value)
      .replace(/\bCR\s*(?:No|Number)?\s*[:#-]?\s*\d+\b/gi, "CR No: [MASKED]")
      .replace(/\b\d{10}\b/g, "[PHONE MASKED]")
      .replace(/\b(patient\s*name|name)\s*[:#-]?\s*[A-Za-z .]+/gi, "$1: [MASKED]")
      .replace(/\b(address)\s*[:#-]?\s*[^|,\n]+/gi, "$1: [MASKED]");
  }

  function sanitizeDiagnosticValue(value) {
    if (Array.isArray(value)) return value.map(sanitizeDiagnosticValue);
    if (value && typeof value === "object") {
      const out = {};
      for (const [key, inner] of Object.entries(value)) {
        if (FORBIDDEN_KEYS.has(key) || /^transient_/i.test(key) || /print_report_arg/i.test(key)) continue;
        out[key] = sanitizeDiagnosticValue(inner);
      }
      return out;
    }
    if (typeof value !== "string") return value;
    return sanitizeDiagnosticText(value);
  }

  function classifyNimsUrl(input) {
    let parsed;
    try { parsed = input instanceof URL ? input : new URL(input || ""); } catch { return "BLOCKED_SCHEME"; }
    if (parsed.protocol !== "https:") return "BLOCKED_SCHEME";
    if (parsed.username || parsed.password) return "BLOCKED_UNSAFE";
    const host = parsed.hostname.toLowerCase();
    const isTrusted = ALLOWED_NIMS_HOSTS.has(host);
    if (!isTrusted) return "EXTERNAL_HTTPS";
    if (parsed.port && parsed.port !== "443") return "BLOCKED_NIMS";
    const rawPath = parsed.pathname || "";
    const lower = rawPath.toLowerCase();
    if (!rawPath || rawPath.includes("\\") || rawPath.includes("..") || rawPath.includes("//") || ["%2e", "%2f", "%5c"].some((part) => lower.includes(part))) return "BLOCKED_NIMS";
    return ALLOWED_NIMS_PATH_PREFIXES.some((prefix) => rawPath.startsWith(prefix)) ? "ALLOWED_NIMS" : "BLOCKED_NIMS";
  }

  function frameScore(frame) {
    const base = { report_list: 5000, cr_search: 4000, investigation_menu: 3000, home: 2000, login: 1000 }[frame && frame.detectedStage] || 0;
    return base + Number((frame && frame.viewReportRows) || 0) + (frame && frame.hasSetPdfTemplate ? 10 : 0) - Number((frame && frame.depth) || 0);
  }

  function selectBestFrameDiagnostic(frames) {
    const items = Array.isArray(frames) ? frames : [];
    let best = null;
    let bestScore = 0;
    for (const frame of items) {
      const score = frameScore(frame);
      if (score > bestScore) { best = frame; bestScore = score; }
    }
    return best;
  }


  function isTerminalNavigationStage(stage) {
    return ["session_expired", "report_list", "cr_search", "login"].includes(stage);
  }

  function navigationStageScore(frame) {
    const stage = frame && (frame.stage || frame.detectedStage);
    const scores = { session_expired: 9000, report_list: 8000, cr_search: 7000, report_viewer: 6000, investigation_menu: 5000, home: 4000, login: 3000 };
    return scores[stage] || 0;
  }

  function selectTerminalNavigationState(frames) {
    const visible = (Array.isArray(frames) ? frames : []).filter((frame) => frame.visible || frame.visibleThroughAncestors);
    for (const stage of ["session_expired", "report_list", "cr_search", "login"]) {
      const match = visible.find((frame) => (frame.stage || frame.detectedStage) === stage);
      if (match) return match;
    }
    return null;
  }

  function targetMethodScore(method) {
    return ({ exact_id: 400, exact_onclick: 350, exact_endpoint: 300, exact_menu_id: 250, exact_label: 200, exact_text: 150, compatibility_fallback: 100, frame_function: 50 })[method] || 0;
  }

  function compareNavigationFrames(a, b) {
    const stageDiff = navigationStageScore(b) - navigationStageScore(a);
    if (stageDiff) return stageDiff;
    const methodDiff = targetMethodScore(b && b.targetMethod) - targetMethodScore(a && a.targetMethod);
    if (methodDiff) return methodDiff;
    const depthDiff = Number((a && a.depth) || 0) - Number((b && b.depth) || 0);
    if (depthDiff) return depthDiff;
    return Number((a && a.frameId) || 0) - Number((b && b.frameId) || 0);
  }

  function selectNavigationTarget(frames) {
    const items = Array.isArray(frames) ? frames : [];
    const terminal = selectTerminalNavigationState(items);
    if (terminal) return { kind: "terminal", frame: terminal };
    const candidates = items
      .filter((frame) => (frame.visible || frame.visibleThroughAncestors) && frame.actionable && ["investigation_menu", "home"].includes(frame.stage || frame.detectedStage))
      .sort(compareNavigationFrames);
    if (!candidates.length) return null;
    return { kind: "action", frame: candidates[0] };
  }

  function sanitizeDiagnosticResult(diagnostic) {
    const clean = sanitizeDiagnosticValue(diagnostic || {});
    if (clean.activeTabUrl) clean.activeTabUrl = safeDisplayUrl(clean.activeTabUrl);
    if (Array.isArray(clean.frames)) {
      clean.frames = clean.frames.map((frame) => ({
        ...frame,
        url: safeDisplayUrl(frame.url || "")
      }));
    }
    return clean;
  }

  const api = {
    isAllowedNimsUrl,
    safeDisplayUrl,
    sanitizeDiagnosticText,
    sanitizeDiagnosticValue,
    classifyNimsUrl,
    frameScore,
    isTerminalNavigationStage,
    navigationStageScore,
    selectTerminalNavigationState,
    selectNavigationTarget,
    selectBestFrameDiagnostic,
    sanitizeDiagnosticResult
  };

  root.NimsSidepanelUtils = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : window);
