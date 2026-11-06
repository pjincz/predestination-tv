package io.jcz.predestination.exo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.xml.parsers.DocumentBuilderFactory

internal class CastArtwork(private val display: (Bitmap?) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var pending: Future<*>? = null
    private var generation = 0

    private var mediaKey: Any? = null
    private var embedded: ByteArray? = null
    private var remoteUrl: String? = null

    /** Prefer the casting client's cover URL, falling back to embedded artwork. */
    fun loadMedia(key: Any?, data: ByteArray?, url: String?) {
        if (key == mediaKey && data.contentEquals(embedded) && url == remoteUrl) return
        clear()
        mediaKey = key
        embedded = data
        remoteUrl = url
        if (data == null && url == null) return
        val request = generation
        pending = worker.submit {
            val remote = runCatching {
                url?.let { download(it, 1024, 4 * 1024 * 1024) }
            }.getOrNull()
            val bitmap = remote ?: runCatching {
                data?.takeIf { it.size <= 16 * 1024 * 1024 }?.let { decode(it, 1024) }
            }.getOrNull()
            main.post {
                if (request == generation) {
                    AppLog.record("TvExoArtwork", "Cover source=${if (remote != null) "remote" else "embedded"} loaded=${bitmap != null}")
                    display(bitmap)
                } else bitmap?.recycle()
            }
        }
    }

    fun loadUrl(url: String?, maxEdge: Int = 1024, maxBytes: Int = 4 * 1024 * 1024,
                completed: (Boolean) -> Unit = {}) {
        clear()
        if (url == null) return
        val request = generation
        pending = worker.submit {
            val bitmap = runCatching { download(url, maxEdge, maxBytes) }.getOrNull()
            main.post {
                if (request == generation) {
                    display(bitmap)
                    completed(bitmap != null)
                } else bitmap?.recycle()
            }
        }
    }

    fun clear() {
        mediaKey = null
        embedded = null
        remoteUrl = null
        generation++
        pending?.cancel(true)
        pending = null
        display(null)
    }

    override fun close() {
        clear()
        worker.shutdownNow()
    }

    companion object {
        fun artworkUrl(metadata: String): String? = runCatching {
            if (metadata.isBlank()) return null
            require(!metadata.contains("<!DOCTYPE", true) && !metadata.contains("<!ENTITY", true))
            val builder = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw IllegalArgumentException("External entity") }
            val doc = builder.parse(metadata.byteInputStream())
            val value = doc.getElementsByTagNameNS("urn:schemas-upnp-org:metadata-1-0/upnp/", "albumArtURI")
                .item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            Protocol.mediaUri(value)
        }.getOrNull()

        internal fun decode(bytes: ByteArray, maxEdge: Int): Bitmap? {
            require(maxEdge > 0)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxEdge)
                options.inSampleSize *= 2
            options.inJustDecodeBounds = false
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }

        private fun download(address: String, maxEdge: Int, maxBytes: Int): Bitmap? {
            var url = address
            repeat(5) {
                if (Thread.currentThread().isInterrupted) return null
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.instanceFollowRedirects = false
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val location = connection.getHeaderField("Location") ?: return null
                        url = Protocol.mediaUri(URL(URL(url), location).toString())
                        return@repeat
                    }
                    if (status != 200 || connection.contentLengthLong > maxBytes) return null
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) return null
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > maxBytes) return null
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    return decode(bytes, maxEdge)
                } finally { connection.disconnect() }
            }
            return null
        }
    }
}
