package io.legado.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class WebViewVerificationContractTest {

    @Test
    fun resolveVerificationTargetUrlFallsBackCleanlyFromIntentUrlToBaseUrl() {
        assertEquals(
            "https://example.com/book/1,{\"method\":\"POST\"}",
            resolveVerificationTargetUrl(
                " https://example.com/book/1,{\"method\":\"POST\"} ",
                "https://example.com"
            )
        )
        assertEquals(
            "https://example.com",
            resolveVerificationTargetUrl("   ", " https://example.com ")
        )
        assertNull(resolveVerificationTargetUrl(null, "  "))
    }

    @Test
    fun backButtonNeverTrapsUserDuringChallengeOrFailedVerification() {
        assertTrue(
            shouldExitImmediatelyOnBack(
                sourceVerificationEnable = false,
                isCloudflareChallenge = false,
                isSavingVerification = false,
                verificationFailedOnce = false,
            )
        )
        assertTrue(
            shouldExitImmediatelyOnBack(
                sourceVerificationEnable = true,
                isCloudflareChallenge = true,
                isSavingVerification = false,
                verificationFailedOnce = false,
            )
        )
        assertTrue(
            shouldExitImmediatelyOnBack(
                sourceVerificationEnable = true,
                isCloudflareChallenge = false,
                isSavingVerification = true,
                verificationFailedOnce = false,
            )
        )
        assertTrue(
            shouldExitImmediatelyOnBack(
                sourceVerificationEnable = true,
                isCloudflareChallenge = false,
                isSavingVerification = false,
                verificationFailedOnce = true,
            )
        )
        assertFalse(
            shouldExitImmediatelyOnBack(
                sourceVerificationEnable = true,
                isCloudflareChallenge = false,
                isSavingVerification = false,
                verificationFailedOnce = false,
            )
        )
    }

    @Test
    fun formatVerificationErrorPrefersReadableMessageWithClassFallback() {
        assertEquals("HTTP 403", formatVerificationError(IllegalStateException(" HTTP 403 ")))
        assertEquals("IllegalStateException", formatVerificationError(IllegalStateException("")))
        assertEquals("", formatVerificationError(null))
    }

    @Test
    fun decodeEvaluatedHtmlResultHandlesNullBlanksAndEscapedQuotesCleanly() {
        assertEquals("", decodeEvaluatedHtmlResult(null))
        assertEquals("", decodeEvaluatedHtmlResult("null"))
        assertEquals("", decodeEvaluatedHtmlResult("  \"\" "))
        assertEquals(
            "<html><div title=\"ok\">\"</div></html>",
            decodeEvaluatedHtmlResult("\"\\u003Chtml\\u003E<div title=\\\"ok\\\">\\\"</div>\\u003C/html\\u003E\"")
        )
    }

    @Test
    fun webViewModelRefetchesUnconditionallyUsesResolvedSourceAndReportsErrors() {
        val source = projectFile("src/main/java/io/legado/app/ui/browser/WebViewModel.kt").readText()
        val saveFn = source.substringAfter("fun saveVerificationResult(")

        assertTrue(saveFn.contains("syncCookiesFromWebView(webView)"))
        assertTrue(saveFn.contains("if (isSavingVerification)"))
        assertTrue(saveFn.contains("SourceHelp.getSource(sourceOrigin, sourceType)"))
        assertTrue(saveFn.contains("decodeEvaluatedHtmlResult(rawHtml)"))
        assertTrue(saveFn.contains("runVerificationSave(onError, success)"))
        assertFalse(saveFn.contains("bookSourceDao.getBookSource"))
        assertFalse(saveFn.contains("if (html == null)"))
        assertFalse(saveFn.contains(".trim('\"')"))
        assertTrue(saveFn.contains("}.onError {"))
        assertTrue(saveFn.contains("}.onFinally {"))
    }

    @Test
    fun webViewActivitySyncsSafelyExposesManualBannerAndResetsChallengeState() {
        val activity = projectFile("src/main/java/io/legado/app/ui/browser/WebViewActivity.kt").readText()
        val layout = projectFile("src/main/res/layout/activity_web_view.xml").readText()

        assertFalse(activity.contains("CookieStore.setCookie("))
        assertTrue(activity.contains("viewModel.syncCookiesFromWebView("))
        assertTrue(activity.contains("binding.btnVerificationDone.setOnClickListener"))
        assertTrue(activity.contains("binding.titleBar.setNavigationOnClickListener"))
        assertTrue(activity.contains("WebViewCookieSync.enableThirdPartyCookies(currentWebView, true)"))
        assertTrue(activity.contains("override fun onReceivedError("))
        assertTrue(activity.contains("hasMainFrameLoadError = true"))
        assertTrue(activity.contains("submitVerificationResult()"))
        assertTrue(activity.contains("isCloudflareChallenge = false"))
        val backCallback = activity.substringAfter("onBackPressedDispatcher.addCallback(this)")
            .substringBefore("override fun onCompatCreateOptionsMenu")
        assertFalse(backCallback.contains("finish()"))
        assertTrue(backCallback.contains("close()"))
        assertTrue(layout.contains("@+id/ll_verification_bar"))
        assertTrue(layout.contains("@+id/btn_verification_done"))
        assertTrue(layout.contains("@+id/tv_verification_status"))
    }

    @Test
    fun loginBackstageCookieManagerAndPoolKeepCookiesSafe() {
        val loginFragment = projectFile("src/main/java/io/legado/app/ui/login/WebViewLoginFragment.kt").readText()
        val backstage = projectFile("src/main/java/io/legado/app/help/http/BackstageWebView.kt").readText()
        val cookieStore = projectFile("src/main/java/io/legado/app/help/http/CookieStore.kt").readText()
        val cookieManager = projectFile("src/main/java/io/legado/app/help/http/CookieManager.kt").readText()
        val webViewPool = projectFile("src/main/java/io/legado/app/help/webView/WebViewPool.kt").readText()

        assertFalse(loginFragment.contains("CookieStore.setCookie("))
        assertTrue(loginFragment.contains("WebViewCookieSync.syncFromWebViewToTarget("))
        assertTrue(loginFragment.contains("WebViewCookieSync.enableThirdPartyCookies(webView, true)"))
        assertFalse(backstage.contains("CookieStore.setCookie("))
        assertTrue(backstage.contains("WebViewCookieSync.syncFromWebViewToTarget(it, url)"))
        assertTrue(cookieStore.substringAfter("fun setWebCookie(").substringBefore("override fun replaceCookie").contains("cookieManager.flush()"))
        assertTrue(cookieManager.substringAfter("fun applyToWebView(").contains("CookieStore.setWebCookie("))
        assertTrue(webViewPool.contains("WebViewCookieSync.enableThirdPartyCookies(this, false)"))
    }

    @Test
    fun verificationStringsExistInDefaultAndChineseLocales() {
        val enStrings = stringNames(projectFile("src/main/res/values/strings.xml"))
        val zhStrings = stringNames(projectFile("src/main/res/values-zh/strings.xml"))
        val requiredKeys = setOf(
            "verification_banner_ready",
            "verification_banner_challenge",
            "verification_banner_verifying",
            "verification_banner_failed",
            "verification_banner_failed_no_detail",
            "verification_back_retry_hint",
            "verification_done_action",
        )

        assertTrue(enStrings.containsAll(requiredKeys))
        assertTrue(zhStrings.containsAll(requiredKeys))
    }

    private fun stringNames(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it).attributes?.getNamedItem("name")?.nodeValue }
            .toSet()
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first(File::isFile)
}
