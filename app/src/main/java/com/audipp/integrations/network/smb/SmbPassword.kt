package com.audipp.integrations.network.smb

import com.audipp.integrations.network.validNetworkText
import com.hierynomus.smbj.auth.AuthenticationContext
import java.nio.ByteBuffer
import java.nio.CharBuffer

/** Encodes the credential for both SMB verification and [com.audipp.integrations.network.NetworkCredentials]. */
object SmbPassword {
    fun encode(username: String, password: CharArray, domain: String = ""): ByteArray {
        require(validNetworkText(username) && (domain.isEmpty() || validNetworkText(domain)))
        require(password.size in 1..1024 && '\u0000' !in password)
        val prefix = "$username\u0000$domain\u0000".toByteArray(Charsets.UTF_8)
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        try {
            return ByteArray(prefix.size + encoded.remaining()).also {
                prefix.copyInto(it)
                encoded.get(it, prefix.size, encoded.remaining())
            }
        } finally {
            encoded.clear()
            while (encoded.hasRemaining()) encoded.put(0)
        }
    }
}

internal fun ByteArray.toSmbAuthentication(): AuthenticationContext {
    val userEnd = indexOf(0)
    val domainEnd = (userEnd + 1 until size).firstOrNull { this[it] == 0.toByte() } ?: -1
    require(userEnd > 0 && domainEnd > userEnd && domainEnd < lastIndex)
    val decoder = Charsets.UTF_8.newDecoder()
    val username = decoder.decode(ByteBuffer.wrap(this, 0, userEnd)).toString()
    val domain = decoder.decode(ByteBuffer.wrap(this, userEnd + 1, domainEnd - userEnd - 1)).toString()
    require(validNetworkText(username) && (domain.isEmpty() || validNetworkText(domain)))
    val decoded = decoder.decode(ByteBuffer.wrap(this, domainEnd + 1, size - domainEnd - 1))
    try {
        val password = CharArray(decoded.remaining()).also { decoded.get(it) }
        try {
            require(password.size in 1..1024 && '\u0000' !in password)
            return AuthenticationContext(username, password, domain)
        } finally {
            password.fill('\u0000')
        }
    } finally {
        decoded.clear()
        while (decoded.hasRemaining()) decoded.put('\u0000')
    }
}
