package io.legado.app.ui.browser

import android.app.Application
import android.content.Intent
import android.util.Base64
import android.webkit.URLUtil
import android.webkit.WebView
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ACache
import io.legado.app.utils.FileDoc
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import java.util.Date
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION2

class WebViewModel(application: Application) : BaseViewModel(application) {
    var source: BaseSource? = null
    var intent: Intent? = null
    var baseUrl: String = ""
    var html: String? = null
    var localHtml: Boolean = false
    val headerMap: HashMap<String, String> = hashMapOf()
    var sourceVerificationEnable: Boolean = false
    var refetchAfterSuccess: Boolean = true
    var verificationResultKey: String? = null
    var sourceName: String = ""
    var sourceOrigin: String = ""
    var sourceType = SourceType.book

    fun initData(
        intent: Intent,
        onError: ((Throwable) -> Unit)? = null,
        success: () -> Unit
    ) {
        execute {
            this@WebViewModel.intent = intent
            val url = intent.getStringExtra("url")
                ?: throw NoStackTraceException("url不能为空")
            sourceName = intent.getStringExtra("sourceName") ?: ""
            sourceOrigin = intent.getStringExtra("sourceOrigin") ?: ""
            sourceType = intent.getIntExtra("sourceType", SourceType.book)
            sourceVerificationEnable = intent.getBooleanExtra("sourceVerificationEnable", false)
            refetchAfterSuccess = intent.getBooleanExtra("refetchAfterSuccess", true)
            verificationResultKey = intent.getStringExtra("verificationResultKey")
            html = intent.getStringExtra("html")?.let{
                localHtml = true
                val headIndex = it.indexOf("<head", ignoreCase = true)
                if (headIndex >= 0) {
                    val closingHeadIndex = it.indexOf('>', startIndex = headIndex)
                    if (closingHeadIndex >= 0) {
                        val insertPos = closingHeadIndex + 1
                        StringBuilder(it).insert(insertPos, "<script>$JS_INJECTION2</script>").toString()
                    } else {
                        "<head><script>$JS_INJECTION2</script></head>$it"
                    }
                } else {
                    "<head><script>$JS_INJECTION2</script></head>$it"
                }
            }
            source = SourceHelp.getSource(sourceOrigin, sourceType)
            val analyzeUrl = AnalyzeUrl(url, source = source, coroutineContext = coroutineContext)
            baseUrl = analyzeUrl.url
            headerMap.putAll(analyzeUrl.headerMap)
            if (analyzeUrl.isPost()) {
                html = analyzeUrl.getStrResponseAwait(useWebView = false).body
            }
        }.onSuccess {
            success.invoke()
        }.onError {
            context.toastOnUi("error\n${it.localizedMessage}")
            it.printOnDebug()
            onError?.invoke(it)
        }
    }

    fun saveImage(webPic: String?, path: String) {
        webPic ?: return
        execute {
            val fileName = "${AppConst.fileNameFormat.format(Date(System.currentTimeMillis()))}.jpg"
            webData2bitmap(webPic)?.let { byteArray ->
                val fileDoc = FileDoc.fromDir(path)
                val picFile = fileDoc.createFileIfNotExist(fileName)
                picFile.openOutputStream().getOrThrow().use {
                    it.write(byteArray)
                }
            } ?: throw Throwable("NULL")
        }.onError {
            ACache.get().remove(imagePathKey)
            context.toastOnUi("保存图片失败:${it.localizedMessage}")
        }.onSuccess {
            context.toastOnUi("保存成功")
        }
    }

    private suspend fun webData2bitmap(data: String): ByteArray? {
        return if (URLUtil.isValidUrl(data)) {
            okHttpClient.newCallResponseBody {
                url(data)
            }.bytes()
        } else {
            Base64.decode(data.split(",").toTypedArray()[1], Base64.DEFAULT)
        }
    }

    var isSavingVerification = false
        private set

    fun syncCookiesFromWebView(webView: WebView, pageUrl: String? = webView.url): Boolean {
        return io.legado.app.help.http.WebViewCookieSync.syncFromWebView(
            pageUrl,
            baseUrl,
            intent?.getStringExtra("url")
        )
    }

    fun saveVerificationResult(
        webView: WebView,
        onError: ((Throwable) -> Unit)? = null,
        success: () -> Unit
    ) {
        if (!sourceVerificationEnable) {
            return success.invoke()
        }
        if (isSavingVerification) {
            return
        }
        syncCookiesFromWebView(webView)
        if (refetchAfterSuccess) {
            isSavingVerification = true
            runVerificationSave(onError, success) {
                val targetUrl = resolveVerificationTargetUrl(
                    intent?.getStringExtra("url"),
                    baseUrl
                ) ?: throw io.legado.app.exception.NoStackTraceException("Verification URL is missing")
                val activeSource = source
                    ?: io.legado.app.help.source.SourceHelp.getSource(sourceOrigin, sourceType)
                val fetchedHtml = AnalyzeUrl(
                    targetUrl,
                    headerMapF = headerMap,
                    source = activeSource,
                    coroutineContext = coroutineContext
                ).getStrResponseAwait(useWebView = false).body ?: ""
                SourceVerificationHelp.setResult(verificationResultKey, fetchedHtml, baseUrl)
            }
        } else {
            isSavingVerification = true
            webView.evaluateJavascript("document.documentElement.outerHTML") { rawHtml ->
                val pageUrl = webView.url ?: ""
                runVerificationSave(onError, success) {
                    val decodedHtml = decodeEvaluatedHtmlResult(rawHtml)
                    if (decodedHtml.isBlank()) {
                        throw NoStackTraceException("Verification page content is empty")
                    }
                    html = decodedHtml
                    SourceVerificationHelp.setResult(
                        verificationResultKey,
                        decodedHtml,
                        pageUrl,
                    )
                }
            }
        }
    }

    private fun runVerificationSave(
        onError: ((Throwable) -> Unit)?,
        success: () -> Unit,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
    ) {
        execute {
            block()
        }.onSuccess {
            isSavingVerification = false
            success.invoke()
        }.onError { error ->
            isSavingVerification = false
            io.legado.app.constant.AppLog.put(
                "保存验证结果失败\n${error.localizedMessage}",
                error
            )
            onError?.invoke(error)
        }.onFinally {
            isSavingVerification = false
        }
    }

    fun disableSource(block: () -> Unit) {
        execute {
            SourceHelp.enableSource(sourceOrigin, sourceType, false)
        }.onSuccess {
            block.invoke()
        }
    }

    fun deleteSource(block: () -> Unit) {
        execute {
            SourceHelp.deleteSource(sourceOrigin, sourceType)
        }.onSuccess {
            block.invoke()
        }
    }

}
