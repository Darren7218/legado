package io.legado.app.help.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewCookieSyncTest {

    @Test
    fun syncCookiesFlushesAndMergesNonBlankCookiesAcrossDeduplicatedHttpUrls() {
        var flushCalls = 0
        val cookieStore = linkedMapOf(
            "https://example.com/chapter/1" to "session=keep; cf_clearance=stale"
        )
        val mergedCalls = mutableListOf<Pair<String, String>>()

        val synced = WebViewCookieSync.syncCookies(
            urls = listOf(
                "https://example.com/chapter/1",
                "https://example.com",
                "https://example.com/chapter/1,{\"method\":\"POST\"}",
                "about:blank",
                null
            ),
            flushCookies = { flushCalls++ },
            readWebViewCookie = { url ->
                when (url) {
                    "https://example.com/chapter/1" -> "cf_clearance=fresh"
                    "https://example.com" -> "  cf_bm=token  "
                    else -> null
                }
            },
            mergeIntoStore = { url, cookie ->
                mergedCalls += url to cookie
                cookieStore[url] = CookieStore.mergeCookieValues(cookieStore[url].orEmpty(), cookie)
            }
        )

        assertTrue(synced)
        assertEquals(1, flushCalls)
        assertEquals(
            listOf(
                "https://example.com/chapter/1" to "cf_clearance=fresh",
                "https://example.com" to "cf_bm=token"
            ),
            mergedCalls
        )
        assertEquals(
            mapOf("session" to "keep", "cf_clearance" to "fresh"),
            CookieStore.cookieToMap(cookieStore["https://example.com/chapter/1"].orEmpty())
        )
    }

    @Test
    fun syncCookiesPreservesExistingStoreEntriesWhenWebViewReturnsNullOrBlank() {
        var flushCalls = 0
        val cookieStore = linkedMapOf(
            "https://example.com" to "session=existing; token=123"
        )
        val mergedCalls = mutableListOf<Pair<String, String>>()

        val synced = WebViewCookieSync.syncCookies(
            urls = listOf("https://example.com/login", "https://example.com"),
            flushCookies = { flushCalls++ },
            readWebViewCookie = { url ->
                if (url == "https://example.com/login") null else "   "
            },
            mergeIntoStore = { url, cookie ->
                mergedCalls += url to cookie
                cookieStore[url] = cookie
            }
        )

        assertFalse(synced)
        assertEquals(1, flushCalls)
        assertTrue(mergedCalls.isEmpty())
        assertEquals("session=existing; token=123", cookieStore["https://example.com"])
    }

    @Test
    fun collectSyncUrlsFiltersNonHttpAndStripsLegadoOptions() {
        assertEquals(
            listOf(
                "https://example.com/search",
                "http://cdn.example.com/page?a=1"
            ),
            WebViewCookieSync.collectSyncUrls(
                listOf(
                    null,
                    "   ",
                    "about:blank",
                    "data:text/html,<h1>hi</h1>",
                    "javascript:void(0)",
                    "https://example.com/search,{\"method\":\"POST\",\"body\":\"q=1\"}",
                    "https://example.com/search",
                    "http://cdn.example.com/page?a=1"
                )
            )
        )
        assertNull(WebViewCookieSync.stripUrlOptions("   "))
        assertEquals(
            "https://example.com/path?x=1,2",
            WebViewCookieSync.stripUrlOptions("https://example.com/path?x=1,2")
        )
    }

    @Test
    fun syncCookiesSkipsFlushWhenNoHttpUrlsExist() {
        var flushCalls = 0
        var reads = 0
        var writes = 0

        val synced = WebViewCookieSync.syncCookies(
            urls = listOf(null, "", "about:blank", "file:///android_asset/help.html"),
            flushCookies = { flushCalls++ },
            readWebViewCookie = { reads++; null },
            mergeIntoStore = { _, _ -> writes++ }
        )

        assertFalse(synced)
        assertEquals(0, flushCalls)
        assertEquals(0, reads)
        assertEquals(0, writes)
    }

    @Test
    fun syncCookiesToTargetMergesNonBlankCookiesAndPreservesStoreOnNull() {
        var flushCalls = 0
        val cookieStore = linkedMapOf(
            "https://book.example.com" to "existing=1"
        )

        val synced = WebViewCookieSync.syncCookiesToTarget(
            targetKey = "https://book.example.com",
            sourceUrls = listOf(
                "https://sso.example.com/login",
                "https://sso.example.com/callback",
                "about:blank"
            ),
            flushCookies = { flushCalls++ },
            readWebViewCookie = { url ->
                when (url) {
                    "https://sso.example.com/login" -> null
                    "https://sso.example.com/callback" -> "auth=token2"
                    else -> null
                }
            },
            mergeIntoStore = { key, cookie ->
                cookieStore[key] = CookieStore.mergeCookieValues(cookieStore[key].orEmpty(), cookie)
            }
        )

        assertTrue(synced)
        assertEquals(1, flushCalls)
        assertEquals(
            mapOf("existing" to "1", "auth" to "token2"),
            CookieStore.cookieToMap(cookieStore["https://book.example.com"].orEmpty())
        )

        val emptySync = WebViewCookieSync.syncCookiesToTarget(
            targetKey = "https://book.example.com",
            sourceUrls = listOf(null, "about:blank"),
            flushCookies = { flushCalls++ },
            readWebViewCookie = { null },
            mergeIntoStore = { key, cookie -> cookieStore[key] = cookie }
        )

        assertFalse(emptySync)
        assertEquals(2, flushCalls)
        assertEquals(
            mapOf("existing" to "1", "auth" to "token2"),
            CookieStore.cookieToMap(cookieStore["https://book.example.com"].orEmpty())
        )
    }

    @Test
    fun pruneCookieKeysRemovesOverriddenKeysWhilePreservingRemainingSessionCookies() {
        assertEquals(
            "sid=keep",
            CookieManager.pruneCookieKeys(
                "sid=keep; cf_clearance=stale; __cf_bm=stale2",
                setOf("cf_clearance", "__cf_bm")
            )
        )
        assertNull(
            CookieManager.pruneCookieKeys(
                "cf_clearance=stale",
                setOf("cf_clearance")
            )
        )
        assertEquals(
            "sid=keep",
            CookieManager.pruneCookieKeys("sid=keep", emptySet())
        )
        assertNull(CookieManager.pruneCookieKeys(null, setOf("cf_clearance")))
    }
}
