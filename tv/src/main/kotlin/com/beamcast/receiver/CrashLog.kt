package com.beamcast.receiver

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the stack trace of the last crash so the phone app can show it: a TV has no practical way
 * to read logs, and without them a crash cannot be diagnosed.
 */
object CrashLog {
    private const val MAX_CHARS = 3000

    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(app).writeText(describe(thread.name, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun describe(threadName: String, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return "v${BuildConfig.VERSION_CODE} thread=$threadName\n$trace".take(MAX_CHARS)
    }

    /** A crash that leaves no trace (killed by a native driver fault) is recorded with this text. */
    fun note(context: Context, text: String) {
        runCatching { file(context).writeText("v${BuildConfig.VERSION_CODE} $text") }
    }

    fun read(context: Context): String? = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
}
