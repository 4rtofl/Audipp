package com.audipp.integrations.network.upnp

import java.net.InetAddress

internal const val UPNP_SEARCH_MS = 10_000L
internal const val SSDP_PORT = 1900
internal const val MAX_SSDP_PACKET = 8 * 1024
internal const val UPNP_DEVICE_PREFIX = "urn:schemas-upnp-org:device:"
internal val upnpSearchTargets = setOf(
    "${UPNP_DEVICE_PREFIX}MediaRenderer:1",
    "${UPNP_DEVICE_PREFIX}MediaServer:1",
    "urn:av-openhome-org:device:Renderer:1",
)

private val ssdpStatus = Regex("HTTP/1\\.[01] 200(?: .*)?")
private val ssdpHeader = Regex("[a-z0-9-]+")
private val relevantHeaders = setOf("st", "usn", "location", "cache-control")
private val cacheMaxAge = Regex("(?:^|,)\\s*max-age\\s*=\\s*([0-9]+)\\s*(?=,|$)", RegexOption.IGNORE_CASE)
private val udnPattern = Regex("uuid:[A-Za-z0-9-]{1,128}", RegexOption.IGNORE_CASE)
internal fun upnpUdn(value: String?): String? = value?.takeIf(udnPattern::matches)?.lowercase()

internal class UpnpAdvertisement(
    val udn: String,
    val target: String,
    val peer: UpnpPeer,
    val maxAgeSeconds: Long,
)

internal fun parseSsdp(packet: ByteArray, sender: InetAddress): UpnpAdvertisement? {
    if (packet.size > MAX_SSDP_PACKET || packet.any { byte ->
            val code = byte.toInt() and 0xff
            code !in 32..126 && code != 9 && code != 10 && code != 13
        }
    ) return null
    val value = packet.toString(Charsets.US_ASCII)
    if (!value.endsWith("\r\n\r\n")) return null
    val lines = value.dropLast(4).split("\r\n")
    if (!ssdpStatus.matches(lines.first())) return null
    val headers = mutableMapOf<String, String>()
    for (line in lines.drop(1)) {
        val key = line.substringBefore(':').lowercase()
        if (!ssdpHeader.matches(key) || ':' !in line) return null
        if (key in relevantHeaders &&
            headers.put(key, line.substringAfter(':').trim()) != null
        ) return null
    }
    val target = headers["st"]?.takeIf { it in upnpSearchTargets } ?: return null
    val usn = headers["usn"] ?: return null
    val udn = upnpUdn(usn.substringBefore("::")) ?: return null
    if (usn.substringAfter("::", "") != target) return null
    val location = headers["location"]?.let(::httpUri) ?: return null
    if (numericAddress(location.host) != sender) return null
    val maxAge = headers["cache-control"]?.let {
        cacheMaxAge.findAll(it).singleOrNull()?.groupValues?.get(1)?.toLongOrNull()
    }?.takeIf { it > 0 } ?: return null
    return UpnpAdvertisement(udn, target, UpnpPeer(location, sender), maxAge.coerceAtMost(1800))
}

internal fun ssdpQuery(target: String): ByteArray = (
    "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\nMX: 3\r\nST: $target\r\n\r\n"
    ).toByteArray(Charsets.US_ASCII)
