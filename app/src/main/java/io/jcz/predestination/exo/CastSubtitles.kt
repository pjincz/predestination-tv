package io.jcz.predestination.exo

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object CastSubtitles {
    fun parse(metadata: String): List<MediaItem.SubtitleConfiguration> = runCatching {
        if (metadata.isBlank() || metadata.length > 1024 * 1024) return emptyList()
        require(!metadata.contains("<!DOCTYPE", true) && !metadata.contains("<!ENTITY", true))
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw IllegalArgumentException("External entity") }
        val document = builder.parse(metadata.byteInputStream())
        val entries = linkedMapOf<String, String>()
        fun add(url: String, format: String) {
            val mime = when (format.lowercase().removePrefix("text/")) {
                "srt", "application/x-subrip" -> "application/x-subrip"
                "vtt" -> "text/vtt"
                "ass", "ssa", "text/x-ssa" -> "text/x-ssa"
                else -> return
            }
            val value = url.trim()
            val parsed = java.net.URI(value)
            if (parsed.scheme?.lowercase() !in listOf("http", "https") || parsed.host.isNullOrEmpty()) return
            if (entries.size < 32) entries.putIfAbsent(value, mime)
        }
        val resources = document.getElementsByTagNameNS("*", "res")
        for (i in 0 until resources.length) {
            val node = resources.item(i) as Element
            val mime = node.getAttribute("protocolInfo").split(':').getOrNull(2).orEmpty()
            add(node.textContent, mime)
        }
        for (name in listOf("CaptionInfoEx", "CaptionInfo")) {
            val nodes = document.getElementsByTagNameNS("http://www.sec.co.kr/", name)
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as Element
                add(node.textContent, node.getAttributeNS("http://www.sec.co.kr/", "type"))
            }
        }
        for (i in 0 until resources.length) {
            val node = resources.item(i) as Element
            add(node.getAttributeNS("http://www.pv.com/pvns/", "subtitleFileUri"),
                node.getAttributeNS("http://www.pv.com/pvns/", "subtitleFileType"))
        }
        entries.entries.mapIndexed { index, (url, mime) ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(url)).setMimeType(mime)
                .setId("cast-subtitle-$index")
                .setLabel(Uri.parse(url).lastPathSegment ?: "${index + 1}")
                .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0).build()
        }
    }.getOrElse {
        AppLog.record("TvExoSubtitle", "Invalid subtitle metadata: ${it.javaClass.simpleName}")
        emptyList()
    }
}
