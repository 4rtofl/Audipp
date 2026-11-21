package com.audipp.integrations.network.webdav

import java.util.Locale
import okhttp3.Request
import okhttp3.Response

internal const val MAX_WEB_DAV_RANGE_BYTES = 256 * 1024
private val entityTag = Regex("""(?:W/)?"[!\x23-\x7E]*"""")
private val contentRange = Regex("""(?i:bytes) ([0-9]+)-([0-9]+)/([0-9]+)""")

internal class MediaIdentity(
    private val etag: String?,
    private val lastModified: String?,
    private val modifiedAtEpochMs: Long?,
    val length: Long?,
) {
    val strongTag: String? get() = etag?.takeUnless { it.startsWith("W/") }

    fun matches(current: MediaIdentity): Boolean =
        (etag == null || etag == current.etag) &&
            (modifiedAtEpochMs == null || modifiedAtEpochMs == current.modifiedAtEpochMs ||
                current.modifiedAtEpochMs == null && strongTag != null) &&
            (length == null || current.length == null || length == current.length)

    fun constrain(request: Request.Builder): Request.Builder = request.apply {
        val tag = strongTag
        if (tag != null) header("If-Match", tag)
        else lastModified?.let { header("If-Unmodified-Since", it) }
    }
}

internal fun Response.mediaIdentity(length: Long? = mediaContentLength()): MediaIdentity {
    val type = singleHeader("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    if (type?.startsWith("text/") == true || type in nonMediaContentTypes) invalidResponse()
    val etag = singleHeader("ETag")
    val modified = singleHeader("Last-Modified")
    if (etag != null && !entityTag.matches(etag)) invalidResponse()
    val modifiedAt = modified?.let { httpDate(it) ?: invalidResponse() }
    return MediaIdentity(etag, modified, modifiedAt, length)
}

private val nonMediaContentTypes = setOf(
    "application/json", "application/xml", "application/xhtml+xml", "multipart/byteranges",
)

private fun Response.mediaContentLength(): Long? = singleHeader("Content-Length")?.let {
    it.toLongOrNull()?.takeIf { length -> length >= 0 } ?: invalidResponse()
}

internal fun Response.fullIdentity(): MediaIdentity {
    if (singleHeader("Content-Range") != null) invalidResponse()
    return mediaIdentity()
}

internal class PartialContent(val start: Long, val end: Long, val total: Long)

internal fun Response.partialContent(): PartialContent {
    val groups = singleHeader("Content-Range")?.let(contentRange::matchEntire)?.groupValues ?: invalidResponse()
    val start = groups[1].toLongOrNull() ?: invalidResponse()
    val end = groups[2].toLongOrNull() ?: invalidResponse()
    val total = groups[3].toLongOrNull() ?: invalidResponse()
    if (start > end || end >= total) invalidResponse()
    val length = mediaContentLength()
    if (length != null && length != end - start + 1) invalidResponse()
    return PartialContent(start, end, total)
}

internal fun Response.exactBody(bytes: Int): ByteArray = boundedBody(bytes).also {
    if (it.size != bytes) invalidResponse()
}
