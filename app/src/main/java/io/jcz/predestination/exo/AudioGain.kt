package io.jcz.predestination.exo

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.log10
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
internal class AudioGain(
    private val log: (String) -> Unit = { AppLog.record("TvExoGain", it) }
) : BaseAudioProcessor() {
    @Volatile var decibels = 0
        private set
    private var attenuation = 1.0
    private var release = 0.0
    private var frames = 0L
    private var limitedFrames = 0L
    private var overloadFrames = 0L
    private var attenuationSum = 0.0
    private var minimum = 1.0
    private var maximum = 0.0
    private var inputPeak = 0.0
    private var outputPeak = 0.0
    private var windowDb = 0
    private var windowRate = 0
    private var windowChannels = 0

    fun setGain(value: Int) {
        require(value in 0..18 && value % 3 == 0)
        decibels = value
    }

    override fun onConfigure(input: AudioFormat): AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) throw UnhandledAudioFormatException(input)
        release = exp(-1.0 / (input.sampleRate * 0.1))
        // Stay active at 0 dB so gain changes do not require restarting playback.
        return input
    }

    override fun onFlush() {
        report()
        attenuation = 1.0
    }

    private fun report() {
        if (frames == 0L) return
        fun db(value: Double) = if (value == 0.0) Double.NEGATIVE_INFINITY else 20 * log10(value)
        log(String.format(Locale.ROOT,
            "PCM %.2fs channels=%d requested=%ddB effectiveMean=%.2fdB effectiveRange=%.2f..%.2fdB " +
                "maxReduction=%.2fdB limited=%.1f%% overload=%.1f%% inputPeak=%.2fdBFS outputPeak=%.2fdBFS",
            frames.toDouble() / windowRate, windowChannels, windowDb,
            windowDb + db(attenuationSum / frames), windowDb + db(minimum), windowDb + db(maximum),
            -db(minimum), 100.0 * limitedFrames / frames, 100.0 * overloadFrames / frames,
            db(inputPeak / 32768.0), db(outputPeak / 32768.0)))
        frames = 0
        limitedFrames = 0
        overloadFrames = 0
        attenuationSum = 0.0
        minimum = 1.0
        maximum = 0.0
        inputPeak = 0.0
        outputPeak = 0.0
    }

    override fun queueInput(input: ByteBuffer) {
        // The pipeline polls with its shared empty buffer before audio arrives.
        if (!input.hasRemaining()) return
        val output = replaceOutputBuffer(input.remaining())
        val db = decibels
        if (db != windowDb) report()
        windowDb = db
        windowRate = inputAudioFormat.sampleRate
        windowChannels = inputAudioFormat.channelCount
        if (db == 0) {
            attenuation = 1.0
            val count = input.remaining() / inputAudioFormat.bytesPerFrame
            for (offset in input.position() until input.limit() step 2) {
                inputPeak = maxOf(inputPeak, abs(input.getShort(offset).toDouble()))
            }
            outputPeak = inputPeak
            frames += count
            attenuationSum += count
            minimum = 1.0
            maximum = 1.0
            output.put(input)
        } else {
            val gain = 10.0.pow(db / 20.0)
            val channels = inputAudioFormat.channelCount
            while (input.hasRemaining()) {
                val start = input.position()
                var peak = 0.0
                repeat(channels) { peak = maxOf(peak, abs(input.short.toDouble()) * gain) }
                val ceiling = if (peak > 32767.0) 32767.0 / peak else 1.0
                // Linked channels preserve balance; immediate attack, 100 ms release.
                attenuation = minOf(ceiling, 1.0 - (1.0 - attenuation) * release)
                frames++
                if (attenuation < 0.988553) limitedFrames++ // More than 0.1 dB reduction, including release.
                if (ceiling < 1.0) overloadFrames++
                attenuationSum += attenuation
                minimum = minOf(minimum, attenuation)
                maximum = maxOf(maximum, attenuation)
                inputPeak = maxOf(inputPeak, peak / gain)
                input.position(start)
                repeat(channels) {
                    val sample = input.short * gain * attenuation
                    val result = sample.toInt().coerceIn(-32768, 32767)
                    outputPeak = maxOf(outputPeak, abs(result.toDouble()))
                    output.putShort(result.toShort())
                }
            }
        }
        output.flip()
        if (frames >= windowRate * 5L) report()
    }
}
