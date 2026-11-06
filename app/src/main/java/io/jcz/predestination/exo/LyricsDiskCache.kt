package io.jcz.predestination.exo

import android.util.AtomicFile
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/** Successful timed lyrics only; stored in app files so system cache cleanup does not evict them. */
internal class LyricsDiskCache(private val directory: File) {
    private fun file(query: LyricQuery): AtomicFile {
        val key = JSONArray().put(query.title).put(query.artist).put(query.album)
            .put(query.seconds).toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$hash.json"))
    }

    fun read(query: LyricQuery): EmbeddedLyrics? {
        val source = file(query)
        return try {
            if (!source.baseFile.exists() && !File(source.baseFile.path + ".bak").exists()) return null
            val bytes = source.openRead().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 8 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val rows = JSONArray(bytes.toString(Charsets.UTF_8))
            require(rows.length() in 1..10000)
            val lines = (0 until rows.length()).map {
                val row = rows.getJSONArray(it)
                LyricLine(row.getLong(0), row.getString(1))
            }
            require(lines.all { it.timeMs >= 0 } && lines.zipWithNext().all { it.first.timeMs <= it.second.timeMs })
            EmbeddedLyrics(lines)
        } catch (error: Exception) {
            AppLog.error("TvExoLyrics", "Lyrics cache read failed; fetching again", error)
            null
        }
    }

    fun write(query: LyricQuery, lyrics: EmbeddedLyrics) {
        if (lyrics.lines.isEmpty()) return
        try {
            check(directory.isDirectory || directory.mkdirs())
            val rows = JSONArray()
            lyrics.lines.forEach { rows.put(JSONArray().put(it.timeMs).put(it.text)) }
            val target = file(query)
            val stream = target.startWrite()
            try {
                stream.write(rows.toString().toByteArray(Charsets.UTF_8))
                target.finishWrite(stream)
            } catch (error: Exception) {
                target.failWrite(stream)
                throw error
            }
        } catch (error: Exception) {
            // Cache failure must never prevent displaying successfully downloaded lyrics.
            AppLog.error("TvExoLyrics", "Lyrics cache write failed", error)
        }
    }
}
