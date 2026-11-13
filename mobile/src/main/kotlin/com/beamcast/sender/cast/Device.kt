package com.beamcast.sender.cast

import android.content.Context
import com.beamcast.sender.cast.chromecast.ChromecastSupport
import com.beamcast.sender.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

enum class DeviceType { BEAM, DLNA, CHROMECAST }

@Serializable
data class Device(
    val name: String,
    val host: String,
    val port: Int,
    /** Old saved devices have no type and are BeamCast receivers. */
    val type: DeviceType = DeviceType.BEAM,
    /** DLNA: absolute AVTransport / RenderingControl control URLs and the device UDN. */
    val avTransportUrl: String? = null,
    val renderingControlUrl: String? = null,
    val udn: String? = null,
    /** Chromecast: MediaRouter route id (only valid while discovered). */
    val routeId: String? = null,
) {
    val baseUrl: String get() = if (host.contains(':')) "http://[$host]:$port" else "http://$host:$port"
    val label: String get() = if (type == DeviceType.BEAM) "$name ($host:$port)" else name

    /** Identity used to dedupe a saved device against discovered ones. */
    val key: String
        get() = when (type) {
            DeviceType.BEAM -> "beam:$host:$port"
            DeviceType.DLNA -> "dlna:${udn ?: avTransportUrl}"
            DeviceType.CHROMECAST -> "cast:${routeId ?: name}"
        }
}

/** The currently connected receiver (process-wide). */
object CastSession {
    val device = MutableStateFlow<Device?>(null)

    fun select(ctx: Context, d: Device?) {
        val old = device.value
        device.value = d
        if (d != null) Store.get(ctx).lastDevice = d
        if (old?.type == DeviceType.CHROMECAST && (d == null || d.type != DeviceType.CHROMECAST)) {
            ChromecastSupport.disconnect()
        }
    }
}
