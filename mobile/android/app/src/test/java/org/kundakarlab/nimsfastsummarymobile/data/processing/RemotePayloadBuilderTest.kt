package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.*
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.ReportInput
import java.util.Base64

class RemotePayloadBuilderTest {
    @Test fun payloadUsesOneBase64FieldAndNoSecrets() {
        val input = ReportInput("r", "Report", "2026-01-01", "pdf", "application/pdf", "abc".toByteArray(), "https://nimsts.edu.in/AHIMSG5/report")
        val payload = RemotePayloadBuilder.build(input)
        assertTrue(payload.has("pdf_base64"))
        assertFalse(payload.has("content_base64"))
        assertFalse(payload.has("cookie"))
        assertFalse(payload.has("token"))
        assertArrayEquals("abc".toByteArray(), Base64.getDecoder().decode(payload.getString("pdf_base64")))
    }

    @Test fun rejectsTooLargeBeforeEncoding() {
        val input = ReportInput("r", "Report", "2026-01-01", "pdf", "application/pdf", ByteArray(RemotePayloadBuilder.MAX_REMOTE_REPORT_BYTES + 1))
        val error = assertThrows(RemotePayloadTooLargeException::class.java) { RemotePayloadBuilder.build(input) }
        assertTrue(error.message!!.contains("too large", true))
    }
}
