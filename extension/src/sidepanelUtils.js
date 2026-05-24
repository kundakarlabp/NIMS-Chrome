(function (root) {
  const ALLOWED_NIMS_HOSTS = new Set(["nimsts.edu.in", "www.nimsts.edu.in"]);
  const FORBIDDEN_KEYS = new Set(["raw_row_text", "onclick", "href", "source_url", "raw_text_preview"]);

  function isAllowedNimsUrl(url) {
    try {
      const parsed = new URL(url || "");
      return parsed.protocol === "https:" && ALLOWED_NIMS_HOSTS.has(parsed.hostname);
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
        if (FORBIDDEN_KEYS.has(key)) continue;
        out[key] = sanitizeDiagnosticValue(inner);
      }
      return out;
    }
    if (typeof value !== "string") return value;
    return sanitizeDiagnosticText(value);
  }

  function selectBestFrameDiagnostic(frames) {
    const items = Array.isArray(frames) ? frames : [];
    let best = null;
    for (const frame of items) {
      if (!best || Number(frame.viewReportRows || 0) > Number(best.viewReportRows || 0)) {
        best = frame;
      }
    }
    return best;
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
    selectBestFrameDiagnostic,
    sanitizeDiagnosticResult
  };

  root.NimsSidepanelUtils = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : window);
