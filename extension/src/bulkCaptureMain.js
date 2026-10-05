(() => {
  'use strict';
  if (window.__KBP_NIMS_BULK_CAPTURE_MAIN__) return;
  window.__KBP_NIMS_BULK_CAPTURE_MAIN__ = true;

  let activeRequestId = '';

  const NIMS_ORIGINS = new Set(['https://nimsts.edu.in', 'https://www.nimsts.edu.in']);

  function bodyContainsMarker(body) {
    if (body == null) return false;
    if (/GETMETABOLICDATA/i.test(String(body))) return true;
    try {
      if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
        return [...body.entries()].some(([key, value]) => /GETMETABOLICDATA/i.test(key + '=' + value));
      }
    } catch {}
    try {
      if (typeof FormData !== 'undefined' && body instanceof FormData) {
        return [...body.entries()].some(([key, value]) => /GETMETABOLICDATA/i.test(key + '=' + String(value)));
      }
    } catch {}
    return false;
  }

  const isBulkRequest = (url, body) =>
    /GETMETABOLICDATA/i.test(String(url || '')) || bodyContainsMarker(body);

  const emit = body => {
    if (!activeRequestId || body == null || typeof body !== 'object') return;
    const message = {
      __nimsBulkCapture: true,
      requestId: activeRequestId,
      body
    };
    for (const targetOrigin of ['https://nimsts.edu.in', 'https://www.nimsts.edu.in']) {
      try { window.top.postMessage(message, targetOrigin); } catch {}
    }
  };

  const parseAndEmit = value => {
    try {
      if (value == null) return;
      if (typeof value === 'object') {
        emit(value);
        return;
      }
      const text = String(value).trim();
      if (!text) return;
      emit(JSON.parse(text));
    } catch {}
  };

  window.addEventListener('message', event => {
    if (!NIMS_ORIGINS.has(event.origin)) return;
    if (event.data?.__nimsBulkCaptureArm !== true) return;
    // The authenticated top NIMS shell arms the Trends iframe immediately
    // before Custom Sheet Apply. Do not require event.source === window:
    // in the real frameset the source is window.top.
    activeRequestId = String(event.data.requestId || '');
  });

  const originalFetch = window.fetch;
  if (typeof originalFetch === 'function') {
    window.fetch = async function(input, init) {
      const url = typeof input === 'string' ? input : input?.url || '';
      const body = init?.body || '';
      const response = await originalFetch.apply(this, arguments);
      if (isBulkRequest(url, body)) {
        try {
          const clone = response.clone();
          const text = await clone.text();
          parseAndEmit(text);
        } catch {}
      }
      return response;
    };
  }

  const originalOpen = XMLHttpRequest.prototype.open;
  const originalSend = XMLHttpRequest.prototype.send;

  XMLHttpRequest.prototype.open = function(method, url) {
    this.__kbpBulkUrl = String(url || '');
    return originalOpen.apply(this, arguments);
  };

  XMLHttpRequest.prototype.send = function(body) {
    const candidate = isBulkRequest(this.__kbpBulkUrl, body);
    if (candidate) {
      this.addEventListener('loadend', () => {
        try {
          if (this.responseType === 'json' && this.response && typeof this.response === 'object') {
            parseAndEmit(this.response);
          } else {
            parseAndEmit(this.responseText);
          }
        } catch {}
      }, { once: true });
    }
    return originalSend.apply(this, arguments);
  };
})();
