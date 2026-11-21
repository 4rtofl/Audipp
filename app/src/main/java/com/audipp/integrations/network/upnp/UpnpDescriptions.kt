package com.audipp.integrations.network.upnp

import com.audipp.integrations.network.NetworkFailure
import java.net.URI

internal const val DEVICE_XML = "urn:schemas-upnp-org:device-1-0"
internal const val SERVICE_XML = "urn:schemas-upnp-org:service-1-0"
internal const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
internal const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
internal const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"
internal const val OPENHOME_PLAYLIST = "urn:av-openhome-org:service:Playlist:1"
internal const val OPENHOME_VOLUME = "urn:av-openhome-org:service:Volume:1"
internal const val MEDIA_SERVER = "${UPNP_DEVICE_PREFIX}MediaServer:1"

private val services = setOf(AV_TRANSPORT, RENDERING_CONTROL, CONTENT_DIRECTORY, OPENHOME_PLAYLIST, OPENHOME_VOLUME)
internal val xmlName = Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}")

internal class UpnpServiceLocation(val type: String, val description: URI, val control: URI) {
    override fun toString() = "UpnpServiceLocation(redacted)"
}

internal class UpnpDescription(
    val udn: String,
    val type: String,
    val name: String,
    val services: Map<String, UpnpServiceLocation>,
) {
    override fun toString() = "UpnpDescription(redacted)"
}

internal fun deviceDescription(
    root: UpnpXml,
    peer: UpnpPeer,
    expected: UpnpCandidate?,
    targets: Set<String> = upnpSearchTargets,
): UpnpDescription {
    if (root.name != "root" || root.namespace != DEVICE_XML) invalidUpnpResponse()
    val top = root.element("device") ?: invalidUpnpResponse()
    val device = if (expected != null) {
        devices(top).filter { upnpUdn(it.value("UDN")) == expected.udn }.singleOrNull()
            ?: invalidUpnpResponse()
    } else {
        devices(top).firstOrNull { node -> targets.any { upnpTypeMatches(node.value("deviceType"), it) } }
            ?: if (devices(top).any { node -> upnpSearchTargets.any { upnpTypeMatches(node.value("deviceType"), it) } }) {
                throw UpnpException(NetworkFailure.UNSUPPORTED)
            } else invalidUpnpResponse()
    }
    val type = device.value("deviceType") ?: invalidUpnpResponse()
    if (expected != null && !upnpTypeMatches(type, expected.type)) invalidUpnpResponse()
    val udn = upnpUdn(device.value("UDN")) ?: invalidUpnpResponse()
    val name = device.value("friendlyName")?.takeIf {
        it.length in 1..256 && it.none(Char::isISOControl)
    } ?: invalidUpnpResponse()
    val base = root.value("URLBase")?.takeIf(String::isNotEmpty)
        ?.let { httpUri(it)?.let { uri -> peer.resolve(peer.location, uri.toString()) } ?: invalidUpnpResponse() }
        ?: peer.location
    val locations = mutableMapOf<String, UpnpServiceLocation>()
    for (service in device.element("serviceList")?.elements("service").orEmpty()) {
        val serviceType = service.value("serviceType")?.takeIf { it in services } ?: continue
        val description = service.value("SCPDURL")?.takeIf(String::isNotEmpty) ?: invalidUpnpResponse()
        val control = service.value("controlURL")?.takeIf(String::isNotEmpty) ?: invalidUpnpResponse()
        val location = UpnpServiceLocation(
            serviceType, peer.resolve(base, description), peer.resolve(base, control, allowQuery = true),
        )
        if (locations.put(serviceType, location) != null) invalidUpnpResponse()
    }
    return UpnpDescription(udn, type, name, locations)
}

private fun devices(node: UpnpXml): Sequence<UpnpXml> = sequence {
    yield(node)
    node.element("deviceList")?.elements("device")?.forEach { yieldAll(devices(it)) }
}

internal class UpnpAction(val input: Set<String>, val output: Set<String>)

internal fun serviceActions(root: UpnpXml): Map<String, UpnpAction> {
    if (root.name != "scpd" || root.namespace != SERVICE_XML) invalidUpnpResponse()
    val actions = mutableMapOf<String, UpnpAction>()
    for (action in root.element("actionList")?.elements("action").orEmpty()) {
        val name = action.value("name")?.takeIf(xmlName::matches) ?: invalidUpnpResponse()
        val arguments = mutableMapOf<String, String>()
        for (argument in action.element("argumentList")?.elements("argument").orEmpty()) {
            val argumentName = argument.value("name")?.takeIf(xmlName::matches) ?: invalidUpnpResponse()
            val direction = argument.value("direction")?.takeIf { it == "in" || it == "out" } ?: invalidUpnpResponse()
            if (arguments.put(argumentName, direction) != null) invalidUpnpResponse()
        }
        val input = arguments.filterValues { it == "in" }.keys
        val output = arguments.filterValues { it == "out" }.keys
        if (actions.put(name, UpnpAction(input, output)) != null) invalidUpnpResponse()
    }
    return actions
}
