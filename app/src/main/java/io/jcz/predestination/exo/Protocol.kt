package io.jcz.predestination.exo

import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

internal const val AV = "urn:schemas-upnp-org:service:AVTransport:1"
internal const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
internal const val CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
internal const val DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1"
internal const val SINK = "http-get:*:video/*:*,http-get:*:audio/*:*," +
    "http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*," +
    "rtsp-rtp-udp:*:*:*"

internal fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

internal class UpnpError(val code: Int, message: String) : Exception(message)

internal data class SoapAction(val name: String, val args: Map<String, String>)

internal object Protocol {
    fun parse(body: ByteArray, service: String, header: String): SoapAction {
        // Reject DTDs before parsing, including on Android parsers without external-entity features.
        val text = body.toString(Charsets.UTF_8)
        require(!text.contains('\u0000') && !text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw IllegalArgumentException("External entity") }
        val root = builder.parse(ByteArrayInputStream(body)).documentElement
        require(root.localName == "Envelope" && root.namespaceURI ==
            "http://schemas.xmlsoap.org/soap/envelope/")
        val soapBody = root.getElementsByTagNameNS(root.namespaceURI, "Body").item(0) as Element
        val action = (0 until soapBody.childNodes.length).map { soapBody.childNodes.item(it) }
            .filterIsInstance<Element>().single()
        require(action.namespaceURI == service)
        require(header.trim('"') == "$service#${action.localName}")
        val elements = (0 until action.childNodes.length).map { action.childNodes.item(it) }
            .filterIsInstance<Element>()
        val args = elements.associate { it.localName to it.textContent }
        require(elements.size == args.size)
        return SoapAction(action.localName, args)
    }

