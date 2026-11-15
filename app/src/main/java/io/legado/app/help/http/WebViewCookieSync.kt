package io.legado.app.help.http

import android.webkit.CookieManager as AndroidCookieManager
import android.webkit.WebView

/**
 * Synchronizes non-empty WebView cookies into [CookieStore] without overwriting
 * existing cookies when WebView returns null or blank values.
 */
object WebViewCookieSync {

    fun syncFromWebView(vararg urls: String?): Boolean {
        val cookieManager = getCookieManagerOrNull() ?: return false
        return syncCookies(
            urls = urls.asList(),
            flushCookies = {
                runCatching { cookieManager.flush() }
            },
            readWebViewCookie = { url ->
                runCatching { cookieManager.getCookie(url) }.getOrNull()
            },
            mergeIntoStore = { url, cookie ->
                CookieStore.replaceCookie(url, cookie)
            }
        )
    }

    fun syncFromWebViewToTarget(targetKey: String?, vararg sourceUrls: String?): Boolean {
        val cookieManager = getCookieManagerOrNull() ?: return false
        return syncCookiesToTarget(
            targetKey = targetKey,
            sourceUrls = sourceUrls.asList(),
            flushCookies = {
                runCatching { cookieManager.flush() }
            },
            readWebViewCookie = { url ->
                runCatching { cookieManager.getCookie(url) }.getOrNull()
            },
            mergeIntoStore = { key, cookie ->
                CookieStore.replaceCookie(key, cookie)
            }
        )
    }

    fun enableThirdPartyCookies(webView: WebView, enabled: Boolean) {
        val cookieManager = getCookieManagerOrNull() ?: return
        runCatching {
            if (enabled) {
                cookieManager.setAcceptCookie(true)
            }
            cookieManager.setAcceptThirdPartyCookies(webView, enabled)
        }
    }

    private fun getCookieManagerOrNull(): AndroidCookieManager? {
        return runCatching { AndroidCookieManager.getInstance() }.getOrNull()
    }

    internal fun syncCookies(
        urls: Iterable<String?>,
        flushCookies: () -> Unit,
        readWebViewCookie: (String) -> String?,
        mergeIntoStore: (url: String, cookie: String) -> Unit
    ): Boolean {
        val candidateUrls = collectSyncUrls(urls)
        return syncEachCookie(candidateUrls, flushCookies, readWebViewCookie) { url, rawCookie ->
            mergeIntoStore(url, rawCookie)
        }
    }

    internal fun syncCookiesToTarget(
        targetKey: String?,
        sourceUrls: Iterable<String?>,
        flushCookies: () -> Unit,
        readWebViewCookie: (String) -> String?,
        mergeIntoStore: (key: String, cookie: String) -> Unit
    ): Boolean {
        val normalizedTarget = targetKey?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val candidateUrls = collectSyncUrls(sourceUrls).ifEmpty {
            collectSyncUrls(listOf(normalizedTarget))
        }
        return syncEachCookie(candidateUrls, flushCookies, readWebViewCookie) { _, rawCookie ->
            mergeIntoStore(normalizedTarget, rawCookie)
        }
    }

    private inline fun syncEachCookie(
        candidateUrls: List<String>,
        flushCookies: () -> Unit,
        readWebViewCookie: (String) -> String?,
        onCookie: (url: String, rawCookie: String) -> Unit
    ): Boolean {
        if (candidateUrls.isEmpty()) {
            return false
        }
        flushCookies()
        var syncedAny = false
        candidateUrls.forEach { url ->
            val rawCookie = readWebViewCookie(url)?.trim()
            if (!rawCookie.isNullOrEmpty()) {
                onCookie(url, rawCookie)
                syncedAny = true
            }
        }
        return syncedAny
    }

    internal fun collectSyncUrls(urls: Iterable<String?>): List<String> {
        val result = linkedSetOf<String>()
        urls.forEach { rawUrl ->
            val cleanUrl = stripUrlOptions(rawUrl) ?: return@forEach
            if (isHttpUrl(cleanUrl)) {
                result.add(cleanUrl)
            }
        }
        return result.toList()
    }

    internal fun stripUrlOptions(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val optionIndex = trimmed.indexOf(",{")
        val clean = if (optionIndex > 0) trimmed.substring(0, optionIndex).trim() else trimmed
        return clean.takeIf { it.isNotEmpty() }
    }

    private fun isHttpUrl(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }
}
