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

private val ssdpStatus = Regex("HTTP/1\\.[01] 200(?: [ -~]*)?")
private val relevantHeaders = setOf("st", "usn", "location", "cache-control")
private val cacheMaxAge = Regex("(?:^|,)\\s*max-age\\s*=\\s*([0-9]+)\\s*(?=,|$)", RegexOption.IGNORE_CASE)
private val udnCharacters = Regex("[^\\p{Z}\\p{C}]+")
internal fun upnpUdn(value: String?): String? = value?.takeIf {
    it.startsWith("uuid:", ignoreCase = true) && it.length in 6..133 &&
        "::" !in it && udnCharacters.matches(it.substring(5))
}?.lowercase()

internal fun upnpTypeMatches(type: String?, target: String): Boolean {
    val separator = target.lastIndexOf(':')
    val prefix = target.substring(0, separator + 1)
    val version = type?.takeIf { it.startsWith(prefix) }?.substring(prefix.length) ?: return false
    return version.isNotEmpty() && version.all { it in '0'..'9' } &&
        (version.toIntOrNull() ?: return false) >= target.substring(separator + 1).toInt()
}

internal class UpnpAdvertisement(
    val udn: String,
    val target: String,
    val peer: UpnpPeer,
    val maxAgeSeconds: Long,
)

internal fun parseSsdp(packet: ByteArray, sender: InetAddress): UpnpAdvertisement? {
    if (packet.size > MAX_SSDP_PACKET) return null
    val value = packet.toString(Charsets.ISO_8859_1)
    if (!value.endsWith("\r\n\r\n")) return null
    val lines = value.dropLast(4).split("\r\n")
    if (!ssdpStatus.matches(lines.first())) return null
    val headers = mutableMapOf<String, String>()
    for (line in lines.drop(1)) {
        if (line.isEmpty()) return null
        val name = line.substringBefore(':').takeIf(headerName::matches) ?: continue
        val key = name.lowercase()
        if (key !in relevantHeaders) continue
        if (':' !in line) return null
        val field = line.substringAfter(':')
        if (field.any { it !in ' '..'~' && it != '\t' }) return null
        if (headers.put(key, field.trim()) != null) return null
    }
    val target = headers["st"]?.takeIf { type -> upnpSearchTargets.any { upnpTypeMatches(type, it) } } ?: return null
    val usn = headers["usn"] ?: return null
    val udn = upnpUdn(usn.substringBefore("::")) ?: return null
    if (usn.substringAfter("::", "") != target) return null
    val location = headers["location"]?.let { httpUri(it) } ?: return null
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
