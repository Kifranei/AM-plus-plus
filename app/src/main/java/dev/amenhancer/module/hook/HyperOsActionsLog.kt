package dev.amenhancer.module.hook

import android.util.Log
import java.io.File

/**
 * Diagnostics for the HyperOS media-notification restore. Every line is prefixed `hyperos-actions:`
 * and goes to the LSPosed module log, logcat (tag `AMPP-hyperos`) and, in the Apple Music process,
 * `files/ampp-hyperos-actions.log` (capped, newest lines kept).
 */
internal object HyperOsActionsLog {
    const val PREFIX = "hyperos-actions:"
    private const val TAG = "AMPP-hyperos"
    private const val MAX_BYTES = 192 * 1024

    @Volatile private var file: File? = null

    fun bindFile(dir: File?) {
        file = dir?.let { File(it, "ampp-hyperos-actions.log") }
    }

    fun i(message: String) = emit(message, null)

    fun e(message: String, error: Throwable) = emit(message, error)

    private fun emit(message: String, error: Throwable?) {
        val line = "$PREFIX $message"
        runCatching { ModernXposedRuntime.log(line, error) }
        runCatching { if (error == null) Log.i(TAG, line) else Log.e(TAG, line, error) }
        val target = file ?: return
        runCatching {
            synchronized(this) {
                if (target.length() > MAX_BYTES) {
                    target.writeText(target.readText().takeLast(MAX_BYTES / 2))
                }
                target.appendText(
                    "${java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} " +
                        "pid=${android.os.Process.myPid()} $line" +
                        (error?.let { " :: ${Log.getStackTraceString(it)}" } ?: "") + "\n",
                )
            }
        }
    }
}
