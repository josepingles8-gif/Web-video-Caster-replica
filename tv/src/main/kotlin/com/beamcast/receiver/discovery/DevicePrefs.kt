package com.beamcast.receiver.discovery

import android.content.Context
import android.os.Build
import android.provider.Settings

/** User-editable, persisted device name. */
object DevicePrefs {
    private const val FILE = "beamcast"
    private const val KEY = "device_name"

    fun name(ctx: Context): String {
        val saved = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        if (!saved.isNullOrBlank()) return saved
        val system = runCatching { Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()
        return system?.takeIf { it.isNotBlank() } ?: Build.MODEL.ifBlank { "BeamCast TV" }
    }

    fun setName(ctx: Context, name: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, name.trim().take(60)).apply()
    }
}
