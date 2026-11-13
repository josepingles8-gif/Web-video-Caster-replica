package com.beamcast.sender.cast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import com.beamcast.sender.cast.Device
import com.beamcast.sender.cast.DeviceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** SSDP M-SEARCH discovery of UPnP MediaRenderers (AVTransport). */
class DlnaDiscovery(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lock: WifiManager.MulticastLock? = null
    private val seenLocations = HashSet<String>()
    val devices = MutableStateFlow<List<Device>>(emptyList())

    fun start() {
        if (lock != null) return
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wifi.createMulticastLock("beamcast-dlna").apply { setReferenceCounted(false); acquire() }
        scope.launch { runCatching { search() } }
    }

    fun stop() {
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        scope.cancel()
    }

    private suspend fun search() {
        DatagramSocket().use { socket ->
            socket.soTimeout = 400
            val data = DlnaProtocol.msearch().toByteArray(Charsets.US_ASCII)
            val group = InetAddress.getByName(DlnaProtocol.SSDP_HOST)
            val buf = ByteArray(2048)
            var round = 0
            var sentAt = 0L
            while (scope.isActive) {
                val now = System.currentTimeMillis()
                if (round < 3 && now - sentAt >= 1000) {
                    runCatching { socket.send(DatagramPacket(data, data.size, group, DlnaProtocol.SSDP_PORT)) }
                    round++
                    sentAt = now
                } else if (round >= 3 && now - sentAt >= 6000) break
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val resp = DlnaProtocol.parseSsdpResponse(String(p.data, 0, p.length, Charsets.UTF_8)) ?: continue
                    if (synchronized(seenLocations) { seenLocations.add(resp.location) }) {
                        scope.launch { runCatching { describe(resp.location) } }
                    }
                } catch (e: SocketTimeoutException) {
                    delay(10)
                }
            }
        }
    }

    private fun describe(location: String) {
        val conn = URL(location).openConnection() as HttpURLConnection
        val xml = try {
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val d = DlnaProtocol.parseDescription(xml, location) ?: return
        val host = URL(location).host
        val device = Device(
            name = d.friendlyName, host = host, port = URL(location).port.takeIf { it > 0 } ?: 80,
            type = DeviceType.DLNA, avTransportUrl = d.avTransportUrl, renderingControlUrl = d.renderingControlUrl, udn = d.udn,
        )
        devices.value = devices.value.filterNot { it.udn == d.udn } + device
    }
}
