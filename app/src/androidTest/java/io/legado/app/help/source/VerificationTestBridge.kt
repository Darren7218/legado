package io.legado.app.help.source

internal object VerificationTestBridge {
    fun registerAttempt(waiter: Thread = Thread.currentThread()): String =
        SourceVerificationHelp.registerVerificationAttempt(waiter)

    fun clearAttempt(key: String?) {
        SourceVerificationHelp.clearResult(key)
    }
}
