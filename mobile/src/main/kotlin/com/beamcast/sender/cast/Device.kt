package com.beamcast.sender.cast

import android.content.Context
import com.beamcast.sender.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class Device(val name: String, val host: String, val port: Int) {
    val baseUrl: String get() = if (host.contains(':')) "http://[$host]:$port" else "http://$host:$port"
    val label: String get() = "$name ($host:$port)"
}

/** The currently connected receiver (process-wide). */
object CastSession {
    val device = MutableStateFlow<Device?>(null)

    fun select(ctx: Context, d: Device?) {
        device.value = d
        if (d != null) Store.get(ctx).lastDevice = d
    }
}