    fun mediaUri(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in listOf("http", "https", "rtsp") ||
            uri.host.isNullOrEmpty() || uri.rawUserInfo != null || value.any { it.code < 32 }) {
            throw UpnpError(714, "Illegal MIME-type or media URL")
        }
        return value
    }

    fun seconds(value: String): Double {
        if (!Regex("[0-9]{1,6}:[0-5][0-9]:[0-5][0-9](\\.[0-9]+)?").matches(value))
            throw UpnpError(711, "Illegal seek target")
        val parts = value.split(':').map { it.toDouble() }
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    }

    fun time(value: Double): String {
        val seconds = if (value.isFinite()) value.toLong().coerceAtLeast(0) else 0
        return "%02d:%02d:%02d".format(java.util.Locale.ROOT,
            seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    fun envelope(content: String) = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>$content</s:Body></s:Envelope>"

    fun response(service: String, action: String, args: Map<String, String>) = envelope(
        "<u:${action}Response xmlns:u=\"$service\">" + args.entries.joinToString("") {
            "<${it.key}>${xml(it.value)}</${it.key}>"
        } + "</u:${action}Response>")

    fun fault(error: UpnpError) = envelope("<s:Fault><faultcode>s:Client</faultcode>" +
        "<faultstring>UPnPError</faultstring><detail>" +
        "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>${error.code}</errorCode>" +
        "<errorDescription>${xml(error.message.orEmpty())}</errorDescription></UPnPError>" +
        "</detail></s:Fault>")

    // name, direction, state variable; used for both discovery descriptions and action validation.
    val actions = mapOf(
        AV to mapOf(
            "SetAVTransportURI" to "InstanceID:i:Id CurrentURI:i:Uri CurrentURIMetaData:i:Metadata",
            "SetNextAVTransportURI" to "InstanceID:i:Id NextURI:i:Uri NextURIMetaData:i:Metadata",
            "Play" to "InstanceID:i:Id Speed:i:Speed",
            "Pause" to "InstanceID:i:Id", "Stop" to "InstanceID:i:Id",
            "Next" to "InstanceID:i:Id", "Previous" to "InstanceID:i:Id",
            "Seek" to "InstanceID:i:Id Unit:i:Unit Target:i:Time",
            "GetTransportInfo" to "InstanceID:i:Id CurrentTransportState:o:TransportState CurrentTransportStatus:o:Status CurrentSpeed:o:Speed",
            "GetPositionInfo" to "InstanceID:i:Id Track:o:Track TrackDuration:o:Time TrackMetaData:o:Metadata TrackURI:o:Uri RelTime:o:Time AbsTime:o:Time RelCount:o:Count AbsCount:o:Count",
            "GetMediaInfo" to "InstanceID:i:Id NrTracks:o:Track MediaDuration:o:Time CurrentURI:o:Uri CurrentURIMetaData:o:Metadata NextURI:o:Uri NextURIMetaData:o:Metadata PlayMedium:o:Medium RecordMedium:o:Medium WriteStatus:o:WriteStatus",
            "GetDeviceCapabilities" to "InstanceID:i:Id PlayMedia:o:Medium RecMedia:o:Medium RecQualityModes:o:Quality",
            "GetTransportSettings" to "InstanceID:i:Id PlayMode:o:PlayMode RecQualityMode:o:Quality",
            "GetCurrentTransportActions" to "InstanceID:i:Id Actions:o:Actions"
        ),
        RC to mapOf(
            "GetVolume" to "InstanceID:i:Id Channel:i:Channel CurrentVolume:o:Volume",
            "SetVolume" to "InstanceID:i:Id Channel:i:Channel DesiredVolume:i:Volume",
            "GetMute" to "InstanceID:i:Id Channel:i:Channel CurrentMute:o:Mute",
            "SetMute" to "InstanceID:i:Id Channel:i:Channel DesiredMute:i:Mute"
        ),
        CM to mapOf(
            "GetProtocolInfo" to "Source:o:Protocol Sink:o:Protocol",
            "GetCurrentConnectionIDs" to "ConnectionIDs:o:ConnectionIds",
            "GetCurrentConnectionInfo" to "ConnectionID:i:ConnectionId RcsID:o:ConnectionId AVTransportID:o:ConnectionId ProtocolInfo:o:Protocol PeerConnectionManager:o:Manager PeerConnectionID:o:ConnectionId Direction:o:Direction Status:o:ConnectionStatus"
        )
    )

    fun validate(service: String, action: SoapAction) {
        val spec = actions[service]?.get(action.name) ?: throw UpnpError(401, "Invalid Action")
        val required = spec.split(' ').map { it.split(':') }.filter { it[1] == "i" }
            .map { it[0] }.toSet()
        // Bilibili includes these vendor extensions alongside the standard transport arguments.
        val optional = if (service == AV && action.name == "SetAVTransportURI")
            setOf("SourceId", "ExtendData") else emptySet()
        if (!action.args.keys.containsAll(required) || !((required + optional).containsAll(action.args.keys)))
            throw UpnpError(402, "Invalid Args")
        if ("InstanceID" in required && action.args["InstanceID"] != "0")
            throw UpnpError(718, "Invalid InstanceID")
        if ("Channel" in required && action.args["Channel"] != "Master")
            throw UpnpError(402, "Invalid Channel")
    }

    fun scpd(service: String): String {
        val methods = actions.getValue(service)
        val variables = methods.values.flatMap { it.split(' ') }.map { it.split(':')[2] }.toSet()
        return "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion><actionList>" +
            methods.entries.joinToString("") { (name, args) ->
                "<action><name>$name</name><argumentList>" + args.split(' ').joinToString("") {
                    val (arg, direction, state) = it.split(':')
                    "<argument><name>$arg</name><direction>${if (direction == "i") "in" else "out"}</direction>" +
                        "<relatedStateVariable>A_ARG_TYPE_$state</relatedStateVariable></argument>"
                } + "</argumentList></action>"
            } + "</actionList><serviceStateTable>" + variables.joinToString("") {
                val type = when (it) {
                    "Id", "Track" -> "ui4"
                    "Volume" -> "ui2"
                    "Mute" -> "boolean"
                    "Count", "ConnectionId" -> "i4"
                    else -> "string"
                }
                "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_$it</name><dataType>$type</dataType>" +
                    (if (it == "Volume") "<allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>" else "") +
                    "</stateVariable>"
            } + (if (service == CM) {
                listOf("SourceProtocolInfo", "SinkProtocolInfo", "CurrentConnectionIDs").joinToString("") {
                    "<stateVariable sendEvents=\"yes\"><name>$it</name><dataType>string</dataType></stateVariable>"
                }
            } else "<stateVariable sendEvents=\"yes\"><name>LastChange</name><dataType>string</dataType></stateVariable>") +
            "</serviceStateTable></scpd>"
    }
}
