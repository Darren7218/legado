package io.legado.app.ui.browser

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.SourceType
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.help.source.VerificationTestBridge
import io.legado.app.ui.association.VerificationCodeActivity
import io.legado.app.ui.association.VerificationCodeDialog
import io.legado.app.utils.NetworkUtils
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class WebViewVerificationUiTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun challengeBannerCompletesVerificationAndPrunesStaleSessionCookie() {
        val verifiedFetches = AtomicInteger(0)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val cookies = session.headers["cookie"].orEmpty()
                val html = if (cookies.contains("cf_clearance=fresh_verified")) {
                    verifiedFetches.incrementAndGet()
                    """
                    <html>
                      <head><title>Verified</title></head>
                      <body><div id="status">VERIFIED_OK</div></body>
                    </html>
                    """.trimIndent()
                } else {
                    """
                    <html>
                      <head>
                        <title>Challenge</title>
                        <meta name="viewport" content="width=device-width,initial-scale=1">
                        <script>
                          window._cf_chl_opt = { mode: "manual" };
                          function passChallenge() {
                            document.cookie = "cf_clearance=fresh_verified; path=/";
                            window._cf_chl_opt = false;
                            window.location.reload();
                          }
                        </script>
                      </head>
                      <body style="margin:24px;font-family:sans-serif">
                        <h3>Manual Verification Check</h3>
                        <button id="verify" onclick="passChallenge()"
                          style="width:240px;height:80px;font-size:18px">Complete Check</button>
                      </body>
                    </html>
                    """.trimIndent()
                }
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
            }
        }
        server.start()
        val url = "http://127.0.0.1:${server.listeningPort}/challenge"
        val domain = NetworkUtils.getSubDomain(url)
        val key = VerificationTestBridge.registerAttempt()
        var scenario: ActivityScenario<WebViewActivity>? = null
        try {
            clearCookiesForDomain(url, domain)
            CacheManager.putMemory("${domain}_session_cookie", "sid=keep; cf_clearance=old_stale")
            CookieStore.replaceCookie(url, "cf_clearance=old_stale")

            scenario = ActivityScenario.launch(
                Intent(context, WebViewActivity::class.java).apply {
                    putExtra("title", "Challenge Test")
                    putExtra("url", url)
                    putExtra("sourceOrigin", url)
                    putExtra("sourceName", "Emulator Challenge Source")
                    putExtra("sourceType", SourceType.book)
                    putExtra("sourceVerificationEnable", true)
                    putExtra("refetchAfterSuccess", true)
                    putExtra("verificationResultKey", key)
                }
            )

            val expectedChallengeText = context.getString(R.string.verification_banner_challenge)
            waitUntil(25_000, "Challenge banner did not become active") {
                var matched = false
                scenario.onActivity { activity ->
                    val bar = activity.findViewById<View>(R.id.ll_verification_bar)
                    val status = activity.findViewById<TextView>(R.id.tv_verification_status)
                    val doneBtn = activity.findViewById<View>(R.id.btn_verification_done)
                    matched = bar.visibility == View.VISIBLE &&
                        bar.height > 0 &&
                        doneBtn.isEnabled &&
                        status.text.toString() == expectedChallengeText
                }
                matched
            }

            waitUntil(5_000, "WebView challenge button was not rendered") {
                val rect = JSONObject(evaluate(scenario, """
                    (() => {
                        const btn = document.getElementById('verify');
                        if (!btn) return {ready:false};
                        const r = btn.getBoundingClientRect();
                        return {ready: r.width > 0 && r.height > 0};
                    })()
                """.trimIndent()))
                rect.optBoolean("ready", false)
            }
            screenshot("verification-challenge-banner")

            val button = JSONObject(evaluate(scenario, """
                (() => {
                    const r = document.getElementById('verify').getBoundingClientRect();
                    return {x: r.x + r.width / 2, y: r.y + r.height / 2, width: innerWidth};
                })()
            """.trimIndent()))
            lateinit var tapCoords: FloatArray
            scenario.onActivity { activity ->
                val webView = activity.findViewById<FrameLayout>(R.id.web_view_container).getChildAt(0)
                val loc = IntArray(2)
                webView.getLocationOnScreen(loc)
                val scale = webView.width / button.getDouble("width")
                tapCoords = floatArrayOf(
                    (loc[0] + button.getDouble("x") * scale).toFloat(),
                    (loc[1] + button.getDouble("y") * scale).toFloat(),
                )
            }
            tapScreen(tapCoords[0], tapCoords[1])

            var diagnostic = ""
            waitUntil(60_000, { "Verification did not finish and return fetched HTML ($diagnostic)" }) {
                var statusText = ""
                var webUrl: String? = null
                var finishingOrDestroyed = false
                if (scenario.state != Lifecycle.State.DESTROYED) {
                    scenario.onActivity { activity ->
                        statusText = activity.findViewById<TextView>(R.id.tv_verification_status)?.text?.toString().orEmpty()
                        val container = activity.findViewById<FrameLayout>(R.id.web_view_container)
                        webUrl = (container?.getChildAt(0) as? WebView)?.url
                        finishingOrDestroyed = activity.isFinishing || activity.isDestroyed
                    }
                } else {
                    finishingOrDestroyed = true
                }
                val res = SourceVerificationHelp.getResult(key)
                diagnostic = "verifiedFetches=${verifiedFetches.get()}, tap=(${tapCoords[0]},${tapCoords[1]}), " +
                    "banner='$statusText', webUrl=$webUrl, result=$res, state=${scenario.state}, " +
                    "finishing=$finishingOrDestroyed, store=${CookieStore.getCookie(url)}"
                res != null && (scenario.state == Lifecycle.State.DESTROYED || finishingOrDestroyed)
            }

            val result = SourceVerificationHelp.getResult(key)!!
            assertTrue(
                "Refetch should contain VERIFIED_OK but was ${result.second}",
                result.second.contains("VERIFIED_OK")
            )
            assertTrue(verifiedFetches.get() >= 2)
            assertEquals(
                mapOf("sid" to "keep", "cf_clearance" to "fresh_verified"),
                CookieStore.cookieToMap(CookieStore.getCookie(url))
            )
        } finally {
            scenario?.close()
            clearCookiesForDomain(url, domain)
            VerificationTestBridge.clearAttempt(key)
            server.stop()
        }
    }

    @Test
    fun initialPostFailureShowsFailedBannerRetriesOnRefreshAndExitsOnBack() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val requestCount = AtomicInteger(0)
        val keepRunning = AtomicBoolean(true)
        val serverThread = thread(isDaemon = true) {
            while (keepRunning.get()) {
                val client = try {
                    serverSocket.accept()
                } catch (_: Throwable) {
                    break
                }
                val attempt = requestCount.incrementAndGet()
                client.use { socket ->
                    val input = socket.getInputStream()
                    val buf = ByteArray(2048)
                    runCatching {
                        socket.soTimeout = 500
                        input.read(buf)
                    }
                    if (attempt == 1) {
                        return@use
                    }
                    val body = "<html><head><title>Recovered</title></head><body>POST_OK</body></html>"
                        .toByteArray()
                    val header = ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                    socket.getOutputStream().apply {
                        write(header)
                        write(body)
                        flush()
                    }
                }
            }
        }

        val url = "http://127.0.0.1:$port/flaky,{\"method\":\"POST\",\"body\":\"x=1\"}"
        val key = VerificationTestBridge.registerAttempt()
        var scenario: ActivityScenario<WebViewActivity>? = null
        try {
            scenario = ActivityScenario.launch(
                Intent(context, WebViewActivity::class.java).apply {
                    putExtra("title", "Flaky POST Test")
                    putExtra("url", url)
                    putExtra("sourceOrigin", "http://127.0.0.1:$port")
                    putExtra("sourceName", "Flaky Source")
                    putExtra("sourceType", SourceType.book)
                    putExtra("sourceVerificationEnable", true)
                    putExtra("verificationResultKey", key)
                }
            )

            waitUntil(25_000, "Failed banner did not appear after initial POST failure") {
                var failedVisible = false
                scenario.onActivity { activity ->
                    val bar = activity.findViewById<View>(R.id.ll_verification_bar)
                    val status = activity.findViewById<TextView>(R.id.tv_verification_status)
                    failedVisible = bar.visibility == View.VISIBLE &&
                        (status.text.toString().contains("failed", ignoreCase = true) ||
                            status.text.toString().contains("失败"))
                }
                failedVisible
            }
            screenshot("verification-failed-banner")

            lateinit var refreshCoords: FloatArray
            scenario.onActivity { activity ->
                refreshCoords = centerOnScreen(activity.findViewById(R.id.menu_web_refresh))
            }
            tapScreen(refreshCoords[0], refreshCoords[1])

            val readyText = context.getString(R.string.verification_banner_ready)
            waitUntil(10_000, "Retry via refresh did not reach READY state") {
                var ready = false
                scenario.onActivity { activity ->
                    val status = activity.findViewById<TextView>(R.id.tv_verification_status)
                    ready = status.text.toString() == readyText
                }
                ready
            }
            assertTrue(requestCount.get() >= 2)
        } finally {
            keepRunning.set(false)
            runCatching { serverSocket.close() }
            runCatching { serverThread.join(1000) }
            scenario?.close()
            VerificationTestBridge.clearAttempt(key)
        }
    }

    @Test
    fun backExitsImmediatelyAfterFailedInitialLoad() {
        val closedSocket = ServerSocket(0)
        val deadPort = closedSocket.localPort
        closedSocket.close()
        val url = "http://127.0.0.1:$deadPort/dead,{\"method\":\"POST\",\"body\":\"x=1\"}"
        val key = VerificationTestBridge.registerAttempt()
        var scenario: ActivityScenario<WebViewActivity>? = null
        try {
            scenario = ActivityScenario.launch(
                Intent(context, WebViewActivity::class.java).apply {
                    putExtra("title", "Back Exit Test")
                    putExtra("url", url)
                    putExtra("sourceOrigin", "http://127.0.0.1:$deadPort")
                    putExtra("sourceName", "Dead Source")
                    putExtra("sourceType", SourceType.book)
                    putExtra("sourceVerificationEnable", true)
                    putExtra("verificationResultKey", key)
                }
            )

            waitUntil(25_000, "Failed banner did not appear") {
                var failedVisible = false
                scenario.onActivity { activity ->
                    val bar = activity.findViewById<View>(R.id.ll_verification_bar)
                    val status = activity.findViewById<TextView>(R.id.tv_verification_status)
                    failedVisible = bar.visibility == View.VISIBLE &&
                        (status.text.toString().contains("failed", ignoreCase = true) ||
                            status.text.toString().contains("失败"))
                }
                failedVisible
            }

            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
            }

            waitUntil(10_000, "Back did not close WebViewActivity immediately after failure") {
                if (scenario.state == Lifecycle.State.DESTROYED) {
                    true
                } else {
                    var finishing = false
                    scenario.onActivity { activity ->
                        finishing = activity.isFinishing || activity.isDestroyed
                    }
                    finishing
                }
            }
        } finally {
            scenario?.close()
            VerificationTestBridge.clearAttempt(key)
        }
    }

    @Test
    fun verificationCodeDialogRefreshesRejectsBlankAndSubmitsTrimmedViaImeDone() {
        val imageRequests = AtomicInteger(0)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val count = imageRequests.incrementAndGet()
                val bitmap = Bitmap.createBitmap(120, 48, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(if (count % 2 == 1) Color.BLUE else Color.GREEN)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                val bytes = out.toByteArray()
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "image/png",
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                ).apply {
                    addHeader("Cache-Control", "no-store")
                }
            }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.listeningPort}"
        val imageUrl = "$baseUrl/captcha.png"
        val key = VerificationTestBridge.registerAttempt()
        var scenario: ActivityScenario<VerificationCodeActivity>? = null
        try {
            scenario = ActivityScenario.launch(
                Intent(context, VerificationCodeActivity::class.java).apply {
                    putExtra("imageUrl", imageUrl)
                    putExtra("sourceOrigin", baseUrl)
                    putExtra("sourceName", "Captcha Test Source")
                    putExtra("sourceType", SourceType.book)
                    putExtra("verificationResultKey", key)
                }
            )

            var dialogDiag = ""
            waitUntil(60_000, { "VerificationCodeDialog and captcha image did not load ($dialogDiag)" }) {
                var ready = false
                scenario.onActivity { activity ->
                    val fragments = activity.supportFragmentManager.fragments.map { it.javaClass.simpleName }
                    val dialog = findVerificationDialog(activity)
                    val input = dialog?.dialog?.findViewById<View>(R.id.verification_code)
                    val img = dialog?.dialog?.findViewById<android.widget.ImageView>(R.id.verification_code_image_view)
                    val refreshBtn = dialog?.dialog?.findViewById<View>(R.id.menu_web_refresh)
                    val toolBar = dialog?.dialog?.findViewById<androidx.appcompat.widget.Toolbar>(R.id.tool_bar)
                    val menuIds = toolBar?.menu?.let { m -> (0 until m.size()).map { m.getItem(it).itemId } }
                    val logs = io.legado.app.constant.AppLog.logs.take(5).map { "${it.second}:${it.third?.message}" }
                    dialogDiag = "requests=${imageRequests.get()}, fragments=$fragments, " +
                        "dialog=${dialog != null}, showing=${dialog?.dialog?.isShowing}, " +
                        "input=${input != null}, inputShown=${input?.isShown}, " +
                        "img=${img?.width}x${img?.height}, drawable=${img?.drawable != null}, " +
                        "refreshBtn=${refreshBtn != null}, refreshW=${refreshBtn?.width}, " +
                        "menuSize=${menuIds?.size}, logs=$logs"
                    ready = imageRequests.get() >= 1 &&
                        img?.drawable != null &&
                        input != null && input.isShown &&
                        refreshBtn != null && refreshBtn.width > 0
                }
                ready
            }
            screenshot("verification-code-dialog")

            val initialCount = imageRequests.get()
            lateinit var refreshCoords: FloatArray
            scenario.onActivity { activity ->
                val dialog = findVerificationDialog(activity)!!
                refreshCoords = centerOnScreen(dialog.requireDialog().findViewById(R.id.menu_web_refresh))
            }
            tapScreen(refreshCoords[0], refreshCoords[1])
            waitUntil(30_000, "Refresh menu did not reload captcha image") {
                imageRequests.get() > initialCount
            }

            // Empty submission should keep dialog open and leave verification result unset.
            lateinit var okCoords: FloatArray
            scenario.onActivity { activity ->
                val dialog = findVerificationDialog(activity)!!
                okCoords = centerOnScreen(dialog.requireDialog().findViewById(R.id.menu_ok))
            }
            tapScreen(okCoords[0], okCoords[1])
            instrumentation.waitForIdleSync()
            assertNull(SourceVerificationHelp.getResult(key))
            scenario.onActivity { activity ->
                val dialog = findVerificationDialog(activity)
                assertTrue("Dialog should remain open on blank code", dialog?.dialog?.isShowing == true)
            }

            // Enter whitespace-padded code and submit via IME_ACTION_DONE.
            scenario.onActivity { activity ->
                val dialog = findVerificationDialog(activity)!!
                val editText = dialog.requireDialog().findViewById<EditText>(R.id.verification_code)
                editText.setText("  AB79  ")
                editText.onEditorAction(EditorInfo.IME_ACTION_DONE)
            }

            waitUntil(5_000, "IME Done did not submit trimmed verification code") {
                SourceVerificationHelp.getResult(key) != null
            }
            assertEquals("AB79", SourceVerificationHelp.getResult(key)?.second)
        } finally {
            scenario?.close()
            VerificationTestBridge.clearAttempt(key)
            server.stop()
        }
    }

    private fun findVerificationDialog(activity: VerificationCodeActivity): DialogFragment? {
        return activity.supportFragmentManager.fragments
            .filterIsInstance<VerificationCodeDialog>()
            .firstOrNull()
    }

    private fun centerOnScreen(view: View): FloatArray {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return floatArrayOf(loc[0] + view.width / 2f, loc[1] + view.height / 2f)
    }

    private fun tapScreen(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val up = MotionEvent.obtain(downTime, downTime + 40, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun clearCookiesForDomain(url: String, domain: String) {
        CookieStore.removeCookie(url)
        CacheManager.deleteMemory("${domain}_session_cookie")
        CacheManager.deleteMemory("${domain}_cookie")
        runOnMain {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        }
    }

    private fun runOnMain(block: () -> Unit) {
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    private fun waitUntil(timeoutMs: Long, message: String, predicate: () -> Boolean) {
        waitUntil(timeoutMs, { message }, predicate)
    }

    private fun waitUntil(timeoutMs: Long, messageProvider: () -> String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        throw AssertionError(messageProvider())
    }

    private fun evaluate(scenario: ActivityScenario<WebViewActivity>, script: String): String {
        val complete = CountDownLatch(1)
        val result = AtomicReference<String>()
        scenario.onActivity { activity ->
            val container = activity.findViewById<FrameLayout>(R.id.web_view_container)
            (container.getChildAt(0) as WebView).evaluateJavascript(script) {
                result.set(it)
                complete.countDown()
            }
        }
        assertTrue("WebView JavaScript did not finish", complete.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = context.getExternalFilesDir("ui-regression")
            directory?.mkdirs()
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
