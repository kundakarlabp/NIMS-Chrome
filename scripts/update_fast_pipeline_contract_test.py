from pathlib import Path

path = Path("tests/test_extension_utils.py")
text = path.read_text(encoding="utf-8")
old = '''    assert "processBulk(cultureRequests, concurrency = 3" in main_activity
    assert "processBulk(otherRequests, concurrency = 3" in main_activity
    assert "Semaphore(concurrency.coerceIn(1, 4))" in main_activity
    assert "ReportFetchQueue(concurrency = 3)" not in main_activity
    assert "private var webViewUserAgent = \\\"\\\"" in main_activity
    assert "webViewUserAgent = webView.settings.userAgentString" in main_activity
    assert 'setRequestProperty("User-Agent", webViewUserAgent)' in main_activity
    assert "setRequestProperty(\\\"User-Agent\\\", webView.settings.userAgentString)" not in main_activity
'''
new = '''    # The optimized implementation intentionally replaced the old dual 3+3
    # culture/laboratory queues with one clinically prioritized queue and
    # separate bounded network/PDF stages.
    assert "ReportRequestOptimizer.optimize(prepared)" in main_activity
    assert "processBulk(queue)" in main_activity
    assert "private val fetchSemaphore = Semaphore(6)" in main_activity
    assert "private val parseSemaphore = Semaphore(2)" in main_activity
    assert "processBulk(cultureRequests, concurrency = 3" not in main_activity
    assert "processBulk(otherRequests, concurrency = 3" not in main_activity
    assert "ReportFetchQueue(concurrency = 3)" not in main_activity
    assert "private var webViewUserAgent = \\\"\\\"" in main_activity
    assert "webViewUserAgent = webView.settings.userAgentString" in main_activity
    assert "NimsReportHttpClient(" in main_activity
    assert "userAgentProvider = { webViewUserAgent }" in main_activity
'''
if text.count(old) != 1:
    raise RuntimeError(f"expected one stale contract block, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
