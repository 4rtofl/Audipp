package com.audipp.integrations.network.upnp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import com.audipp.integrations.network.NetworkFailure
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidUpnpNetwork(
    context: Context,
    private val network: Network,
    private val allowed: () -> Boolean,
) : UpnpNetwork {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    override fun rejection(): NetworkFailure? = when {
        !allowed() -> NetworkFailure.PERMISSION_REQUIRED
        connectivity?.getNetworkCapabilities(network)?.isUpnpLan() != true -> NetworkFailure.OFFLINE
        else -> null
    }

    override fun isLocal(address: InetAddress): Boolean {
        val links = connectivity?.getLinkProperties(network) ?: return false
        return links.linkAddresses.any { localSubnet(address, it.address, it.prefixLength) }
    }

    override suspend fun resolve(host: String): List<InetAddress> = withContext(Dispatchers.IO) {
        requireNetwork()
        mapSocketErrors { network.getAllByName(host).toList() }
    }

    override suspend fun search(requests: Set<String>, accept: (UpnpAdvertisement) -> Boolean) {
        cancellableUpnpIo({ MulticastSocket(null) }) { socket, active ->
            requireNetwork()
            val capabilities = connectivity?.getNetworkCapabilities(network)
                ?: throw UpnpException(NetworkFailure.OFFLINE)
            val links = connectivity.getLinkProperties(network) ?: throw UpnpException(NetworkFailure.OFFLINE)
            val address = links.linkAddresses.firstOrNull { it.address is Inet4Address }?.address
                ?: throw UpnpException(NetworkFailure.UNSUPPORTED)
            val lock = if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                wifi?.createMulticastLock("Audipp SSDP") ?: throw UpnpException(NetworkFailure.PERMISSION_REQUIRED)
            } else null
            try {
                lock?.acquire()
                mapSocketErrors {
                    network.bindSocket(socket)
                    socket.bind(InetSocketAddress(address, 0))
                    val lanInterface = links.interfaceName?.let(NetworkInterface::getByName)
                        ?: throw UpnpException(NetworkFailure.OFFLINE)
                    socket.networkInterface = lanInterface
                    socket.timeToLive = 1
                    val group = InetAddress.getByAddress(byteArrayOf(239.toByte(), 255.toByte(), 255.toByte(), 250.toByte()))
                    val stop = SystemClock.elapsedRealtime() + UPNP_SEARCH_MS
                    for (target in requests) {
                        if (!active()) return@mapSocketErrors
                        val query = ssdpQuery(target)
                        socket.send(DatagramPacket(query, query.size, group, SSDP_PORT))
                    }
                    val buffer = ByteArray(MAX_SSDP_PACKET + 1)
                    val packet = DatagramPacket(buffer, buffer.size)
                    while (active()) {
                        val remaining = stop - SystemClock.elapsedRealtime()
                        if (remaining <= 0) break
                        requireNetwork()
                        socket.soTimeout = minOf(remaining, 250L).toInt()
                        packet.length = buffer.size
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        if (packet.length > MAX_SSDP_PACKET || !isLocal(packet.address)) continue
                        val advertisement = parseSsdp(buffer.copyOf(packet.length), packet.address) ?: continue
                        if (!accept(advertisement)) break
                    }
                }
            } finally {
                if (lock?.isHeld == true) lock.release()
            }
        }
    }

    override suspend fun exchange(peer: UpnpPeer, uri: URI, post: UpnpPost?): UpnpResponse =
        cancellableUpnpIo({ Socket() }) { socket, _ ->
            requireNetwork()
            if (!isLocal(peer.address)) throw UpnpException(NetworkFailure.OFFLINE)
            val checked = peer.resolve(peer.location, uri.toString())
            mapSocketErrors {
                network.bindSocket(socket)
                upnpHttp(socket, peer, checked, post)
            }
        }

    private fun requireNetwork() {
        rejection()?.let { throw UpnpException(it) }
    }

    private inline fun <T> mapSocketErrors(block: () -> T): T {
        try {
            return block()
        } catch (error: IOException) {
            val permissionDenied = generateSequence<Throwable>(error) { it.cause }
                .any { it is ErrnoException && it.errno in setOf(OsConstants.EPERM, OsConstants.EACCES) }
            if (permissionDenied) throw UpnpException(NetworkFailure.PERMISSION_REQUIRED)
            rejection()?.let { throw UpnpException(it) }
            throw error
        }
    }
}

internal fun NetworkCapabilities.isUpnpLan(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
        (hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
