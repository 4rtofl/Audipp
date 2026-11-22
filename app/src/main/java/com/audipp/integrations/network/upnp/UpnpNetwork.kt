package com.audipp.integrations.network.upnp

import com.audipp.integrations.network.NetworkFailure
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal interface UpnpNetwork {
    fun rejection(): NetworkFailure?
    fun isLocal(address: InetAddress): Boolean
    suspend fun resolve(host: String): List<InetAddress>
    suspend fun search(requests: Set<String>, accept: (UpnpAdvertisement) -> Boolean)
    suspend fun exchange(peer: UpnpPeer, uri: URI, post: UpnpPost? = null): UpnpResponse
}

internal fun localSubnet(address: InetAddress, local: InetAddress, prefix: Int): Boolean {
    val bytes = address.address
    val localBytes = local.address
    if (bytes.size != localBytes.size || prefix !in 1..(bytes.size * 8) || address == local ||
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress
    ) return false
    val private = if (bytes.size == 4) {
        address.isSiteLocalAddress || address.isLinkLocalAddress
    } else {
        bytes[0].toInt() and 0xfe == 0xfc
    }
    if (!private) return false
    for (index in bytes.indices) {
        val bits = (prefix - index * 8).coerceIn(0, 8)
        val mask = (0xff shl (8 - bits)) and 0xff
        if ((bytes[index].toInt() xor localBytes[index].toInt()) and mask != 0) return false
    }
    if (bytes.size != 4 || prefix >= 31) return true
    val hostBits = (prefix until 32).map { bit ->
        (bytes[bit / 8].toInt() and (0x80 shr (bit % 8))) != 0
    }
    return hostBits.any { it } && hostBits.any { !it }
}

internal suspend fun <R : Closeable, T> cancellableUpnpIo(
    resource: () -> R,
    operation: (R, () -> Boolean) -> T,
): T = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { continuation ->
        try {
            val result = resource().use { socket ->
                continuation.invokeOnCancellation { closeQuietly(socket) }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                operation(socket) { continuation.isActive }
            }
            continuation.resume(result)
        } catch (failure: SocketTimeoutException) {
            if (continuation.isActive) continuation.resumeWithException(UpnpException(NetworkFailure.TIMEOUT))
        } catch (failure: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }
}

private fun closeQuietly(resource: Closeable) {
    try {
        resource.close()
    } catch (_: IOException) {
        // The cancelled operation no longer owns the socket.
    }
}
