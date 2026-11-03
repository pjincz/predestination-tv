package io.jcz.predestination.exo

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/** Linked-channel RMS compressor: -30 dBFS threshold, 4:1 ratio, +12 dB makeup. */
internal class NightCompressor {
    private var power = 0.0
    private var attack = 0.0
    private var release = 0.0
    private var interval = 1
    private var remaining = 0
    private var gain = 1.0

    fun configure(sampleRate: Int) {
        attack = exp(-1.0 / (sampleRate * 0.01))
        release = exp(-1.0 / (sampleRate * 0.15))
        interval = (sampleRate / 1000).coerceAtLeast(1)
        reset()
    }

    fun reset() {
        power = 0.0
        remaining = 0
        gain = 1.0
    }

    fun process(peak: Double): Double {
        val next = (peak / 32768.0).let { it * it }
        val coefficient = if (next > power) attack else release
        power = coefficient * power + (1 - coefficient) * next
        if (remaining-- <= 0) {
            val level = 10 * log10(power.coerceAtLeast(1e-12))
            val reduction = (level + 30).coerceAtLeast(0.0) * 0.75
            gain = 10.0.pow((12 - reduction) / 20)
            remaining = interval - 1
        }
        return gain
    }
}
