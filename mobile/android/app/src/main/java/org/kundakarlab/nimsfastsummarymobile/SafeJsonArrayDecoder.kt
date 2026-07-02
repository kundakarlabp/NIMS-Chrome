package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONArray

// ROOT CAUSE: evaluateJson's low-level WebView bridge (MainActivity.decodeJsString)
// can legitimately hand back an empty string or other non-JSON text on failure
// (it is itself wrapped in runCatching with an empty-string fallback). Calling
// JSONArray(rawText) directly on that result throws org.json.JSONException for
// anything that isn't valid JSON array syntax. An uncaught exception on the
// main thread is a process crash on Android -- not a catchable in-app error --
// which is what was reported live: the app died with no chance to even copy
// the log. This single function is the one place that decision is made, used
// by every call site that used to construct JSONArray(...) directly from a
// WebView response, so the failure mode is "empty array, clear error message"
// everywhere, not "process crash" in some places and "safe" in others.
object SafeJsonArrayDecoder {
    fun decode(raw: String): JSONArray = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
}
