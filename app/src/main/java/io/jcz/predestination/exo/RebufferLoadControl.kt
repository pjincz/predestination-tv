package io.jcz.predestination.exo

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId

@UnstableApi
internal class RebufferLoadControl(private val settings: BufferSettings) : DefaultLoadControl() {
    private val period = Timeline.Period()
    private val window = Timeline.Window()
    private var lastRebuffer = C.TIME_UNSET
    private var limits = settings.read()
    private var nextTargetMs = 5000L
    private var capLogged = false

    @Volatile
    var targetMs = 0L
        private set

    override fun onPrepared(playerId: PlayerId) {
        super.onPrepared(playerId)
        lastRebuffer = C.TIME_UNSET
        limits = settings.read()
        nextTargetMs = 5000
        targetMs = 0
    }

    private fun isHttpVod(parameters: LoadControl.Parameters): Boolean {
        val timeline = parameters.timeline
        val index = timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
        if (index == C.INDEX_UNSET) return false
        timeline.getWindow(timeline.getPeriod(index, period).windowIndex, window)
        val scheme = window.mediaItem.localConfiguration?.uri?.scheme
        return !window.isLive && !window.isDynamic && (scheme == "http" || scheme == "https")
    }

    private fun rebufferTarget(parameters: LoadControl.Parameters): Long {
        if (!parameters.rebuffering || !isHttpVod(parameters)) {
            targetMs = 0
            return 0
        }
        val event = parameters.lastRebufferRealtimeMs
        if (event != lastRebuffer || targetMs == 0L) {
            lastRebuffer = event
            targetMs = nextTargetMs
            nextTargetMs = (nextTargetMs * 2).coerceAtMost(limits.seconds * 1000L)
            capLogged = false
            AppLog.record("TvExoPlayback", "Rebuffer target=${targetMs}ms")
        }
        return targetMs
    }

    private fun reachedMemoryTarget(parameters: LoadControl.Parameters): Boolean {
        if (getAllocator(parameters.playerId).totalBytesAllocated < limits.memoryMiB * 1024 * 1024) return false
        if (targetMs > 0 && !capLogged) {
            capLogged = true
            AppLog.record("TvExoPlayback",
                "Buffer memory target reached: buffered=${parameters.bufferedDurationUs / 1000}ms target=${targetMs}ms")
        }
        return true
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val target = rebufferTarget(parameters)
        if (target == 0L) {
            if (isHttpVod(parameters) &&
                (parameters.bufferedDurationUs >= limits.seconds * 1_000_000L ||
                    reachedMemoryTarget(parameters))) return false
            return super.shouldContinueLoading(parameters)
        }
        return parameters.bufferedDurationUs < target * 1000 * parameters.playbackSpeed &&
            !reachedMemoryTarget(parameters)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val target = rebufferTarget(parameters)
        if (target == 0L) return super.shouldStartPlayback(parameters)
        return parameters.bufferedDurationUs >= target * 1000 * parameters.playbackSpeed ||
            reachedMemoryTarget(parameters)
    }
}
