package io.jcz.predestination.exo

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioGainTest {
    @Test fun acceptsEmptyPipelineBufferBeforeFirstAudioFrame() {
        val processor = processor()
        processor.queueInput(androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER)
        assertFalse(processor.output.hasRemaining())
        assertEquals(listOf(1000, -1000), samples(processor, 1000, -1000))
    }

    private fun processor() = AudioGain {}.apply {
        configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        flush()
    }

    @Test fun diagnosticsReportActualLimiterReductionAndResetOnGainChange() {
        val logs = mutableListOf<String>()
        val processor = AudioGain(logs::add).apply {
            configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
            flush()
            setGain(18)
        }
        samples(processor, 30000, -15000)
        processor.setGain(0)
        samples(processor, 1000, -1000)
        processor.flush()
        assertEquals(2, logs.size)
        assertTrue(logs[0].contains("requested=18dB effectiveMean=0.77dB"))
        assertTrue(logs[0].contains("limited=100.0% overload=100.0%"))
        assertTrue(logs[1].contains("requested=0dB effectiveMean=0.00dB"))
        assertTrue(logs[1].contains("limited=0.0% overload=0.0%"))
    }

    private fun samples(processor: AudioGain, vararg samples: Int): List<Int> {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it.toShort()) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        return List(output.remaining() / 2) { output.short.toInt() }
    }

    @Test fun zeroIsBitExactAndGainCanChangeWithoutReconfiguring() {
        val processor = processor()
        assertTrue(processor.isActive)
        assertEquals(listOf(-32768, 32767, 1, -1), samples(processor, -32768, 32767, 1, -1))
        processor.setGain(6)
        assertEquals(listOf(1995, -1995), samples(processor, 1000, -1000))
        processor.setGain(0)
        assertEquals(listOf(1000, -1000), samples(processor, 1000, -1000))
    }

    @Test fun limiterPreventsOverflowAndPreservesChannelBalance() {
        val processor = processor()
        processor.setGain(18)
        val result = samples(processor, 30000, -15000)
        assertTrue(result[0] in 32765..32767)
        assertEquals(-0.5, result[1].toDouble() / result[0], 0.0001)
        assertTrue(samples(processor, -32768, 32767).all { it in -32768..32767 })
        processor.setGain(0)
        assertEquals(listOf(1000, -1000), samples(processor, 1000, -1000))
    }

    @Test fun flushKeepsUserGainButClearsPreviousPeakAttenuation() {
        val processor = processor()
        processor.setGain(6)
        samples(processor, 30000, 30000)
        processor.flush()
        assertEquals(listOf(1995, 1995), samples(processor, 1000, 1000))
    }
}
