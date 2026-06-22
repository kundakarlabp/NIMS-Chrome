(function (root) {
  function isGenuineReportRow(row) {
    return Boolean(
      row
      && row.onclick_function_name === "printReport"
      && Number(row.onclick_arg_count) === 1
      && row.onclick_parse_status !== "needs_mapping"
    );
  }

  function frameScore(frame) {
    if (!frame || !frame.ready || Number(frame.rowCount || 0) <= 0) return -1;
    return (frame.exactResultFrame ? 100000 : 0)
      + (frame.visible === false ? -10000 : 0)
      + Number(frame.rowCount || 0) * 100
      - Number(frame.frameId || 0);
  }

  function selectResultsFrame(frames) {
    return (Array.isArray(frames) ? frames : [])
      .filter((frame) => frame && frame.ready && frame.visible !== false && Number(frame.rowCount || 0) > 0)
      .sort((a, b) => frameScore(b) - frameScore(a))[0] || null;
  }

  const api = { isGenuineReportRow, frameScore, selectResultsFrame };
  root.NimsManualAnalysisUtils = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : window);
