package com.audipp.integrations.network.smb

import com.audipp.integrations.network.NetworkEndpoint
import com.audipp.integrations.network.NetworkProtocol
import com.audipp.integrations.network.validNetworkText
import java.net.URI

internal class SmbEndpoint(endpoint: NetworkEndpoint) {
    private val uri = URI(endpoint.uri)
    val host = uri.host
    val port = if (uri.port == -1) 445 else uri.port
    val share: String
    val root: String

    init {
        require(endpoint.protocol == NetworkProtocol.SMB)
        val rawPath = uri.rawPath
        require(!rawPath.contains(Regex("%(?:2f|5c)", RegexOption.IGNORE_CASE)))
        val parts = uri.path.removePrefix("/").removeSuffix("/").split('/')
        require(parts.size <= 32 && parts.all(::validSmbName))
        require(parts.first().length <= 80)
        share = parts.first()
        root = parts.drop(1).joinToString("\\")
    }
}

internal fun validSmbName(name: String): Boolean =
    name.length <= 255 && validNetworkText(name) && !name.endsWith('.') && !name.endsWith(' ') &&
        !name.any { it in "/\\:*?\"<>|\uFFFD" }
