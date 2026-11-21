package com.audipp.integrations.network.webdav

import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.validNetworkText
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URISyntaxException
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

private const val MAX_XML_BYTES = 2 * 1024 * 1024
private const val MAX_XML_DEPTH = 32
private const val MAX_DIRECTORY_ENTRIES = 10_000
private const val MAX_HREF = 8_192
private const val DAV = "DAV:"

private val propfindBody = """
    <D:propfind xmlns:D="DAV:">
      <D:prop><D:resourcetype/><D:displayname/><D:getcontenttype/></D:prop>
    </D:propfind>
""".trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())

internal fun propfind(directory: HttpUrl): Request = Request.Builder()
    .url(directory)
    .header("Depth", "1")
    .method("PROPFIND", propfindBody)
    .build()

internal fun webDavRoot(value: String): HttpUrl {
    val uri = checkedHref(value)
    val root = value.toHttpUrlOrNull()
    require(uri != null && root != null && root.path.none(::hasUnsupportedEncoding)) { "Invalid WebDAV root" }
    return root.asDirectory().also {
        require(it.toString().length <= MAX_HREF) { "Invalid WebDAV root" }
    }
}

internal class WebDavEntry(val url: HttpUrl, val collection: Boolean, val name: String, val contentType: String?)

internal fun parseDirectory(response: Response, directory: HttpUrl): List<WebDavEntry> {
    val handler = MultiStatus(directory)
    // Typical PROPFIND entries reach 2 MiB before 10,000 items; treat it as a large directory, not a malformed reply.
    val bytes = response.boundedBody(MAX_XML_BYTES, NetworkFailure.DIRECTORY_LIMIT_EXCEEDED)
    try {
        val parser = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        parser.entityResolver = handler
        parser.errorHandler = handler
        parser.contentHandler = handler
        parser.parse(InputSource(ByteArrayInputStream(bytes)))
    } catch (_: SAXException) {
        invalidResponse()
    } catch (_: ParserConfigurationException) {
        invalidResponse()
    }
    return handler.entries()
}

private val nestedEscape = Regex("%[0-9a-fA-F]{2}")
private val encodedSlash = Regex("%(?:25)*+2f", RegexOption.IGNORE_CASE)
private val dotSegment = Regex("(?:\\.|%(?:25)*+2e){1,2}", RegexOption.IGNORE_CASE)

private fun hasUnsupportedEncoding(segment: String): Boolean =
    nestedEscape.containsMatchIn(segment) || segment.any { it == '\\' || it == '\uFFFD' || it.isISOControl() }

private fun representableSegment(segment: String): Boolean =
    validNetworkText(segment) && !hasUnsupportedEncoding(segment)

private fun checkedHref(value: String): URI? {
    if (value.isEmpty() || value.any { it.isISOControl() || it == '\\' } ||
        value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) != value
    ) return null
    val uri = try {
        URI(value)
    } catch (_: URISyntaxException) {
        return null
    }
    if (uri.isOpaque || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        (uri.scheme == null && (uri.rawAuthority != null || !value.startsWith('/') || value.startsWith("//"))) ||
        (uri.scheme != null && (uri.host == null || uri.port != -1 && uri.port !in 1..65_535))
    ) return null
    val segments = uri.rawPath.orEmpty().split('/').drop(1).dropLastWhile { it.isEmpty() }
    if (uri.rawPath.orEmpty().contains("//")) return null
    for (segment in segments) {
        val decoded = URI("/" + segment).path.removePrefix("/")
        if (dotSegment.matches(decoded) || dotSegment.matches(segment) || '/' in decoded ||
            encodedSlash.containsMatchIn(decoded) || encodedSlash.containsMatchIn(segment)
        ) return null
    }
    return uri
}

private fun HttpUrl.asDirectory(): HttpUrl =
    if (encodedPath.endsWith('/')) this else newBuilder().addPathSegment("").build()

private val HttpUrl.path get() = pathSegments.dropLastWhile { it.isEmpty() }

internal fun relativeMediaPath(root: HttpUrl, url: HttpUrl): String {
    val path = url.encodedPathSegments.drop(root.pathSegments.size - 1).joinToString("/")
    return nestedEscape.replace(path) { match ->
        val char = match.value.substring(1).toInt(16).toChar()
        // Escaped reserved characters can have a different meaning at the server.
        if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~") char.toString()
        else match.value
    }
}

internal fun resolveMediaPath(root: HttpUrl, relativePath: String): HttpUrl? {
    if (relativePath.length > MAX_HREF) return null
    val path = checkedHref("/$relativePath")?.path ?: return null
    if (path.drop(1).split('/').any { !representableSegment(it) }) return null
    val url = root.newBuilder().addEncodedPathSegments(relativePath).build()
    return url.takeIf { it.toString().length <= MAX_HREF }
}

