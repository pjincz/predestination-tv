package io.jcz.predestination.exo

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun acceptsBilibiliTransportExtensionsWithoutRelaxingRequiredArguments() {
        val body = Protocol.envelope("<u:SetAVTransportURI xmlns:u=\"$AV\"><InstanceID>0</InstanceID>" +
            "<CurrentURI>https://example.org/video.mp4</CurrentURI><CurrentURIMetaData/>" +
            "<SourceId>bilibili</SourceId><ExtendData>{}</ExtendData></u:SetAVTransportURI>")
        val action = Protocol.parse(body.toByteArray(), AV, "\"$AV#SetAVTransportURI\"")
        Protocol.validate(AV, action)
        ReceiverRouter {}.action(AV, action)
        assertEquals(402, assertThrows(UpnpError::class.java) {
            Protocol.validate(AV, action.copy(args = action.args - "CurrentURI"))
        }.code)
        assertEquals(402, assertThrows(UpnpError::class.java) {
            Protocol.validate(AV, action.copy(args = action.args + ("Unexpected" to "value")))
        }.code)
        assertEquals(718, assertThrows(UpnpError::class.java) {
            Protocol.validate(AV, action.copy(args = action.args + ("InstanceID" to "1")))
        }.code)
    }

    @Test fun parsesEscapedUriAndMetadata() {
        val body = Protocol.envelope("<u:SetAVTransportURI xmlns:u=\"$AV\"><InstanceID>0</InstanceID>" +
            "<CurrentURI>http://192.168.1.2/a?x=1&amp;y=2</CurrentURI>" +
            "<CurrentURIMetaData>&lt;DIDL-Lite/&gt;</CurrentURIMetaData></u:SetAVTransportURI>")
        val action = Protocol.parse(body.toByteArray(), AV, "\"$AV#SetAVTransportURI\"")
        Protocol.validate(AV, action)
        assertEquals("http://192.168.1.2/a?x=1&y=2", action.args["CurrentURI"])
        assertEquals("<DIDL-Lite/>", action.args["CurrentURIMetaData"])
    }

    @Test fun rejectsEntitiesAndMismatchedActions() {
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.parse("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><x/>".toByteArray(), AV, "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.parse(Protocol.envelope("<u:Play xmlns:u=\"$AV\"/>").toByteArray(), AV, "$AV#Stop")
        }
    }

    @Test fun validatesInstancesArgumentsAndChannels() {
        assertEquals(718, assertThrows(UpnpError::class.java) {
            Protocol.validate(AV, SoapAction("Stop", mapOf("InstanceID" to "1")))
        }.code)
        assertEquals(402, assertThrows(UpnpError::class.java) {
            Protocol.validate(RC, SoapAction("GetVolume", mapOf("InstanceID" to "0", "Channel" to "LF")))
        }.code)
        assertEquals(401, assertThrows(UpnpError::class.java) {
            Protocol.validate(AV, SoapAction("Execute", emptyMap()))
        }.code)
        assertThrows(UpnpError::class.java) { Protocol.validate(AV, SoapAction("Play", emptyMap())) }
    }

    @Test fun restrictsNetworkMediaAndSeekValues() {
        assertEquals("https://example.org/video.mkv", Protocol.mediaUri("https://example.org/video.mkv"))
        listOf("rtsp://192.168.1.2:8554/live", "RTSP://camera.local/live?channel=1",
            "rtsp://[::1]:8554/live").forEach { assertEquals(it, Protocol.mediaUri(it)) }
        listOf("file:///data/private", "content://secret", "http://user:pass@host/a", "ftp://host/a",
            "rtsp:///live", "rtsp://user:pass@host/live", "rtsps://host/live",
            "http://host/a\r\nHeader: value").forEach {
            assertThrows(UpnpError::class.java) { Protocol.mediaUri(it) }
        }
        assertEquals(3723.5, Protocol.seconds("01:02:03.5"), 0.001)
        assertEquals("01:02:03", Protocol.time(3723.5))
        listOf("-01:00:00", "01:60:00", "NaN", "00:00:99").forEach {
            assertThrows(UpnpError::class.java) { Protocol.seconds(it) }
        }
    }

    @Test fun descriptionsAndResponsesAreWellFormedXml() {
        val builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        listOf(AV, RC, CM).forEach { service ->
            val document = builder.parse(Protocol.scpd(service).byteInputStream())
            assertEquals(Protocol.actions.getValue(service).size,
                document.getElementsByTagName("action").length)
        }
        builder.parse(Protocol.response(AV, "GetMediaInfo", mapOf("CurrentURI" to "http://host/?a=1&b=2")).byteInputStream())
        builder.parse(Protocol.fault(UpnpError(402, "Invalid <arg> & value")).byteInputStream())
    }
}

