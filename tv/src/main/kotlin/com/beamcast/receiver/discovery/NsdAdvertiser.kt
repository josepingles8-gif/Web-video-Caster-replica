package com.beamcast.receiver.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.beamcast.protocol.BeamProtocol

/**
 * Registers the receiver over mDNS. Re-registers when the network changes or the name is edited,
 * retries on failure, and holds a multicast lock while active. Main thread only.
 */
class NsdAdvertiser(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.RegistrationListener? = null
    private var name = ""
    private var port = 0
    private var active = false

    private val refresh = Runnable { refreshNetwork() }
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleRefresh()
        override fun onLost(network: Network) = scheduleRefresh()
    }

    fun start(deviceName: String, port: Int) {
        this.name = deviceName
        this.port = port
        active = true
        lock = wifi.createMulticastLock("beamcast-mdns").apply { setReferenceCounted(false); acquire() }
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching { cm.registerNetworkCallback(req, netCallback) }
        refreshNetwork()
    }

    fun rename(deviceName: String) {
        name = deviceName
        if (active) refreshNetwork()
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        runCatching { cm.unregisterNetworkCallback(netCallback) }
        unregister()
        lock?.takeIf { it.isHeld }?.release()
        lock = null
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 800)
    }

    private fun refreshNetwork() {
        if (!active) return
        val ip = NetworkUtil.localIpv4()
        ReceiverState.update { copy(ip = ip, name = name, port = port) }
        unregister()
        if (ip == null) {
            ReceiverState.update { copy(visibility = Visibility.NO_NETWORK) }
        } else {
            ReceiverState.update { copy(visibility = Visibility.STARTING) }
            register()
        }
    }

    private fun register() {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = BeamProtocol.SERVICE_TYPE
            port = this@NsdAdvertiser.port
            setAttribute("v", BeamProtocol.VERSION.toString())
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                handler.post {
                    if (listener !== this) return@post
                    // NSD resolves name conflicts itself by appending " (2)"; i.serviceName is the final name.
                    Log.i(TAG, "Registered as ${i.serviceName}")
                    ReceiverState.update { copy(visibility = Visibility.VISIBLE) }
                }
            }
            override fun onRegistrationFailed(i: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registration failed: $errorCode")
                handler.post {
                    if (listener !== this) return@post
                    listener = null
                    ReceiverState.update { copy(visibility = Visibility.FAILED) }
                    handler.postDelayed({ if (active && listener == null) refreshNetwork() }, 5000)
                }
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, errorCode: Int) {}
        }
        listener = l
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "registerService threw", e)
            listener = null
            ReceiverState.update { copy(visibility = Visibility.FAILED) }
            handler.postDelayed({ if (active && listener == null) refreshNetwork() }, 5000)
        }
    }

    private fun unregister() {
        val l = listener ?: return
        listener = null
        try { nsd.unregisterService(l) } catch (_: Exception) { /* not registered yet */ }
    }

    private companion object { const val TAG = "NsdAdvertiser" }
}