private val rootTag = listOf("multistatus")
private val responseTag = rootTag + "response"
private val hrefTag = responseTag + "href"
private val responseStatusTag = responseTag + "status"
private val propstatTag = responseTag + "propstat"
private val propertyStatusTag = propstatTag + "status"
private val propTag = propstatTag + "prop"
private val resourceTypeTag = propTag + "resourcetype"
private val collectionTag = resourceTypeTag + "collection"
private val displayNameTag = propTag + "displayname"
private val contentTypeTag = propTag + "getcontenttype"
private val scalarTags = setOf(hrefTag, responseStatusTag, propertyStatusTag, displayNameTag, contentTypeTag)
private val statusLine = Regex("""HTTP/\d(?:\.\d)? ([1-5]\d\d)(?: .*)?""")

private class Properties(var collection: Boolean? = null, var name: String? = null, var contentType: String? = null) {
    fun merge(other: Properties) {
        collection = merge(collection, other.collection)
        name = merge(name, other.name)
        contentType = merge(contentType, other.contentType)
    }

    private fun <T> merge(old: T?, new: T?): T? {
        if (old != null && new != null && old != new) invalidResponse()
        return new ?: old
    }
}

private class MultiStatus(private val directory: HttpUrl) : DefaultHandler2() {
    private val tags = mutableListOf<String>()
    private val children = mutableListOf<WebDavEntry>()
    private val seen = mutableSetOf<List<String>>()
    private var responseCount = 0
    private var selfFound = false
    private var href: String? = null
    private var status: Int? = null
    private var propStatus: Int? = null
    private var properties = Properties()
    private var currentPropstat = Properties()
    private var text: StringBuilder? = null

    override fun startDTD(name: String, publicId: String?, systemId: String?) = invalidResponse()
    override fun resolveEntity(publicId: String?, systemId: String?): InputSource = invalidResponse()
    override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource = invalidResponse()
    override fun error(e: SAXParseException) = invalidResponse()
    override fun fatalError(e: SAXParseException) = invalidResponse()

    override fun startElement(uri: String, localName: String, qName: String, attributes: org.xml.sax.Attributes) {
        if (text != null || tags.size == MAX_XML_DEPTH) invalidResponse()
        tags.add(if (uri == DAV) localName else "")
        if (tags.size == 1 && tags != rootTag) invalidResponse()
        when (tags) {
            responseTag -> {
                if (++responseCount > MAX_DIRECTORY_ENTRIES + 1) {
                    throw WebDavResponseException(NetworkFailure.DIRECTORY_LIMIT_EXCEEDED)
                }
                href = null
                status = null
                properties = Properties()
            }
            propstatTag -> {
                currentPropstat = Properties()
                propStatus = null
            }
            resourceTypeTag -> currentPropstat.collection = false
            collectionTag -> currentPropstat.collection = true
            in scalarTags -> text = StringBuilder()
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        val target = text ?: return
        if (tags != hrefTag && tags != displayNameTag && target.length + length > MAX_HREF) invalidResponse()
        target.append(ch, start, length)
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        when (tags) {
            hrefTag -> {
                if (href != null) invalidResponse()
                href = scalar()
            }
            responseStatusTag -> {
                if (status != null) invalidResponse()
                status = parseStatus(scalar())
            }
            propertyStatusTag -> {
                if (propStatus != null) invalidResponse()
                propStatus = parseStatus(scalar())
            }
            displayNameTag -> currentPropstat.name = scalar()
            contentTypeTag -> currentPropstat.contentType = scalar()
            propstatTag -> {
                val code = propStatus ?: invalidResponse()
                if (code in 200..299) properties.merge(currentPropstat)
            }
            responseTag -> addEntry()
        }
        tags.removeAt(tags.lastIndex)
    }

    private fun scalar(): String = text.toString().trim().also { text = null }

    private fun parseStatus(line: String): Int = statusLine.matchEntire(line)?.groupValues?.get(1)?.toInt()
        ?: invalidResponse()

    private fun addEntry() {
        val value = href ?: invalidResponse()
        if (checkedHref(value) == null) invalidResponse()
        val url = directory.resolve(value) ?: invalidResponse()
        if (url.scheme != directory.scheme || url.host != directory.host || url.port != directory.port) invalidResponse()
        val path = url.path
        if (!seen.add(path)) invalidResponse()
        if (path == directory.path) {
            selfFound = (status ?: 200) in 200..299 && properties.collection == true
            return
        }
        if (path.size != directory.path.size + 1 || path.dropLast(1) != directory.path) invalidResponse()
        if (!representableSegment(path.last())) return
        if ((status ?: 200) !in 200..299) return
        val collection = properties.collection ?: return
        if (!collection && url.encodedPath.endsWith('/')) invalidResponse()
        val name = properties.name?.takeIf(::validNetworkText) ?: path.last()
        val resource = if (collection) url.asDirectory() else url
        if (resource.toString().length > MAX_HREF) return
        children += WebDavEntry(resource, collection, name, properties.contentType)
    }

    fun entries(): List<WebDavEntry> {
        if (!selfFound) invalidResponse()
        return children.toList()
    }
}
