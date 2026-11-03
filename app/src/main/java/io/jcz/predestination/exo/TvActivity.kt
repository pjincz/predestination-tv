package io.jcz.predestination.exo

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(UnstableApi::class)
internal class TvActivity : Activity(), Renderer {
    private lateinit var player: ExoPlayer
    private lateinit var video: PlayerView
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private var uri = ""
    private var metadata = ""
    private var volume = 100
    private var muted = false

    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        video = PlayerView(this)
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 22f
        }
        setContentView(FrameLayout(this).apply {
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1))
        })
    }

    override fun onStart() {
        super.onStart()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    Log.i("TvExoAudio", "Audio groups=${audioGroups.size}, total groups=${tracks.groups.size}")
                    audioGroups.forEachIndexed { groupIndex, group ->
                        for (trackIndex in 0 until group.length) {
                            val format = group.getTrackFormat(trackIndex)
                            Log.i("TvExoAudio", "Audio $groupIndex:$trackIndex " +
                                "mime=${format.sampleMimeType} codecs=${format.codecs} " +
                                "channels=${format.channelCount} sampleRate=${format.sampleRate} " +
                                "supported=${group.isTrackSupported(trackIndex)} " +
                                "supportCode=${group.getTrackSupport(trackIndex)} " +
                                "selected=${group.isTrackSelected(trackIndex)}")
                        }
                    }
                }

                override fun onEvents(player: Player, events: Player.Events) = updateStatus()
            })
        }
        video.player = player
        applyVolume()
        CastService.attach(this, this)
        refresh.run()
    }

    override fun onStop() {
        main.removeCallbacks(refresh)
        CastService.detach(this, this)
        video.player = null
        player.release()
        uri = ""
        metadata = ""
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP && uri.isNotEmpty()) {
            stopPlayback()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
        updateStatus()
    }

    private fun state() = when {
        uri.isEmpty() -> "NO_MEDIA_PRESENT"
        player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED -> "STOPPED"
        !player.playWhenReady -> "PAUSED_PLAYBACK"
        player.playbackState == Player.STATE_BUFFERING -> "TRANSITIONING"
        else -> "PLAYING"
    }

    private fun updateStatus() {
        val waiting = state() in listOf("STOPPED", "NO_MEDIA_PRESENT")
        status.visibility = if (waiting) View.VISIBLE else View.GONE
        video.useController = !waiting
        if (!waiting) return
        val error = player.playerError?.errorCodeName
        status.text = listOfNotNull("Predestination TV Exo · ${android.os.Build.MODEL}",
            CastService.status, error?.let { "播放失败 · $it" }).joinToString("\n")
    }

    private fun applyVolume() {
        player.volume = if (muted) 0f else volume / 100f
    }

    private fun duration() = player.duration.coerceAtLeast(0) / 1000.0

    private fun position() = player.currentPosition.coerceAtLeast(0) / 1000.0

    private fun transportStatus() = if (player.playerError == null) "OK" else "ERROR_OCCURRED"

    private fun transportActions(): String {
        if (uri.isEmpty()) return ""
        return buildList {
            add("Play")
            add("Stop")
            if (state() in listOf("PLAYING", "TRANSITIONING")) add("Pause")
            if (player.isCurrentMediaItemSeekable) add("Seek")
        }.joinToString(",")
    }

    override fun action(service: String, action: SoapAction): Map<String, String> {
        val args = action.args
        return when (action.name) {
            "SetAVTransportURI" -> {
                val next = args.getValue("CurrentURI")
                if (next.isNotEmpty()) Protocol.mediaUri(next)
                uri = next
                metadata = args.getValue("CurrentURIMetaData")
                stopPlayback()
                emptyMap()
            }
            "Play" -> {
                if (args["Speed"] != "1") throw UpnpError(717, "Unsupported speed")
                if (uri.isEmpty()) throw UpnpError(701, "No media present")
                if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                    player.setMediaItem(MediaItem.fromUri(Uri.parse(uri).normalizeScheme()))
                    player.prepare()
                }
                player.play()
                emptyMap()
            }
            "Pause" -> {
                if (state() in listOf("STOPPED", "NO_MEDIA_PRESENT"))
                    throw UpnpError(701, "Transition not available")
                player.pause()
                emptyMap()
            }
            "Stop" -> {
                stopPlayback()
                emptyMap()
            }
            "Seek" -> {
                if (args["Unit"] !in listOf("REL_TIME", "ABS_TIME") || !player.isCurrentMediaItemSeekable)
                    throw UpnpError(710, "Seek not available")
                val target = Protocol.seconds(args.getValue("Target"))
                if (duration() > 0 && target > duration()) throw UpnpError(711, "Illegal seek target")
                player.seekTo((target * 1000).toLong())
                emptyMap()
            }
            "Next", "Previous" -> throw UpnpError(701, "Transition not available")
            "GetTransportInfo" -> mapOf("CurrentTransportState" to state(),
                "CurrentTransportStatus" to transportStatus(), "CurrentSpeed" to "1")
            "GetPositionInfo" -> mapOf("Track" to if (uri.isEmpty()) "0" else "1",
                "TrackDuration" to Protocol.time(duration()), "TrackMetaData" to metadata,
                "TrackURI" to uri,
                "RelTime" to Protocol.time(position()), "AbsTime" to Protocol.time(position()),
                "RelCount" to "2147483647", "AbsCount" to "2147483647")
            "GetMediaInfo" -> mapOf("NrTracks" to if (uri.isEmpty()) "0" else "1",
                "MediaDuration" to Protocol.time(duration()), "CurrentURI" to uri,
                "CurrentURIMetaData" to metadata, "NextURI" to "", "NextURIMetaData" to "",
                "PlayMedium" to "NETWORK", "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED")
            "GetDeviceCapabilities" -> mapOf("PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED",
                "RecQualityModes" to "NOT_IMPLEMENTED")
            "GetTransportSettings" -> mapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> mapOf("Actions" to transportActions())
            "GetVolume" -> mapOf("CurrentVolume" to volume.toString())
            "SetVolume" -> {
                val value = args["DesiredVolume"]?.toIntOrNull()
                if (value == null || value !in 0..100) throw UpnpError(402, "Invalid volume")
                volume = value
                applyVolume()
                emptyMap()
            }
            "GetMute" -> mapOf("CurrentMute" to if (muted) "1" else "0")
            "SetMute" -> {
                val value = args["DesiredMute"]
                if (value !in listOf("0", "1", "true", "false")) throw UpnpError(402, "Invalid mute")
                muted = value in listOf("1", "true")
                applyVolume()
                emptyMap()
            }
            "GetProtocolInfo" -> mapOf("Source" to "", "Sink" to SINK)
            "GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to "0")
            "GetCurrentConnectionInfo" -> {
                if (args["ConnectionID"] != "0") throw UpnpError(706, "Invalid connection reference")
                mapOf("RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "http-get:*:*:*",
                    "PeerConnectionManager" to "", "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK")
            }
            else -> throw UpnpError(401, "Invalid Action")
        }
    }

    override fun event(service: String): Map<String, String> {
        if (service == CM) return mapOf("SourceProtocolInfo" to "", "SinkProtocolInfo" to SINK,
            "CurrentConnectionIDs" to "0")
        val values = if (service == AV) mapOf("TransportState" to state(), "TransportStatus" to transportStatus(),
            "CurrentTrackURI" to uri,
            "AVTransportURI" to uri, "CurrentTrackMetaData" to metadata,
            "CurrentTrackDuration" to Protocol.time(duration()), "CurrentTransportActions" to
                transportActions())
        else mapOf("Volume" to volume.toString(),
            "Mute" to if (muted) "1" else "0")
        val namespace = if (service == AV) "AVT" else "RCS"
        return mapOf("LastChange" to "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/$namespace/\">" +
            "<InstanceID val=\"0\">" + values.entries.joinToString("") {
                "<${it.key} ${if (service == RC) "channel=\"Master\" " else ""}val=\"${xml(it.value)}\"/>"
            } + "</InstanceID></Event>")
    }
}
