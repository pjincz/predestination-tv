package io.jcz.predestination.exo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.FileProvider
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.renderer.NextTextRenderer

@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
internal class TvActivity : Activity(), Renderer {
    private lateinit var player: ExoPlayer
    private lateinit var video: PlayerView
    private lateinit var musicBackground: MusicBackgroundView
    private lateinit var osd: TvOsd
    private lateinit var status: TextView
    private lateinit var loadControl: RebufferLoadControl
    private lateinit var bufferingPanel: LinearLayout
    private lateinit var bufferingText: TextView
    private lateinit var bufferingProgress: ProgressBar
    private var updateDialog: AlertDialog? = null
    private var adminDialog: AlertDialog? = null
    private var shownApproval: AdminPage.Approval? = null
    private val apkUpdate by lazy { ApkUpdate(applicationContext) }
    private val main = Handler(Looper.getMainLooper())
    private var uri = ""
    private var metadata = ""
    private var volume = 100
    private var muted = false
    private val audioGain = AudioGain()
    private var gainDialog: AlertDialog? = null
    private val backExit = BackExit()
    private var exitHint: Toast? = null
    private val playlistExecutor = java.util.concurrent.Executors.newCachedThreadPool()
    private var playlistTask: java.util.concurrent.Future<*>? = null
    private var playlistGeneration = 0
    private var loadingPlaylist = false
    private var playlistError: String? = null

    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBack() }
        }
        val installedAt = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        if (apkUpdate.pending.lastModified() < installedAt) apkUpdate.pending.delete()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        video = PlayerView(this).apply { useController = false; isFocusable = false }
        musicBackground = MusicBackgroundView(this).apply {
            visibility = View.GONE
        }
        osd = TvOsd(this).apply {
            toggle = { togglePlayback() }
            seek = { seconds -> seekFromOsd(position() + seconds) }
            seekFraction = { fraction -> seekFromOsd(duration() * fraction) }
            addButton("播放 / 暂停") { togglePlayback() }
            addButton("上一首") { skipFromOsd(false) }
            addButton("下一首") { skipFromOsd(true) }
            addButton("停止") { stopPlayback() }
            addButton("字幕") { showTracks(C.TRACK_TYPE_TEXT) }
            addButton("音轨") { showTracks(C.TRACK_TYPE_AUDIO) }
            addButton("音量增益") { showGainDialog() }
        }
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 22f
        }
        bufferingText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 22f
        }
        bufferingProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bufferingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(0xcc202020.toInt())
            visibility = View.GONE
            addView(bufferingText)
            addView(bufferingProgress, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(TvRemoteFrame(this) { event ->
            ::player.isInitialized && !isWaiting() && osd.handleKey(event)
        }.apply {
            setOnClickListener {
                if (::player.isInitialized && !isWaiting()) {
                    if (osd.visibility == View.VISIBLE) osd.hide() else osd.show()
                }
            }
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(musicBackground, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1))
            addView(bufferingPanel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(osd, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })
        osd.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateMusicBounds() }
    }

    override fun onStart() {
        super.onStart()
        setAudioGain(0)
        val renderers = object : NextRenderersFactory(this@TvActivity) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setAudioProcessors(arrayOf(audioGain))
                    .build()
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val subtitleRenderers = RenderersFactory { handler, videoEvents, audioEvents, textOutput, metadataOutput ->
            renderers.createRenderers(handler, videoEvents, audioEvents, textOutput, metadataOutput)
                .also { list ->
                    list.filterIsInstance<NextTextRenderer>().forEach {
                        @Suppress("DEPRECATION")
                        it.delegate.experimentalSetLegacyDecodingEnabled(true)
                    }
                }
        }
        val parsers = DefaultSubtitleParserFactory()
        val subtitleParsers = object : SubtitleParser.Factory by parsers {
                // Leave PGS compressed until its track is selected for rendering.
                override fun supportsFormat(format: Format): Boolean =
                    format.sampleMimeType != MimeTypes.APPLICATION_PGS && parsers.supportsFormat(format)
            }
        loadControl = RebufferLoadControl(BufferSettings(applicationContext))
        player = ExoPlayer.Builder(this).setRenderersFactory(subtitleRenderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setSubtitleParserFactory(subtitleParsers))
            .setLoadControl(loadControl).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            addAnalyticsListener(object : AnalyticsListener {
                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long
                ) {
                    AppLog.record("TvExoPlayback", "Dropped video frames=$droppedFrames over ${elapsedMs}ms")
                }
            })
            addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    gainDialog?.dismiss()
                    gainDialog = null
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    AppLog.record("TvExoAudio", "Audio groups=${audioGroups.size}, total groups=${tracks.groups.size}")
                    audioGroups.forEachIndexed { groupIndex, group ->
                        for (trackIndex in 0 until group.length) {
                            val format = group.getTrackFormat(trackIndex)
                            AppLog.record("TvExoAudio", "Audio $groupIndex:$trackIndex " +
                                "mime=${format.sampleMimeType} codecs=${format.codecs} " +
                                "channels=${format.channelCount} sampleRate=${format.sampleRate} " +
                                "supported=${group.isTrackSupported(trackIndex)} " +
                                "supportCode=${group.getTrackSupport(trackIndex)} " +
                                "selected=${group.isTrackSelected(trackIndex)}")
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val label = when (playbackState) {
                        Player.STATE_BUFFERING -> "buffering"
                        Player.STATE_READY -> "ready"
                        Player.STATE_ENDED -> "ended"
                        else -> "idle"
                    }
                    AppLog.record("TvExoPlayback",
                        "$label position=${player.currentPosition}ms buffered=${player.totalBufferedDuration}ms")
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    AppLog.record("TvExoPlayback", "Playback error: ${error.errorCodeName}")
                    generateSequence<Throwable>(error) { it.cause }.take(8).forEach { cause ->
                        AppLog.record("TvExoPlayback", "${cause.javaClass.simpleName}: ${cause.message}")
                        cause.stackTrace.take(6).forEach { frame ->
                            AppLog.record("TvExoPlayback", "  at $frame")
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
        musicBackground.setPlaying(false)
        cancelPlaylist()
        backExit.reset()
        exitHint?.cancel()
        osd.pinned = false
        osd.hide()
        gainDialog?.dismiss()
        gainDialog = null
        main.removeCallbacks(refresh)
        adminDialog?.dismiss()
        adminDialog = null
        shownApproval = null
        updateDialog?.dismiss()
        updateDialog = null
        CastService.detach(this, this)
        video.player = null
        player.release()
        uri = ""
        metadata = ""
        super.onStop()
    }

    override fun onDestroy() {
        playlistExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (event.repeatCount == 0) togglePlayback()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) player.pause()
            else if (uri.isNotEmpty()) action(AV, SoapAction("Play", mapOf("Speed" to "1")))
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP && uri.isNotEmpty()) {
            stopPlayback()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Legacy Android back navigation; newer versions use OnBackInvokedDispatcher.")
    @android.annotation.SuppressLint("GestureBackNavigation") // API 33+ callback registered in onCreate.
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        val topLevel = osd.pinned || osd.visibility != View.VISIBLE
        val shouldExit = backExit.onBack(topLevel, android.os.SystemClock.elapsedRealtime())
        exitHint?.cancel()
        if (!topLevel) {
            osd.hide()
        } else if (shouldExit) {
            finish()
        } else {
            exitHint = Toast.makeText(this, "双击返回键退出app", Toast.LENGTH_SHORT).also { it.show() }
        }
    }

    @android.annotation.SuppressLint("GestureBackNavigation") // API 33+ callback is also registered below.
    private fun trackDialogBack(dialog: AlertDialog) {
        fun cancelFromBack() {
            backExit.onBack(false, android.os.SystemClock.elapsedRealtime())
            dialog.cancel()
        }
        dialog.setOnKeyListener { _, key, event ->
            if (key != KeyEvent.KEYCODE_BACK) false
            else {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) cancelFromBack()
                true
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { cancelFromBack() }
        }
    }

    private fun togglePlayback() {
        if (uri.isEmpty()) return
        if (player.playWhenReady && player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED) player.pause()
        else action(AV, SoapAction("Play", mapOf("Speed" to "1")))
    }

    private fun seekFromOsd(seconds: Double) {
        if (player.isCurrentMediaItemSeekable) {
            player.seekTo((seconds.coerceIn(0.0, duration()) * 1000).toLong())
        }
    }

    private fun showTracks(type: Int) {
        val choices = player.currentTracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
        }
        val labels = listOf(if (type == C.TRACK_TYPE_TEXT) "关闭字幕" else "自动选择") +
            choices.mapIndexed { index, (group, track) ->
                val format = group.getTrackFormat(track)
                listOfNotNull("${index + 1}", format.label, format.language, format.sampleMimeType)
                    .joinToString(" · ")
            }
        AlertDialog.Builder(this)
            .setTitle(if (type == C.TRACK_TYPE_TEXT) "字幕" else "音轨")
            .setItems(labels.toTypedArray()) { _, index ->
                val parameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                    .setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT && index == 0)
                if (index > 0) {
                    val (group, track) = choices[index - 1]
                    parameters.addOverride(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, track))
                }
                player.trackSelectionParameters = parameters.build()
            }
            .setNegativeButton("取消", null)
            .show().also(::trackDialogBack)
    }

    private fun setAudioGain(db: Int) {
        audioGain.setGain(db)
        AppLog.record("TvExoAudio", "Audio gain=${db}dB")
    }

    private fun showGainDialog() {
        if (gainDialog?.isShowing == true) return
        gainDialog = audioControls(this, audioGain, ::setAudioGain).also {
            it.show()
            trackDialogBack(it)
        }
    }

    private fun skipTrack(next: Boolean): Boolean {
        if (next && player.hasNextMediaItem()) player.seekToNextMediaItem()
        else if (!next && player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
        else return false
        return true
    }

    private fun skipFromOsd(next: Boolean) {
        if (!skipTrack(next)) {
            Toast.makeText(this, if (next) "没有下一首" else "没有上一首", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        cancelPlaylist()
        player.stop()
        player.clearMediaItems()
        updateStatus()
    }

    private fun state() = when {
        uri.isEmpty() -> "NO_MEDIA_PRESENT"
        loadingPlaylist -> "TRANSITIONING"
        player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED -> "STOPPED"
        !player.playWhenReady -> "PAUSED_PLAYBACK"
        player.playbackState == Player.STATE_BUFFERING -> "TRANSITIONING"
        else -> "PLAYING"
    }

    private fun confirmUpdate() {
        if (!apkUpdate.pending.isFile || !hasWindowFocus() ||
            updateDialog?.isShowing == true || adminDialog?.isShowing == true) return
        updateDialog = AlertDialog.Builder(this)
            .setMessage("打开系统安装器更新 Predestination TV Exo？")
            .setPositiveButton("继续") { _, _ ->
                val apk = FileProvider.getUriForFile(this, "$packageName.updates", apkUpdate.pending)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apk, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivityForResult(intent, 42)
                    AppLog.record("TvExoUpdate", "System installer opened")
                } catch (_: android.content.ActivityNotFoundException) {
                    apkUpdate.pending.delete()
                    Toast.makeText(this, "系统安装器不可用", Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    apkUpdate.pending.delete()
                    Toast.makeText(this, "系统拒绝安装，请检查此来源的安装权限", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消") { _, _ -> apkUpdate.pending.delete() }
            .setOnCancelListener { apkUpdate.pending.delete() }
            .show()
            .also {
                trackDialogBack(it)
                it.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
            }
    }

    @Deprecated("Uses the platform installer activity result to release the staged APK.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42) {
            val apk = FileProvider.getUriForFile(this, "$packageName.updates", apkUpdate.pending)
            revokeUriPermission(apk, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            apkUpdate.pending.delete()
        }
    }

    private fun updateAdminApproval() {
        val page = CastService.adminPage()
        val approval = page?.awaitingApproval()
        if (approval == shownApproval) return
        adminDialog?.dismiss()
        adminDialog = null
        shownApproval = approval
        if (approval == null) return

        adminDialog = AlertDialog.Builder(this)
            .setTitle("允许访问管理员页面？")
            .setMessage("请求来自 ${approval.peer}。请核对浏览器配对码 ${approval.code}。")
            .setPositiveButton("允许") { _, _ -> page.resolve(approval, true) }
            .setNegativeButton("拒绝") { _, _ -> page.resolve(approval, false) }
            .setOnCancelListener { page.resolve(approval, false) }
            .show()
            .also {
                trackDialogBack(it)
                it.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
            }
    }

    private fun updateBufferingProgress() {
        if (loadingPlaylist) {
            bufferingPanel.visibility = View.VISIBLE
            bufferingText.text = "正在读取歌曲列表"
            bufferingProgress.isIndeterminate = true
            return
        }
        bufferingProgress.isIndeterminate = false
        val buffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady
        bufferingPanel.visibility = if (buffering) View.VISIBLE else View.GONE
        if (!buffering) return
        val target = loadControl.targetMs
        bufferingProgress.isIndeterminate = target == 0L
        if (target == 0L) {
            bufferingText.text = "正在缓冲"
            return
        }
        val buffered = player.totalBufferedDuration.coerceIn(0, target)
        bufferingProgress.max = target.toInt()
        bufferingProgress.progress = buffered.toInt()
        bufferingText.text = "正在缓冲 · %.1f / %.0f 秒".format(buffered / 1000.0, target / 1000.0)
    }

    private fun updateMusicBounds() {
        val params = musicBackground.layoutParams as FrameLayout.LayoutParams
        val bottom = if (osd.pinned) osd.height else 0
        if (params.bottomMargin != bottom) {
            params.bottomMargin = bottom
            musicBackground.layoutParams = params
        }
    }

    private fun isWaiting() = state() in listOf("STOPPED", "NO_MEDIA_PRESENT")

    private fun updateStatus() {
        updateAdminApproval()
        confirmUpdate()
        updateBufferingProgress()
        val waiting = isWaiting()
        val tracks = player.currentTracks
        val audioOnly = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO) &&
            !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
        osd.pinned = !waiting && audioOnly
        updateMusicBounds()
        musicBackground.visibility = if (!waiting && audioOnly) View.VISIBLE else View.GONE
        musicBackground.setPlaying(!waiting && audioOnly && player.isPlaying)
        if (waiting) osd.hide()
        status.visibility = if (waiting) View.VISIBLE else View.GONE
        osd.title.text = TvOsd.displayTitle(player.mediaMetadata.title?.toString(), currentTrackUri())
        osd.source.text = TvOsd.displaySource(currentTrackUri())
        osd.source.visibility = if (uri.isEmpty()) View.GONE else View.VISIBLE
        osd.playbackState.text = when {
            waiting -> "等待播放"
            player.playbackState == Player.STATE_BUFFERING -> "正在缓冲"
            !player.playWhenReady -> "已暂停"
            else -> "正在播放"
        }
        osd.updateProgress(position(), duration(), player.isCurrentMediaItemSeekable)
        if (!waiting) return
        val error = playlistError ?: player.playerError?.errorCodeName
        status.text = listOfNotNull("Predestination TV Exo · ${android.os.Build.MODEL}",
            CastService.status, error?.let { "播放失败 · $it" }).joinToString("\n")
    }

    private fun applyVolume() {
        player.volume = if (muted) 0f else volume / 100f
    }

    private fun duration() = player.duration.coerceAtLeast(0) / 1000.0

    private fun position() = player.currentPosition.coerceAtLeast(0) / 1000.0

    private fun transportStatus() =
        if (player.playerError == null && playlistError == null) "OK" else "ERROR_OCCURRED"

    private fun currentTrackUri() = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: uri

    private fun cancelPlaylist() {
        playlistGeneration++
        playlistTask?.cancel(true)
        playlistTask = null
        loadingPlaylist = false
        playlistError = null
    }

    private fun startPlayback() {
        if (loadingPlaylist) return
        if (!M3uPlaylist.matches(uri)) {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(uri).normalizeScheme()))
            player.prepare()
            player.play()
            return
        }
        cancelPlaylist()
        loadingPlaylist = true
        val generation = playlistGeneration
        val source = uri
        updateStatus()
        playlistTask = playlistExecutor.submit {
            val result = runCatching { M3uPlaylist.load(source) }
            main.post {
                if (generation != playlistGeneration) return@post
                loadingPlaylist = false
                playlistTask = null
                result.onSuccess { list ->
                    val items = list.entries.map { entry ->
                        MediaItem.Builder().setUri(entry.url)
                            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(entry.title).build())
                            .apply { if (list.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                            .build()
                    }
                    AppLog.record("TvExoCast", "Playlist loaded: tracks=${items.size} hls=${list.hls}")
                    player.setMediaItems(items)
                    player.prepare()
                    player.play()
                }.onFailure { error ->
                    // Avoid logging exception URLs: playlist links can contain access tokens.
                    playlistError = "无法读取歌曲列表"
                    AppLog.record("TvExoCast", "Playlist load failed: ${error.javaClass.simpleName}")
                }
                updateStatus()
            }
        }
    }

    private fun transportActions(): String {
        if (uri.isEmpty()) return ""
        return buildList {
            add("Play")
            add("Stop")
            if (state() in listOf("PLAYING", "TRANSITIONING")) add("Pause")
            if (player.isCurrentMediaItemSeekable) add("Seek")
            if (player.hasNextMediaItem()) add("Next")
            if (player.hasPreviousMediaItem()) add("Previous")
        }.joinToString(",")
    }

    override fun action(service: String, action: SoapAction): Map<String, String> {
        val args = action.args
        return when (action.name) {
            "SetAVTransportURI" -> {
                val next = args.getValue("CurrentURI")
                if (next.isNotEmpty()) Protocol.mediaUri(next)
                gainDialog?.dismiss()
                gainDialog = null
                audioGain.resetChannels()
                audioGain.nightMode = false
                setAudioGain(0)
                uri = next
                metadata = args.getValue("CurrentURIMetaData")
                stopPlayback()
                emptyMap()
            }
            "Play" -> {
                if (args["Speed"] != "1") throw UpnpError(717, "Unsupported speed")
                if (uri.isEmpty()) throw UpnpError(701, "No media present")
                if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                    startPlayback()
                } else player.play()
                emptyMap()
            }
            "Pause" -> {
                if (loadingPlaylist) {
                    cancelPlaylist()
                    updateStatus()
                    return emptyMap()
                }
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
            "Next", "Previous" -> {
                if (!skipTrack(action.name == "Next")) throw UpnpError(701, "Transition not available")
                emptyMap()
            }
            "GetTransportInfo" -> mapOf("CurrentTransportState" to state(),
                "CurrentTransportStatus" to transportStatus(), "CurrentSpeed" to "1")
            "GetPositionInfo" -> mapOf("Track" to if (uri.isEmpty()) "0" else (player.currentMediaItemIndex + 1).toString(),
                "TrackDuration" to Protocol.time(duration()), "TrackMetaData" to metadata,
                "TrackURI" to currentTrackUri(),
                "RelTime" to Protocol.time(position()), "AbsTime" to Protocol.time(position()),
                "RelCount" to "2147483647", "AbsCount" to "2147483647")
            "GetMediaInfo" -> mapOf("NrTracks" to if (uri.isEmpty()) "0" else player.mediaItemCount.coerceAtLeast(1).toString(),
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
            "CurrentTrackURI" to currentTrackUri(),
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
