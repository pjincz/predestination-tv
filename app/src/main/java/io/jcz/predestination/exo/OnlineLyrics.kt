package io.jcz.predestination.exo

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.abs

internal data class LyricQuery(val title: String, val artist: String, val album: String, val seconds: Long)

internal class OnlineLyrics(directory: java.io.File) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val cache = LyricsDiskCache(directory)

    fun load(query: LyricQuery, done: (EmbeddedLyrics, Boolean) -> Unit) {
        worker.submit {
            var failed = false
            val cached = cache.read(query)
            val result = cached ?: runCatching { fetch(query) }.getOrElse {
                failed = true
                AppLog.error("TvExoLyrics", "LRCLIB request failed", it)
                EmbeddedLyrics(emptyList())
            }.also { if (it.lines.isNotEmpty()) cache.write(query, it) }
            if (cached != null) AppLog.record("TvExoLyrics", "LRCLIB disk cache hit")
            AppLog.record("TvExoLyrics", "LRCLIB timed lyrics: lines=${result.lines.size}")
            done(result, failed)
        }
    }

    private fun fetch(query: LyricQuery): EmbeddedLyrics {
        val args = linkedMapOf("track_name" to query.title, "artist_name" to query.artist,
            "duration" to query.seconds.toString())
        if (query.album.isNotBlank()) args["album_name"] = query.album
        val encoded = args.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val connection = URL("https://lrclib.net/api/get?$encoded").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "Predestination-TV-Exo/0.9.1")
            val code = connection.responseCode
            AppLog.record("TvExoLyrics", "LRCLIB response=$code")
            if (code == 404) return EmbeddedLyrics(emptyList())
            check(code == 200)
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1024 * 1024)
                    output.write(buffer, 0, count)
                }
            }
            return decode(query, output.toString("UTF-8"))
        } finally {
            connection.disconnect()
        }
    }

    override fun close() {
        worker.shutdownNow()
    }

    companion object {
        fun decode(query: LyricQuery, json: String): EmbeddedLyrics {
            val record = JSONObject(json)
            val duration = record.optDouble("duration", Double.NaN)
            if (!duration.isFinite() || abs(duration - query.seconds) > 2 ||
                record.optBoolean("instrumental")) return EmbeddedLyrics(emptyList())
            return EmbeddedLyrics.parse(record.optString("syncedLyrics").takeUnless { it == "null" }.orEmpty())
        }
    }
}
