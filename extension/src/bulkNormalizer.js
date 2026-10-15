(function (root) {
  'use strict';

  const CR_PATTERN = /^\d{15}$/;
  const MONTHS = { Jan: '01', Feb: '02', Mar: '03', Apr: '04', May: '05', Jun: '06', Jul: '07', Aug: '08', Sep: '09', Oct: '10', Nov: '11', Dec: '12' };
  const REPORT_PATH = '/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt';

  function requireCr(crNo) {
    if (typeof crNo !== 'string' || !CR_PATTERN.test(crNo)) throw new Error('Enter the 15-digit NIMS CR number.');
    return crNo;
  }

  function toIsoDate(value) {
    const input = String(value ?? '').trim();
    const match = /^(\d{2})-([A-Za-z]{3})-(\d{4})$/.exec(input);
    if (!match) return input;
    const key = match[2][0].toUpperCase() + match[2].slice(1).toLowerCase();
    return MONTHS[key] ? `${match[3]}-${MONTHS[key]}-${match[1]}` : input;
  }

  function findBulk(node, depth = 0, seen = new Set()) {
    if (depth > 5 || node == null || typeof node !== 'object' || seen.has(node)) return null;
    seen.add(node);
    if (!Array.isArray(node) && Array.isArray(node.groups)) return node;
    if (Array.isArray(node)) {
      for (const child of node) {
        const found = findBulk(child, depth + 1, seen);
        if (found) return found;
      }
      return null;
    }
    for (const child of Object.values(node)) {
      const found = findBulk(child, depth + 1, seen);
      if (found) return found;
    }
    return null;
  }

  function reportUrl(report) {
    const direct = String(report?.url || '').trim();
    if (direct) {
      try {
        const parsed = new URL(direct);
        if (['nimsts.edu.in', 'www.nimsts.edu.in'].includes(parsed.hostname) &&
            parsed.pathname === REPORT_PATH &&
            parsed.searchParams.get('hmode') === 'PRINTREPORT') return parsed.href;
      } catch {}
    }
    const token = String(report?.token || report?.reportFile || report?.fileName || '').trim();
    if (!token || token.includes('..') || !/^[A-Za-z0-9_.-]+\.pdf$/i.test(token)) return '';
    const url = new URL(REPORT_PATH, 'https://www.nimsts.edu.in');
    url.searchParams.set('hmode', 'PRINTREPORT');
    url.searchParams.set('fileName', token);
    return url.href;
  }

  function normalizeReports(items) {
    if (!Array.isArray(items)) return [];
    return items.flatMap((report, index) => {
      const url = reportUrl(report);
      if (!url) return [];
      return [{
        id: String(report?.id || report?.reportId || `nims-report-${index}`),
        title: String(report?.title || report?.testName || report?.labName || 'NIMS source report'),
        date: toIsoDate(report?.date || report?.collectionDate || ''),
        url
      }];
    });
  }

  function normalizeBulk(raw, crNo, options = {}) {
    requireCr(crNo);
    const bulk = findBulk(raw) || raw;
    if (!bulk || typeof bulk !== 'object' || !Array.isArray(bulk.groups)) {
      throw new Error('NIMS bulk API did not return Custom Sheet groups.');
    }
    if (bulk.status != null && String(bulk.status) !== '1') {
      throw new Error('NIMS Custom Sheet bulk request failed.');
    }
    if (bulk.crNo != null && String(bulk.crNo).replace(/\D/g, '') !== crNo) {
      const error = new Error('NIMS returned a different CR. Results were not loaded.');
      error.code = 'NIMS_IDENTITY_MISMATCH';
      throw error;
    }

    const results = [];
    for (const group of bulk.groups) {
      for (const row of group?.rows ?? []) {
        for (const [date, rawValue] of Object.entries(row?.valuesByDate ?? {})) {
          const valueText = String(rawValue ?? '').trim();
          if (!valueText) continue;
          results.push({
            id: `nims-${results.length}`,
            group: String(group?.testName ?? ''),
            test: String(group?.testName ?? ''),
            parameter: String(row?.parameterName ?? ''),
            date: toIsoDate(date),
            valueText,
            unit: row?.unit == null ? undefined : String(row.unit),
            refRange: row?.refRange == null ? undefined : String(row.refRange)
          });
        }
      }
    }

    if (!results.length) throw new Error('NIMS Custom Sheet bulk API returned no result values.');

    const reports = normalizeReports(options.reportIndex ?? bulk.reportIndex ?? []);
    const enquiryRows = Array.isArray(bulk.enquiryRows) ? bulk.enquiryRows : [];

    return {
      schemaVersion: 'nims-dashboard-bulk/1.0',
      source: 'nims_bulk_api',
      patient: {
        name: String(options.patient?.name ?? bulk.patientName ?? bulk.identity?.name ?? '').trim(),
        crNo,
        age: String(options.patient?.age ?? bulk.identity?.age ?? '').trim() || undefined,
        sex: String(options.patient?.sex ?? bulk.identity?.sex ?? '').trim() || undefined
      },
      results,
      reports,
      enquiryRows,
      coverage: {
        resultCount: results.length,
        reportCount: reports.length,
        enquiryCount: enquiryRows.length
      }
    };
  }

  root.NimsBulkNormalizer = { requireCr, toIsoDate, findBulk, normalizeBulk, normalizeReports };
})(typeof self !== 'undefined' ? self : globalThis);
