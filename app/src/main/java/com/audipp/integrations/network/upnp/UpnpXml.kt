package com.audipp.integrations.network.upnp

import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

internal const val MAX_UPNP_XML_BYTES = 2 * 1024 * 1024
internal const val MAX_UPNP_ITEMS = 1000
// Compact markup can allocate far more heap as a tree than its wire size.
private const val MAX_XML_ELEMENTS = 65_536

internal class UpnpXml(val name: String, val namespace: String) {
    val children = mutableListOf<UpnpXml>()
    val text = StringBuilder()

    fun elements(name: String, namespace: String = this.namespace): List<UpnpXml> =
        children.filter { it.name == name && it.namespace == namespace }

    fun element(name: String, namespace: String = this.namespace): UpnpXml? =
        elements(name, namespace).let { if (it.size > 1) invalidUpnpResponse() else it.firstOrNull() }

    fun value(name: String): String? = element(name)?.let {
        if (it.children.isNotEmpty()) invalidUpnpResponse()
        it.text.toString().trim()
    }

    override fun toString() = "UpnpXml(redacted)"
}

internal fun parseUpnpXml(bytes: ByteArray, checkActive: () -> Unit = {}): UpnpXml {
    if (bytes.size > MAX_UPNP_XML_BYTES) invalidUpnpResponse()
    val stack = ArrayDeque<UpnpXml>()
    var root: UpnpXml? = null
    var elements = 0
    val handler = object : DefaultHandler2() {
        override fun startDTD(name: String?, publicId: String?, systemId: String?) = throw SAXException()
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException()
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource =
            throw SAXException()
        override fun error(e: SAXParseException) = throw e
        override fun fatalError(e: SAXParseException) = throw e

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            checkActive()
            if (stack.size == 32 || ++elements > MAX_XML_ELEMENTS) throw SAXException()
            val node = UpnpXml(localName, uri)
            val parent = stack.lastOrNull()
            if (parent == null) {
                root = node
            } else {
                if (parent.children.size == MAX_UPNP_ITEMS) throw SAXException()
                parent.children.add(node)
            }
            stack.addLast(node)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            checkActive()
            stack.lastOrNull()?.text?.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            stack.removeLast()
        }
    }
    try {
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.entityResolver = handler
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.parse(InputSource(ByteArrayInputStream(bytes)))
    } catch (_: SAXException) {
        invalidUpnpResponse()
    } catch (_: javax.xml.parsers.ParserConfigurationException) {
        invalidUpnpResponse()
    }
    return root ?: invalidUpnpResponse()
}
