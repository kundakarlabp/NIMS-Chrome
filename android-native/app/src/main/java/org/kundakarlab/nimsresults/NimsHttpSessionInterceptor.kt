package org.kundakarlab.nimsresults

import android.webkit.CookieManager
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.Locale

/**
 * Applied as a NETWORK interceptor: OkHttp invokes it on every redirect hop,
 * before the HTTP request is sent. Application interceptors run only once.
 *
 * Preserve NIMS's legitimate www/apex redirects while preventing a manually
 * attached session cookie from following any redirect outside NIMS HTTPS.
 */
internal class NimsHttpSessionInterceptor(
    private val allowedHosts: Set<String> = NimsUrls.HOSTS,
    private val readCookie: (String) -> String = {
        CookieManager.getInstance().getCookie(it).orEmpty()
    },
    private val recordCookies: (String, List<String>) -> Unit = { url, values ->
        if (values.isNotEmpty()) {
            val store = CookieManager.getInstance()
            values.forEach { store.setCookie(url, it) }
            store.flush()
        }
    }
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (url.scheme != "https" || url.host.lowercase(Locale.US) !in allowedHosts) {
            throw IOException("NIMS redirected outside approved HTTPS hosts.")
        }

        // Never forward Cookie from a previous hop or caller; WebView holds
        // separate cookies for nimsts.edu.in and www.nimsts.edu.in.
        val request = chain.request().newBuilder().removeHeader("Cookie")
        readCookie(url.toString()).takeIf { it.isNotBlank() }?.let {
            request.header("Cookie", it)
        }
        val response = chain.proceed(request.build())
        val changes = response.headers("Set-Cookie")
        if (changes.isNotEmpty()) recordCookies(url.toString(), changes)
        return response
    }
}
