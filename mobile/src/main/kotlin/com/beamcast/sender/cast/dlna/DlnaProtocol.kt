package com.beamcast.sender.cast.dlna

import com.beamcast.protocol.PlaybackState
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/** Pure (Android-free) UPnP/DLNA helpers: SSDP, description XML, SOAP, DIDL, time, state. */
object DlnaProtocol {
    const val AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val SSDP_HOST = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val DLNA_FLAGS = "DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    data class SsdpResponse(val location: String, val usn: String?, val st: String?)
    data class Description(val udn: String, val friendlyName: String, val avTransportUrl: String, val renderingControlUrl: String?)

    fun msearch(st: String = AVTRANSPORT, mx: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_HOST:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: $mx\r\nST: $st\r\n\r\n"

    /** Parses an SSDP search response (or NOTIFY); null when it carries no LOCATION. */
    fun parseSsdpResponse(text: String): SsdpResponse? {
        val headers = HashMap<String, String>()
        text.lineSequence().drop(1).forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) { val k = line.substring(0, i).trim().lowercase(); if (k !in headers) headers[k] = line.substring(i + 1).trim() }
        }
        val loc = headers["location"]?.takeIf { it.isNotEmpty() } ?: return null
        return SsdpResponse(loc, headers["usn"], headers["st"] ?: headers["nt"])
    }

    private fun parseXml(xml: String): Element? = try {
        val f = DocumentBuilderFactory.newInstance()
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.isExpandEntityReferences = false }
        f.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml))).documentElement
    } catch (e: Exception) {
        null
    }

    private fun Node.local(): String = (nodeName ?: "").substringAfter(':')

    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.local() == name }

    private fun Element.text(name: String): String? = children(name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun Element.descendants(name: String, out: MutableList<Element> = ArrayList()): List<Element> {
        for (i in 0 until childNodes.length) {
            val c = childNodes.item(i) as? Element ?: continue
            if (c.local() == name) out.add(c)
            c.descendants(name, out)
        }
        return out
    }

    fun resolve(base: String, ref: String): String =
        runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref)

    /** Parses a device description; null when it is not an AVTransport renderer. */
    fun parseDescription(xml: String, location: String): Description? {
        val root = parseXml(xml) ?: return null
        val device = root.children("device").firstOrNull() ?: return null
        val base = root.text("URLBase") ?: location
        val services = device.descendants("service")
        fun control(typePrefix: String): String? = services
            .firstOrNull { it.text("serviceType")?.startsWith(typePrefix) == true }
            ?.text("controlURL")?.let { resolve(base, it) }
        val av = control("urn:schemas-upnp-org:service:AVTransport:") ?: return null
        val udn = device.text("UDN") ?: location
        val name = device.text("friendlyName") ?: udn
        return Description(udn, name, av, control("urn:schemas-upnp-org:service:RenderingControl:"))
    }

    fun xmlEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    fun soapEnvelope(serviceType: String, action: String, args: List<Pair<String, String>>): String {
        val body = args.joinToString("") { "<${it.first}>${xmlEscape(it.second)}</${it.first}>" }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$serviceType\">$body</u:$action></s:Body></s:Envelope>"
    }

    fun soapActionHeader(serviceType: String, action: String) = "\"$serviceType#$action\""

    fun didl(title: String, url: String, mime: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${xmlEscape(title)}</dc:title>" +
            "<upnp:class>${upnpClass(mime)}</upnp:class>" +
            "<res protocolInfo=\"${xmlEscape("http-get:*:$mime:$DLNA_FLAGS")}\">${xmlEscape(url)}</res></item></DIDL-Lite>"

    private fun upnpClass(mime: String) = when {
        mime.startsWith("audio/") -> "object.item.audioItem.musicTrack"
        mime.startsWith("image/") -> "object.item.imageItem.photo"
        else -> "object.item.videoItem"
    }

    /** Leaf elements of a SOAP response, by local name (e.g. RelTime, CurrentTransportState). */
    fun parseSoapResponse(xml: String): Map<String, String> {
        val root = parseXml(xml) ?: return emptyMap()
        val out = HashMap<String, String>()
        fun walk(e: Element) {
            val kids = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }
            if (kids.isEmpty()) out[e.local()] = e.textContent.orEmpty() else kids.forEach(::walk)
        }
        walk(root)
        return out
    }

    /** errorDescription (or code) of a UPnP SOAP fault; null when the XML is not a fault. */
    fun parseFault(xml: String): String? {
        val m = parseSoapResponse(xml)
        if (!m.containsKey("faultcode") && !m.containsKey("errorCode") && !m.containsKey("errorDescription")) return null
        return m["errorDescription"]?.takeIf { it.isNotBlank() }
            ?: m["errorCode"]?.let { "UPnP error $it" }
            ?: m["faultstring"] ?: "SOAP fault"
    }

    fun formatTime(ms: Long): String {
        val s = (ms.coerceAtLeast(0)) / 1000
        return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }

    /** Parses H:MM:SS[.fff]; unknown values (NOT_IMPLEMENTED, empty) give 0. */
    fun parseTime(t: String?): Long {
        val s = t?.trim()?.removePrefix("+") ?: return 0
        val parts = s.split(':')
        if (parts.size != 3) return 0
        val h = parts[0].toLongOrNull() ?: return 0
        val m = parts[1].toLongOrNull() ?: return 0
        val sec = parts[2].toDoubleOrNull() ?: return 0
        return ((h * 3600 + m * 60) * 1000 + Math.round(sec * 1000))
    }

    fun mapState(transportState: String?): String = when (transportState?.uppercase()) {
        "PLAYING" -> PlaybackState.PLAYING
        "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> PlaybackState.PAUSED
        "TRANSITIONING" -> PlaybackState.BUFFERING
        else -> PlaybackState.IDLE
    }
}
