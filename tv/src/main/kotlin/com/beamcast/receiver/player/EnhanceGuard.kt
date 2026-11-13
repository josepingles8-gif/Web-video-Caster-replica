package com.beamcast.receiver.player

import android.content.Context
import com.beamcast.receiver.BuildConfig
import com.beamcast.receiver.CrashLog

/**
 * Stops picture enhancement from closing the app over and over. Enabling it is recorded on disk
 * until the first enhanced frame is on screen; if the app starts and finds that record, the last
 * attempt killed the process (some old GPU drivers crash), so enhancement stays off on this TV
 * until the app is updated.
 */
object EnhanceGuard {
    private const val PENDING = "enhance_pending"
    private const val BROKEN_IN = "enhance_broken_in_version"

    private fun prefs(context: Context) = context.getSharedPreferences("beamcast_player", Context.MODE_PRIVATE)

    /** Call once at process start. */
    fun startup(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(PENDING, false)) return
        p.edit().putBoolean(PENDING, false).putInt(BROKEN_IN, BuildConfig.VERSION_CODE).commit()
        val last = CrashLog.read(context)
        if (last == null || !last.startsWith("v${BuildConfig.VERSION_CODE} thread=")) {
            CrashLog.note(context, "Picture enhancement closed the app without a Java error.\n" + recentLog())
        }
    }

    /**
     * The app's own recent error log lines. They outlive the crashed process and are the only
     * evidence of a native fault (signal, GPU driver or codec errors) on a TV without adb.
     */
    private fun recentLog(): String {
        var out = ""
        val reader = Thread {
            out = runCatching {
                val proc = ProcessBuilder("logcat", "-d", "-v", "brief", "-b", "main", "-b", "system", "-b", "crash", "-t", "1500")
                    .redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.filter { it.startsWith("E/") || it.startsWith("F/") || it.startsWith("A/") || INTERESTING.containsMatchIn(it) }
                        .map { it.take(170) }.toList().takeLast(40).joinToString("\n")
                }
            }.getOrElse { "log unavailable: $it" }
        }
        reader.start()
        reader.join(4000)
        return out.takeLast(4000).ifBlank { "(no log lines)" }
    }

    private val INTERESTING = Regex("signal|egl|gles|omx|codec|effect|frameprocess|surfacetexture|outofmemory|lowmem|mali|adreno|powervr", RegexOption.IGNORE_CASE)

    fun supported(context: Context) = prefs(context).getInt(BROKEN_IN, -1) != BuildConfig.VERSION_CODE

    /** Written synchronously: the crash this guards against may come within milliseconds. */
    fun begin(context: Context) {
        prefs(context).edit().putBoolean(PENDING, true).commit()
    }

    fun done(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(PENDING, false)) p.edit().putBoolean(PENDING, false).apply()
    }
}
