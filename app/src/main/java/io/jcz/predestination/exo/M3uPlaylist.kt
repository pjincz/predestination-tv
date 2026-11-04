package io.jcz.predestination.exo

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal object M3uPlaylist {
    data class Entry(val url: String, val title: String? = null)
    data class Result(val entries: List<Entry>, val hls: Boolean = false)

    fun matches(url: String): Boolean =
        URI(url).path.orEmpty().lowercase().let { it.endsWith(".m3u") || it.endsWith(".m3u8") }

    fun parse(text: String, base: String): Result {
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.toList()
        if (lines.any { it.startsWith("#EXT-X-") }) return Result(listOf(Entry(base)), true)
        val entries = mutableListOf<Entry>()
        var title: String? = null
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") -> title = line.substringAfter(',', "").ifBlank { null }
                line.isEmpty() || line.startsWith('#') -> Unit
                else -> {
                    val url = URI(base).resolve(line).toString()
                    Protocol.mediaUri(url)
                    require(!matches(url)) { "Nested playlists are not supported" }
                    entries.add(Entry(url, title))
                    require(entries.size <= 10000) { "Too many playlist entries" }
                    title = null
                }
            }
        }
        require(entries.isNotEmpty()) { "Empty playlist" }
        return Result(entries)
    }

    fun load(url: String): Result {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        try {
            require(connection.responseCode in 200..299) { "Playlist request failed: HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 2 * 1024 * 1024) { "Playlist too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return parse(bytes.toString(Charsets.UTF_8), connection.url.toString())
        } finally {
            connection.disconnect()
        }
    }
}
