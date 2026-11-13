package com.beamcast.sender

import com.beamcast.protocol.PlaybackState
import com.beamcast.sender.cast.dlna.DlnaProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaProtocolTest {
    @Test fun ssdpResponse() {
        val r = DlnaProtocol.parseSsdpResponse(
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.5:49152/desc.xml\r\nST: urn:schemas-upnp-org:service:AVTransport:1\r\nUSN: uuid:abc::urn:x\r\n\r\n",
        )
        assertEquals("http://192.168.1.5:49152/desc.xml", r?.location)
        assertEquals("uuid:abc::urn:x", r?.usn)
        assertNull(DlnaProtocol.parseSsdpResponse("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"))
    }

    private val desc = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><URLBase>http://10.0.0.2:8080/</URLBase>
<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>Living Room &amp; TV</friendlyName><UDN>uuid:1234</UDN>
<serviceList>
<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc/control</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>av/control</controlURL></service>
</serviceList></device></root>"""

    @Test fun description() {
        val d = DlnaProtocol.parseDescription(desc, "http://10.0.0.2:8080/desc.xml")
        assertNotNull(d)
        assertEquals("Living Room & TV", d!!.friendlyName)
        assertEquals("uuid:1234", d.udn)
        assertEquals("http://10.0.0.2:8080/av/control", d.avTransportUrl)
        assertEquals("http://10.0.0.2:8080/rc/control", d.renderingControlUrl)
    }

    @Test fun descriptionRelativeToLocationAndNoAv() {
        val noBase = desc.replace(Regex("<URLBase>.*</URLBase>"), "")
        assertEquals("http://h:1/x/av/control", DlnaProtocol.parseDescription(noBase, "http://h:1/x/desc.xml")?.avTransportUrl)
        assertNull(DlnaProtocol.parseDescription(desc.replace("AVTransport", "Other"), "http://h/d.xml"))
        assertNull(DlnaProtocol.parseDescription("not xml", "http://h/d.xml"))
    }

    @Test fun soapAndDidl() {
        val env = DlnaProtocol.soapEnvelope(DlnaProtocol.AVTRANSPORT, "Seek", listOf("InstanceID" to "0", "Target" to "a<b&c"))
        assertTrue(env.contains("<u:Seek xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(env.contains("<Target>a&lt;b&amp;c</Target>"))
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Seek\"", DlnaProtocol.soapActionHeader(DlnaProtocol.AVTRANSPORT, "Seek"))
        val didl = DlnaProtocol.didl("Tom & \"Jerry\"", "http://x/a?b=1&c=2", "video/mp4")
        assertTrue(didl.contains("<dc:title>Tom &amp; &quot;Jerry&quot;</dc:title>"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000\""))
        assertTrue(didl.contains(">http://x/a?b=1&amp;c=2</res>"))
    }

    @Test fun responseAndFault() {
        val ok = """<s:Envelope xmlns:s="x"><s:Body><u:GetPositionInfoResponse xmlns:u="y"><RelTime>0:01:02</RelTime><TrackDuration>0:10:00</TrackDuration></u:GetPositionInfoResponse></s:Body></s:Envelope>"""
        val m = DlnaProtocol.parseSoapResponse(ok)
        assertEquals("0:01:02", m["RelTime"])
        assertNull(DlnaProtocol.parseFault(ok))
        val fault = """<s:Envelope xmlns:s="x"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="z"><errorCode>701</errorCode><errorDescription>Transition not available</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
        assertEquals("Transition not available", DlnaProtocol.parseFault(fault))
    }

    @Test fun timeAndState() {
        assertEquals("0:00:00", DlnaProtocol.formatTime(-5))
        assertEquals("1:02:03", DlnaProtocol.formatTime(3_723_999))
        assertEquals(3_723_000L, DlnaProtocol.parseTime("1:02:03"))
        assertEquals(1_500L, DlnaProtocol.parseTime("0:00:01.500"))
        assertEquals(0L, DlnaProtocol.parseTime("NOT_IMPLEMENTED"))
        assertEquals(PlaybackState.PLAYING, DlnaProtocol.mapState("PLAYING"))
        assertEquals(PlaybackState.PAUSED, DlnaProtocol.mapState("PAUSED_PLAYBACK"))
        assertEquals(PlaybackState.BUFFERING, DlnaProtocol.mapState("TRANSITIONING"))
        assertEquals(PlaybackState.IDLE, DlnaProtocol.mapState("STOPPED"))
    }
}
