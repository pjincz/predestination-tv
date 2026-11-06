package io.jcz.predestination.exo

import javax.xml.parsers.DocumentBuilderFactory

internal object CastTitle {
    fun parse(metadata: String): String? = field(metadata, "http://purl.org/dc/elements/1.1/", "title")

    fun field(metadata: String, namespace: String, name: String): String? = runCatching {
        if (metadata.isBlank() || metadata.length > 1024 * 1024) return null
        require(!metadata.contains("<!DOCTYPE", true) && !metadata.contains("<!ENTITY", true))
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw IllegalArgumentException("External entity") }
        val document = builder.parse(metadata.byteInputStream())
        document.getElementsByTagNameNS(namespace, name)
            .item(0)?.textContent?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
