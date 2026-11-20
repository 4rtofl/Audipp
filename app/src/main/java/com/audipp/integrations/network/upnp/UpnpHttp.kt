package com.audipp.integrations.network.upnp

import com.audipp.integrations.network.NetworkFailure
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

internal class UpnpResponse(val status: Int, val body: ByteArray)
internal class UpnpPost(val soapAction: String, val body: ByteArray)

private const val HTTP_HEADERS_LIMIT = 16 * 1024
private const val HTTP_CHUNK_FRAMING_LIMIT = 128 * 1024
private val httpStatus = Regex("HTTP/1\\.[01] ([1-5][0-9]{2})(?: [ -~]*)?")
private val headerName = Regex("[!#%&'*+.^_`|~0-9A-Za-z-]+")
private val chunkSize = Regex("[0-9a-fA-F]{1,8}")

// The caller has already pinned the peer and bound the socket to its selected network.
internal fun upnpHttp(socket: Socket, peer: UpnpPeer, uri: URI, post: UpnpPost?): UpnpResponse {
    socket.soTimeout = 3_000
    socket.connect(InetSocketAddress(peer.address, uri.httpPort), 3_000)
    val path = URI(uri.toASCIIString()).rawPath.ifEmpty { "/" }
    val host = uri.host + if (uri.port == -1) "" else ":${uri.port}"
    val output = socket.getOutputStream()
    val headers = buildString {
        append(if (post == null) "GET " else "POST ")
        append("$path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nAccept-Encoding: identity\r\n")
        if (post != null) {
            append("Content-Type: text/xml; charset=\"utf-8\"\r\n")
            append("SOAPAction: \"${post.soapAction}\"\r\nContent-Length: ${post.body.size}\r\n")
        }
        append("\r\n")
    }
    output.write(headers.toByteArray(Charsets.US_ASCII))
    post?.let { output.write(it.body) }
    output.flush()
    return readUpnpHttp(BufferedInputStream(socket.getInputStream()))
}

private fun readUpnpHttp(input: InputStream): UpnpResponse {
    val headersBudget = LineBudget(HTTP_HEADERS_LIMIT)
    val status = httpStatus.matchEntire(headersBudget.read(input))?.groupValues?.get(1)?.toInt()
        ?: invalidUpnpResponse()
    when (status) {
        200, 500 -> Unit
        401, 403 -> throw UpnpException(NetworkFailure.AUTH_REQUIRED)
        else -> invalidUpnpResponse()
    }
    var length: Int? = null
    var chunked = false
    var encodingSeen = false
    while (true) {
        val line = headersBudget.read(input)
        if (line.isEmpty()) break
        val (name, value) = header(line)
        when (name) {
            "content-length" -> {
                if (length != null || value.isEmpty() || !value.all { it in '0'..'9' }) invalidUpnpResponse()
                length = value.toIntOrNull()?.takeIf { it <= MAX_UPNP_XML_BYTES } ?: invalidUpnpResponse()
            }
            "transfer-encoding" -> {
                if (chunked || !value.equals("chunked", ignoreCase = true)) invalidUpnpResponse()
                chunked = true
            }
            "content-encoding" -> {
                if (encodingSeen || !value.equals("identity", ignoreCase = true)) invalidUpnpResponse()
                encodingSeen = true
            }
        }
    }
    if (chunked && length != null) invalidUpnpResponse()
    val body = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    when {
        chunked -> {
            val framing = LineBudget(HTTP_CHUNK_FRAMING_LIMIT)
            while (true) {
                val size = framing.read(input).substringBefore(';').trim()
                    .takeIf(chunkSize::matches)?.toLongOrNull(16) ?: invalidUpnpResponse()
                if (size == 0L) break
                if (size > MAX_UPNP_XML_BYTES - body.size()) invalidUpnpResponse()
                copyExact(input, body, buffer, size.toInt())
                if (framing.read(input).isNotEmpty()) invalidUpnpResponse()
            }
            while (true) {
                val trailer = framing.read(input)
                if (trailer.isEmpty()) break
                header(trailer)
            }
        }
        length != null -> copyExact(input, body, buffer, length)
        else -> {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > MAX_UPNP_XML_BYTES - body.size()) invalidUpnpResponse()
                body.write(buffer, 0, count)
            }
        }
    }
    return UpnpResponse(status, body.toByteArray())
}

private fun copyExact(input: InputStream, output: ByteArrayOutputStream, buffer: ByteArray, length: Int) {
    var remaining = length
    while (remaining > 0) {
        val count = input.read(buffer, 0, minOf(remaining, buffer.size))
        if (count < 0) invalidUpnpResponse()
        output.write(buffer, 0, count)
        remaining -= count
    }
}

private fun header(line: String): Pair<String, String> {
    val name = line.substringBefore(':')
    if (':' !in line || !headerName.matches(name)) invalidUpnpResponse()
    return name.lowercase() to line.substringAfter(':').trim()
}

private class LineBudget(private var remaining: Int) {
    fun read(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            when (val value = next(input)) {
                13 -> {
                    if (next(input) != 10) invalidUpnpResponse()
                    return line.toString()
                }
                9, in 32..126 -> line.append(value.toChar())
                else -> invalidUpnpResponse()
            }
        }
    }

    private fun next(input: InputStream): Int {
        if (remaining-- <= 0) invalidUpnpResponse()
        return input.read()
    }
}
