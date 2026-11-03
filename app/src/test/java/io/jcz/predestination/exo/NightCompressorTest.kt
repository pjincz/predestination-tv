package io.jcz.predestination.exo

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow

class NightCompressorTest {
    private fun settledGain(level: Double): Double {
        val compressor = NightCompressor().apply { configure(48000) }
        var gain = 0.0
        repeat(48000) { gain = compressor.process(32768 * 10.0.pow(level / 20)) }
        return 20 * log10(gain)
    }

    @Test fun boostsQuietSignalAndCompressesAboveThresholdAtFourToOne() {
        assertEquals(12.0, settledGain(-40.0), 0.1)
        val quiet = -25 + settledGain(-25.0)
        val loud = -5 + settledGain(-5.0)
        assertEquals(5.0, loud - quiet, 0.1)
    }

    @Test fun recoversAfterLoudSoundAndResetClearsHistory() {
        val compressor = NightCompressor().apply { configure(48000) }
        repeat(48000) { compressor.process(30000.0) }
        var gain = 0.0
        repeat(96000) { gain = compressor.process(100.0) }
        assertEquals(12.0, 20 * log10(gain), 0.1)
        compressor.reset()
        assertEquals(12.0, 20 * log10(compressor.process(100.0)), 0.1)
    }
}
