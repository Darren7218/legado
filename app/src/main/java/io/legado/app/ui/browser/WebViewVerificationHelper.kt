package io.legado.app.ui.browser

import org.apache.commons.text.StringEscapeUtils

/**
 * Pure state and URL helpers for manual verification in [WebViewActivity] and [WebViewModel].
 */
internal enum class VerificationBannerState {
    LOADING,
    READY,
    CHALLENGE_ACTIVE,
    VERIFYING,
    FAILED,
}

internal fun resolveVerificationTargetUrl(intentUrl: String?, baseUrl: String): String? {
    return intentUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?: baseUrl.trim().takeIf { it.isNotEmpty() }
}

internal fun shouldExitImmediatelyOnBack(
    sourceVerificationEnable: Boolean,
    isCloudflareChallenge: Boolean,
    isSavingVerification: Boolean,
    verificationFailedOnce: Boolean,
): Boolean {
    if (!sourceVerificationEnable) {
        return true
    }
    return isCloudflareChallenge || isSavingVerification || verificationFailedOnce
}

internal fun formatVerificationError(error: Throwable?): String {
    return error?.localizedMessage?.trim()?.takeIf { it.isNotEmpty() }
        ?: error?.message?.trim()?.takeIf { it.isNotEmpty() }
        ?: error?.javaClass?.simpleName.orEmpty()
}

internal fun decodeEvaluatedHtmlResult(rawResult: String?): String {
    val trimmed = rawResult?.trim() ?: return ""
    if (trimmed.isEmpty() || trimmed == "null") {
        return ""
    }
    val unwrapped = if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
        trimmed.substring(1, trimmed.length - 1)
    } else {
        trimmed
    }
    return StringEscapeUtils.unescapeJson(unwrapped)
}
