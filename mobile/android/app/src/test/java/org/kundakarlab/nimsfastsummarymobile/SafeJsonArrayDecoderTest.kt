package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.*
import org.junit.Test

class SafeJsonArrayDecoderTest {
    // REGRESSION: this is the exact scenario reported live -- Test One,
    // immediately after a NIMS popup open/close during template discovery,
    // crashed the whole app (process death, not a catchable in-app error).
    // The cause was JSONArray(rowsText) called directly with no guard, where
    // rowsText could be an empty string (decodeJsString's own failure
    // fallback) or otherwise non-JSON. JSONArray("") throws
    // org.json.JSONException; uncaught on the main thread, that kills the
    // process. This test proves the decoder degrades to an empty array
    // instead, for every shape of bad input that could plausibly occur.

    @Test
    fun emptyStringDecodesToEmptyArrayInsteadOfThrowing() {
        val result = SafeJsonArrayDecoder.decode("")
        assertEquals(0, result.length())
    }

    @Test
    fun nonJsonGarbageDecodesToEmptyArrayInsteadOfThrowing() {
        val result = SafeJsonArrayDecoder.decode("Session is Expired or Not a Authenticated User")
        assertEquals(0, result.length())
    }

    @Test
    fun truncatedOrMalformedJsonDecodesToEmptyArrayInsteadOfThrowing() {
        val result = SafeJsonArrayDecoder.decode("""[{"report_name":"CBC","onclick":"return printRep""")
        assertEquals(0, result.length())
    }

    @Test
    fun aJsonObjectInsteadOfAnArrayDecodesToEmptyArrayInsteadOfThrowing() {
        // org.json.JSONArray(String) throws on a syntactically valid JSON
        // OBJECT too, since it expects an array specifically. NimsReportCore
        // functions always return arrays for these calls, but the decoder
        // must not crash even if the page returns something else.
        val result = SafeJsonArrayDecoder.decode("""{"ok": false, "errorCode": "something"}""")
        assertEquals(0, result.length())
    }

    @Test
    fun validJsonArrayDecodesNormally() {
        val result = SafeJsonArrayDecoder.decode("""[{"report_name":"CBC"},{"report_name":"ESR"}]""")
        assertEquals(2, result.length())
        assertEquals("CBC", result.getJSONObject(0).getString("report_name"))
    }

    @Test
    fun nullLiteralStringDecodesToEmptyArrayInsteadOfThrowing() {
        // decodeJsString's JSONArray("[$value]").getString(0) path can hand
        // back the literal text "null" if the JS side returned undefined.
        val result = SafeJsonArrayDecoder.decode("null")
        assertEquals(0, result.length())
    }
}
