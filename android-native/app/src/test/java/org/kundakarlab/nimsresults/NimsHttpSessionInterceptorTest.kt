package org.kundakarlab.nimsresults

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Real OkHttp redirect handling over local TLS: no NIMS network or patient data. */
class NimsHttpSessionInterceptorTest {
    private fun withTlsServer(test: (MockWebServer, HandshakeCertificates) -> Unit) {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverIdentity = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientTrust = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        MockWebServer().use { server ->
            server.useHttps(serverIdentity.sslSocketFactory(), false)
            server.start()
            test(server, clientTrust)
        }
    }

    private fun client(
        trust: HandshakeCertificates,
        hosts: Set<String>,
        cookies: MutableMap<String, String>
    ): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
        .addNetworkInterceptor(
            NimsHttpSessionInterceptor(
                allowedHosts = hosts,
                readCookie = { url -> cookies[url.toHttpUrl().host].orEmpty() },
                recordCookies = { url, values ->
                    values.lastOrNull()?.substringBefore(';')?.let {
                        cookies[url.toHttpUrl().host] = it
                    }
                }
            )
        )
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Test
    fun permittedRedirectRefreshesCookieFromIntermediateResponse() = withTlsServer { server, trust ->
        val cookies = mutableMapOf("localhost" to "nims=old")
        server.enqueue(MockResponse().setResponseCode(302)
            .addHeader("Location", "/next")
            .addHeader("Set-Cookie", "nims=fresh; Path=/"))
        server.enqueue(MockResponse().setBody("ok"))
        val url = server.url("/start").newBuilder().host("localhost").build()
        client(trust, setOf("localhost"), cookies)
            .newCall(Request.Builder().url(url).build()).execute().use { response ->
                assertEquals(200, response.code)
            }
        assertEquals("nims=old", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie"))
        assertEquals("nims=fresh", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie"))
    }

    @Test
    fun approvedWwwToApexLikeRedirectUsesDestinationCookieOnly() = withTlsServer { server, trust ->
        // localhost and 127.0.0.1 emulate two approved hostnames on one server.
        val cookies = mutableMapOf("localhost" to "www=one", "127.0.0.1" to "apex=two")
        val next = server.url("/destination").newBuilder().host("127.0.0.1").build()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", next.toString()))
        server.enqueue(MockResponse().setBody("ok"))
        client(trust, setOf("localhost", "127.0.0.1"), cookies)
            .newCall(Request.Builder().url(server.url("/start").newBuilder().host("localhost").build())
                .header("Cookie", "unsafe=stale")
                .build()).execute().use { response ->
                assertEquals(200, response.code)
            }
        assertEquals("www=one", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie"))
        assertEquals("apex=two", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie"))
    }

    @Test
    fun offHostRedirectCannotSendSessionCookie() = withTlsServer { server, trust ->
        val cookies = mutableMapOf("localhost" to "nims=secret")
        val forbidden = server.url("/foreign").newBuilder().host("127.0.0.1").build()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", forbidden.toString()))
        val initial = server.url("/start").newBuilder().host("localhost").build()
        try {
            client(trust, setOf("localhost"), cookies)
                .newCall(Request.Builder().url(initial)
                    .header("Cookie", "manual=cookie-that-must-not-follow").build())
                .execute().close()
            fail("Unapproved redirect must not be followed")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("outside approved HTTPS hosts"))
        }
        assertEquals(1, server.requestCount)
        assertEquals("nims=secret", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie"))
    }

    @Test
    fun httpsToHttpDowngradeIsRefused() = withTlsServer { server, trust ->
        val cookies = mutableMapOf("localhost" to "nims=secret")
        val downgrade = server.url("/downgrade").newBuilder()
            .scheme("http").host("localhost").build()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", downgrade.toString()))
        try {
            client(trust, setOf("localhost"), cookies)
                .newCall(Request.Builder().url(server.url("/start")).build())
                .execute().close()
            fail("HTTP downgrade must not be followed")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("outside approved HTTPS hosts"))
        }
        assertEquals(1, server.requestCount)
    }
}
