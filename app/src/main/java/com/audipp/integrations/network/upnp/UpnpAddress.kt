package com.audipp.integrations.network.upnp

import com.audipp.integrations.network.NetworkFailure
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

internal class UpnpException(val failure: NetworkFailure) : java.io.IOException()
internal fun invalidUpnpResponse(): Nothing = throw UpnpException(NetworkFailure.INVALID_RESPONSE)

internal fun httpUri(value: String): URI? {
    if (value.length !in 1..2048 || value.any { it.isISOControl() || it == '\\' }) return null
    val uri = try {
        URI(value)
    } catch (_: URISyntaxException) {
        return null
    }
    if (!uri.scheme.equals("http", ignoreCase = true) || uri.host.isNullOrBlank() ||
        uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery != null ||
        uri.host.contains('%') || (uri.port != -1 && uri.port !in 1..65535) ||
        uri.rawAuthority.endsWith(':') || uri.path.any { it.isISOControl() || it == '\\' }
    ) return null
    return uri
}

internal val URI.httpPort: Int get() = if (port == -1) 80 else port

internal class UpnpPeer(val location: URI, val address: InetAddress) {
    fun resolve(base: URI, reference: String): URI {
        val resolved = try {
            httpUri(base.resolve(reference).toString())
        } catch (_: IllegalArgumentException) {
            null
        } ?: invalidUpnpResponse()
        if (!location.host.equals(resolved.host, ignoreCase = true) || location.httpPort != resolved.httpPort) {
            invalidUpnpResponse()
        }
        return resolved
    }

    override fun toString() = "UpnpPeer(redacted)"
}

internal fun numericAddress(host: String): InetAddress? {
    val literal = host.removeSurrounding("[", "]")
    val parts = literal.split('.')
    if (parts.size == 4 && parts.all { it.length in 1..3 && it.all { char -> char in '0'..'9' } }) {
        val bytes = parts.map { it.toInt() }
        if (bytes.any { it > 255 }) return null
        return InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray())
    }
    if (':' !in literal || literal.any { it !in "0123456789abcdefABCDEF:." }) return null
    return try {
        InetAddress.getByName(literal)
    } catch (_: java.net.UnknownHostException) {
        null
    }
}
