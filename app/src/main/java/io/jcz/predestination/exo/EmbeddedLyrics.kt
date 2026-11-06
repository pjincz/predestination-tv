package io.jcz.predestination.exo

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.util.Locale

internal data class LyricLine(val timeMs: Long, val text: String)

internal class EmbeddedLyrics(val lines: List<LyricLine>) {
    fun indexAt(positionMs: Long): Int {
        var low = 0
        var high = lines.size
        while (low < high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) low = mid + 1 else high = mid
        }
        return low - 1
    }

    fun at(positionMs: Long): Pair<String, String> {
        val index = indexAt(positionMs)
        return lines.getOrNull(index)?.text.orEmpty() to lines.getOrNull(index + 1)?.text.orEmpty()
    }

    fun window(positionMs: Long, radius: Int = 2): List<String> {
        val index = indexAt(positionMs)
        return (-radius..radius).map { lines.getOrNull(index + it)?.text.orEmpty() }
    }

    companion object {
        private val timestamp = Regex("\\[(\\d{1,6}):([0-5]?\\d)(?:[.:](\\d{1,3}))?]")
        private val offsetTag = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
        private val keys = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS")

        fun parse(text: String): EmbeddedLyrics {
            if (text.length > 512 * 1024) return EmbeddedLyrics(emptyList())
            // A positive LRC offset advances the lyrics relative to the audio.
            val offset = offsetTag.findAll(text).lastOrNull()?.groupValues?.get(1)
                ?.toLongOrNull()?.coerceIn(-86_400_000, 86_400_000) ?: 0L
            val result = mutableListOf<LyricLine>()
            for (raw in text.lineSequence()) {
                val line = raw.trim().removePrefix("\uFEFF")
                val stamps = timestamp.findAll(line).toList()
                if (stamps.isEmpty() || stamps.first().range.first != 0) continue
                val words = line.substring(stamps.last().range.last + 1).trim().take(2000)
                for (stamp in stamps) {
                    val time = stamp.groupValues[1].toLong() * 60_000 +
                        stamp.groupValues[2].toLong() * 1000 +
                        stamp.groupValues[3].padEnd(3, '0').toLong()
                    result.add(LyricLine((time - offset).coerceAtLeast(0), words))
                    if (result.size >= 10000) break
                }
                if (result.size >= 10000) break
            }
            return EmbeddedLyrics(result.groupBy { it.timeMs }.toSortedMap().map { (time, values) ->
                LyricLine(time, values.map { it.text }.distinct().joinToString("\n"))
            })
        }

        @androidx.annotation.OptIn(UnstableApi::class)
        fun from(formats: List<Format>): EmbeddedLyrics {
            for (format in formats) {
                val metadata = format.metadata ?: continue
                for (index in 0 until metadata.length()) {
                    val entry = metadata[index]
                    val text = when {
                        entry is VorbisComment && entry.key.uppercase(Locale.ROOT) in keys -> entry.value
                        entry is TextInformationFrame && entry.id == "TXXX" &&
                            entry.description?.uppercase(Locale.ROOT) in keys -> entry.values.joinToString("\n")
                        else -> continue
                    }
                    val lyrics = parse(text)
                    if (lyrics.lines.isNotEmpty()) return lyrics
                }
            }
            return EmbeddedLyrics(emptyList())
        }
    }
}
