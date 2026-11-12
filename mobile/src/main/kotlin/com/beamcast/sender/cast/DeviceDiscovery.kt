package com.beamcast.sender.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.beamcast.protocol.BeamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address

/** NSD discovery of BeamCast receivers; each resolved service is verified with GET /info. */
@Suppress("DEPRECATION")
class DeviceDiscovery(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null

    val devices = MutableStateFlow<List<Device>>(emptyList())

    fun start() {
        if (listener != null) return
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wifi.createMulticastLock("beamcast").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                devices.value = devices.value.filterNot { it.name == info.serviceName }
            }
        }
        listener = l
        try {
            nsd.discoverServices(BeamProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            listener = null
        }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        scope.cancel()
    }

    private fun enqueue(info: NsdServiceInfo) {
        synchronized(queue) { queue.addLast(info) }
        next()
    }

    private fun next() {
        val info = synchronized(queue) {
            if (resolving) return
            val n = queue.removeFirstOrNull() ?: return
            resolving = true
            n
        }
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done()
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val addr = serviceInfo.host as? Inet4Address
                    if (addr != null) {
                        val d = Device(serviceInfo.serviceName, addr.hostAddress ?: "", serviceInfo.port)
                        scope.launch {
                            runCatching { CastClient.info(d) }.onSuccess { i ->
                                val named = d.copy(name = i.name)
                                devices.value = devices.value.filterNot { it.host == d.host && it.port == d.port } + named
                            }
                        }
                    }
                    done()
                }
            })
        } catch (e: Exception) {
            done()
        }
    }

    private fun done() {
        synchronized(queue) { resolving = false }
        next()
    }
}
