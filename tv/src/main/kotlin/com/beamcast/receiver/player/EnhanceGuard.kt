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
            CrashLog.note(context, "Picture enhancement closed the app without a Java error (native GPU driver fault or out of memory).")
        }
    }

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
