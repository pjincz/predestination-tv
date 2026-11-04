package io.jcz.predestination.exo

import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

internal object CastImage {
    fun matches(uri: String, metadata: String): Boolean {
        val declared = runCatching {
            if (metadata.isBlank() || metadata.length > 1024 * 1024) return@runCatching null
            require(!metadata.contains("<!DOCTYPE", true) && !metadata.contains("<!ENTITY", true))
            val builder = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw IllegalArgumentException("External entity") }
            val document = builder.parse(metadata.byteInputStream())
            val type = document.getElementsByTagNameNS("urn:schemas-upnp-org:metadata-1-0/upnp/", "class")
                .item(0)?.textContent.orEmpty().trim()
            if (type.startsWith("object.item.imageItem")) return@runCatching true
            if (type.startsWith("object.item.audioItem") || type.startsWith("object.item.videoItem"))
                return@runCatching false
            val resources = document.getElementsByTagNameNS("*", "res")
            for (index in 0 until resources.length) {
                val resource = resources.item(index) as Element
                if (resource.textContent.trim() != uri) continue
                val mime = resource.getAttribute("protocolInfo").split(':').getOrNull(2)
                if (mime != null && mime != "*") return@runCatching mime.startsWith("image/")
            }
            null
        }.getOrNull()
        return declared ?: runCatching {
            URI(uri).path.orEmpty().substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png")
        }.getOrDefault(false)
    }
}
