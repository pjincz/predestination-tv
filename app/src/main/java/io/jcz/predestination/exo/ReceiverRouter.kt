package io.jcz.predestination.exo

/** Called on the main thread. Keeps a cast request while the playback UI is not available. */
internal class ReceiverRouter(private val openPlayer: () -> Unit) : Renderer {
    private var player: Renderer? = null
    private var previous = java.lang.ref.WeakReference<Renderer>(null)
    private var uri = ""
    private var metadata = ""
    private var changed = false
    private var playPending = false
    private var volume: String? = null
    private var mute: String? = null

    fun attach(target: Renderer) {
        if (player === target) return
        player = target
        if (uri.isNotEmpty() && (changed || previous.get() !== target)) {
            target.action(AV, SoapAction("SetAVTransportURI", mapOf("InstanceID" to "0",
                "CurrentURI" to uri, "CurrentURIMetaData" to metadata)))
            changed = false
        }
        previous = java.lang.ref.WeakReference(target)
        volume?.let { target.action(RC, SoapAction("SetVolume", mapOf("InstanceID" to "0",
            "Channel" to "Master", "DesiredVolume" to it))) }
        mute?.let { target.action(RC, SoapAction("SetMute", mapOf("InstanceID" to "0",
            "Channel" to "Master", "DesiredMute" to it))) }
        if (playPending) {
            target.action(AV, SoapAction("Play", mapOf("InstanceID" to "0", "Speed" to "1")))
            playPending = false
        }
    }

    fun detach(target: Renderer) { if (player === target) player = null }

    override fun action(service: String, action: SoapAction): Map<String, String> {
        Protocol.validate(service, action)
        if (action.name == "SetVolume") {
            val value = action.args["DesiredVolume"]?.toIntOrNull()
            if (value == null || value !in 0..100) throw UpnpError(402, "Invalid volume")
            player?.action(service, action)
            volume = value.toString()
            return emptyMap()
        }
        if (action.name == "SetMute") {
            val value = action.args["DesiredMute"]
            if (value !in listOf("0", "1", "true", "false")) throw UpnpError(402, "Invalid mute")
            player?.action(service, action)
            mute = if (value in listOf("1", "true")) "1" else "0"
            return emptyMap()
        }
        if (action.name == "SetAVTransportURI") {
            val next = action.args.getValue("CurrentURI")
            if (next.isNotEmpty()) Protocol.mediaUri(next)
            player?.action(service, action)
            uri = next
            metadata = action.args.getValue("CurrentURIMetaData")
            changed = player == null
            playPending = false
            return emptyMap()
        }
        player?.let { return it.action(service, action) }
        return when (action.name) {
            "GetVolume" -> mapOf("CurrentVolume" to (volume ?: "100"))
            "GetMute" -> mapOf("CurrentMute" to (mute ?: "0"))
            "Play" -> {
                if (uri.isEmpty()) throw UpnpError(701, "No media present")
                if (action.args["Speed"] != "1") throw UpnpError(717, "Unsupported speed")
                playPending = true
                openPlayer()
                emptyMap()
            }
            "Pause", "Stop" -> { playPending = false; emptyMap() }
            "GetTransportInfo" -> mapOf("CurrentTransportState" to idleState(),
                "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
            "GetPositionInfo" -> mapOf("Track" to if (uri.isEmpty()) "0" else "1",
                "TrackDuration" to "00:00:00", "TrackMetaData" to metadata, "TrackURI" to uri,
                "RelTime" to "00:00:00", "AbsTime" to "00:00:00", "RelCount" to "2147483647",
                "AbsCount" to "2147483647")
            "GetMediaInfo" -> mapOf("NrTracks" to if (uri.isEmpty()) "0" else "1",
                "MediaDuration" to "00:00:00", "CurrentURI" to uri, "CurrentURIMetaData" to metadata,
                "NextURI" to "", "NextURIMetaData" to "", "PlayMedium" to "NETWORK",
                "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> mapOf("Actions" to if (uri.isEmpty()) "" else "Play,Stop")
            "GetDeviceCapabilities" -> mapOf("PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED",
                "RecQualityModes" to "NOT_IMPLEMENTED")
            "GetTransportSettings" -> mapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetProtocolInfo" -> mapOf("Source" to "", "Sink" to SINK)
            "GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to "0")
            "GetCurrentConnectionInfo" -> {
                if (action.args["ConnectionID"] != "0") throw UpnpError(706, "Invalid connection")
                mapOf("RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "http-get:*:*:*",
                    "PeerConnectionManager" to "", "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK")
            }
            else -> throw UpnpError(701, "Open player to use this action")
        }
    }

    private fun idleState() = if (uri.isEmpty()) "NO_MEDIA_PRESENT" else "STOPPED"

    override fun event(service: String): Map<String, String> {
        player?.let { return it.event(service) }
        return when (service) {
            CM -> mapOf("SourceProtocolInfo" to "", "SinkProtocolInfo" to SINK, "CurrentConnectionIDs" to "0")
            AV -> mapOf("LastChange" to "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\">" +
                "<InstanceID val=\"0\"><TransportState val=\"${idleState()}\"/>" +
                "<AVTransportURI val=\"${xml(uri)}\"/></InstanceID></Event>")
            else -> mapOf("LastChange" to "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"/>")
        }
    }

}
