package com.beamcast.receiver.discovery

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtil {
    /** Best LAN IPv4 address of this device (Wi-Fi/Ethernet preferred), or null when offline. */
    fun localIpv4(): String? {
        val candidates = mutableListOf<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        candidates += ni.name to (a.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Exception) { /* no interfaces */ }
        return (candidates.firstOrNull { (n, _) -> n.startsWith("wlan") || n.startsWith("eth") } ?: candidates.firstOrNull())?.second
    }
}
