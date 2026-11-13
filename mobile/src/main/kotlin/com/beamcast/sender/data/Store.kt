package com.beamcast.sender.data

import android.content.Context
import com.beamcast.protocol.BeamProtocol
import com.beamcast.protocol.VideoQuality
import com.beamcast.sender.cast.Device

class Store private constructor(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("beamcast", Context.MODE_PRIVATE)
    val bookmarks = EntryList.fromJson(prefs.getString("bookmarks", null))
    val history = EntryList.fromJson(prefs.getString("history", null), 500)

    var lastDevice: Device?
        get() = prefs.getString("device", null)?.let {
            runCatching { BeamProtocol.json.decodeFromString(Device.serializer(), it) }.getOrNull()
        }
        set(v) {
            prefs.edit().putString("device", v?.let { BeamProtocol.json.encodeToString(Device.serializer(), it) }).apply()
        }

    var enhance: Boolean
        get() = prefs.getBoolean("enhance", false)
        set(v) { prefs.edit().putBoolean("enhance", v).apply() }

    var quality: String
        get() = prefs.getString("quality", VideoQuality.MAX) ?: VideoQuality.MAX
        set(v) { prefs.edit().putString("quality", v).apply() }

    fun saveBookmarks() = prefs.edit().putString("bookmarks", bookmarks.toJson()).apply()
    fun saveHistory() = prefs.edit().putString("history", history.toJson()).apply()

    companion object {
        @Volatile private var instance: Store? = null
        fun get(ctx: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(ctx.applicationContext).also { instance = it }
        }
    }
}
