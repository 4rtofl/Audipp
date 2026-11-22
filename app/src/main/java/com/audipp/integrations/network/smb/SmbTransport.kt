package com.audipp.integrations.network.smb

import com.audipp.integrations.network.NetworkCallPolicy
import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.NetworkResult
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb.SMB1NotSupportedException
import com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN
import com.hierynomus.mssmb2.SMB2CreateOptions.FILE_OPEN_REPARSE_POINT
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2MessageFlag
import com.hierynomus.mssmb2.SMB2PacketData
import com.hierynomus.mssmb2.SMB2PacketHeader
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.protocol.transport.PacketHandlers
import com.hierynomus.protocol.transport.PacketReceiver
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.protocol.transport.TransportLayer
import com.hierynomus.security.SecurityProvider
import com.hierynomus.smb.SMBPacket
import com.hierynomus.smb.SMBPacketData
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.paths.PathResolver
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.hierynomus.smbj.transport.TransportLayerFactory
import com.hierynomus.smbj.transport.tcp.direct.DirectTcpTransportFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal val reparseAttribute = FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value

internal class SmbEntry(val name: String, val attributes: Long) {
    val isDirectory get() = attributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
    val isReparsePoint get() = attributes and reparseAttribute != 0L
}

internal data class SmbIdentity(
    val server: UUID,
    val index: Long,
    val written: Long,
    val changed: Long,
    val size: Long,
) {
    val usable get() = server != UUID(0, 0) && index != 0L && written > 0 && changed > 0 && size >= 0
}

internal interface SmbFile {
    fun identity(): SmbIdentity
    fun read(buffer: ByteArray, offset: Long, start: Int, length: Int): Int
}

internal interface SmbShare {
    fun <T> list(path: String, visit: (Sequence<SmbEntry>) -> T): T
    fun <T> open(path: String, read: (SmbFile) -> T): T
}

internal interface SmbTransport {
    suspend fun <T> exchange(secret: ByteArray, action: (SmbShare) -> NetworkResult<T>): NetworkResult<T>
}

internal class SmbRejected(val reason: NetworkFailure) : RuntimeException(reason.name)

internal class SmbjTransport(
    private val endpoint: SmbEndpoint,
    private val policy: NetworkCallPolicy,
    private val isLanReadAllowed: () -> Boolean,
) : SmbTransport {
    override suspend fun <T> exchange(
        secret: ByteArray,
        action: (SmbShare) -> NetworkResult<T>,
    ): NetworkResult<T> = coroutineScope {
        suspendCancellableCoroutine { continuation ->
            val socket = Socket(Proxy.NO_PROXY)
            continuation.invokeOnCancellation { closeSocket(socket) }
            launch(Dispatchers.IO) {
                val result = runCatching {
                    socket.use {
                        if (!isLanReadAllowed()) throw SmbRejected(NetworkFailure.PERMISSION_REQUIRED)
                        withShare(socket, secret, action)
                    }
                }
                continuation.resumeWith(result.fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { error ->
                        smbFailure(error)?.let { Result.success(NetworkResult.Failure(it)) } ?: Result.failure(error)
                    },
                ))
            }
        }
    }

    private fun <T> withShare(
        socket: Socket,
        secret: ByteArray,
        action: (SmbShare) -> NetworkResult<T>,
    ): NetworkResult<T> {
        val auth = try {
            secret.toSmbAuthentication()
        } catch (_: IllegalArgumentException) {
            throw SmbRejected(NetworkFailure.INVALID_INPUT)
        } catch (_: CharacterCodingException) {
            throw SmbRejected(NetworkFailure.INVALID_INPUT)
        }
        try {
            val factory = object : ProxySocketFactory() {
                override fun createSocket(host: String, port: Int): Socket {
                    if (port != endpoint.port || socket.isConnected) {
                        throw SmbRejected(NetworkFailure.PERMISSION_REQUIRED)
                    }
                    if (!isLanReadAllowed()) throw SmbRejected(NetworkFailure.PERMISSION_REQUIRED)
                    // SMBJ normalizes IPv6 literals before invoking this factory.
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.port), policy.connectTimeoutMs.toInt())
                    return socket
                }
            }
            val responses = SignedResponses(socket)
            return SMBClient(smbConfig(policy).withSocketFactory(factory).withTransportLayerFactory(responses).build()).use { client ->
                val connection = client.connect(endpoint.host, endpoint.port)
                try {
                    if (!isLanReadAllowed()) throw SmbRejected(NetworkFailure.PERMISSION_REQUIRED)
                    val session = connection.authenticate(auth)
                    auth.password.fill('\u0000')
                    if (!session.isSigningRequired && !session.sessionContext.isEncryptData) {
                        throw SmbRejected(NetworkFailure.SIGNATURE_REJECTED)
                    }
                    responses.session = session
                    val share = session.connectShare(endpoint.share)
                    if (share !is DiskShare) throw SmbRejected(NetworkFailure.UNSUPPORTED)
                    // SMBJ follows symlink responses by default even when DFS is off.
                    val direct = DiskShare(share.smbPath, share.treeConnect, PathResolver.LOCAL)
                    val result = action(SmbjShare(direct, connection.connectionContext.serverGuid))
                    if (!isLanReadAllowed()) throw SmbRejected(NetworkFailure.PERMISSION_REQUIRED)
                    result
                } finally {
                    // Each attempt owns its socket. Failed or cancelled calls cannot leave a session for later reads.
                    try {
                        connection.close(true)
                    } catch (_: IOException) {
                        closeSocket(socket)
                    }
                }
            }
        } finally {
            auth.password.fill('\u0000')
        }
    }
}

internal fun smbConfig(policy: NetworkCallPolicy): SmbConfig.Builder = SmbConfig.builder()
    .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
    .withMultiProtocolNegotiate(false)
    .withDfsEnabled(false)
    .withSigningRequired(true)
    .withAuthenticators(NtlmAuthenticator.Factory())
    .withBufferSize(64 * 1024)
    .withTimeout(policy.operationTimeoutMs, MILLISECONDS)
    .withSoTimeout(policy.operationTimeoutMs, MILLISECONDS)

/**
 * SMBJ 0.13.0 through 0.15.0 hand plain responses whose SessionId is 0 or unknown, and signed responses with SessionId 0,
 * to the waiting request without verifying them, and drop a failed signature so the caller only sees a timeout.
 * The upstream fix (hierynomus/smbj#911) is unmerged. Once the session exists, this receiver runs before SMBJ's
 * handlers and ends the attempt unless each plain response is signed by that session.
 */
private class SignedResponses(private val socket: Socket) : TransportLayerFactory<SMBPacketData<*>, SMBPacket<*, *>> {
    @Volatile var session: Session? = null

    override fun createTransportLayer(
        handlers: PacketHandlers<SMBPacketData<*>, SMBPacket<*, *>>,
        config: SmbConfig,
    ): TransportLayer<SMBPacket<*, *>> {
        val receiver = object : PacketReceiver<SMBPacketData<*>> {
            override fun handle(packet: SMBPacketData<*>) {
                val session = session
                // Encrypted frames reach SMBJ as a different type and are authenticated by decrypting with the session key.
                if (session != null && packet is SMB2PacketData && !packet.isSignedBy(session, config.securityProvider)) {
                    // Closing first keeps SMBJ's error handling from waiting on a LOGOFF the stopped reader cannot receive.
                    closeSocket(socket)
                    throw TransportException(SmbRejected(NetworkFailure.SIGNATURE_REJECTED))
                }
                handlers.receiver.handle(packet)
            }

            override fun handleError(error: Throwable) = handlers.receiver.handleError(error)
        }
        return DirectTcpTransportFactory<SMBPacketData<*>, SMBPacket<*, *>>()
            .createTransportLayer(PacketHandlers(handlers.serializer, receiver, handlers.packetFactory), config)
    }
}

private fun SMB2PacketData.isSignedBy(session: Session, provider: SecurityProvider): Boolean {
    val header = header
    // SMBJ never sends compounded requests, and it verifies later compound members over the wrong bytes.
    if (header.nextCommandOffset != 0) return false
    // [MS-SMB2] 3.2.5.1.3 exempts interim responses and break notifications; SMBJ completes no request with them.
    if (header.messageId == -1L || isIntermediateAsyncResponse) return true
    if (!header.isFlagSet(SMB2MessageFlag.SMB2_FLAGS_SIGNED) || header.sessionId != session.sessionId) return false
    val key = session.getSigningKey(header, false) ?: return false
    val bytes = dataBuffer.array()
    val mac = provider.getMac(key.algorithm).apply { init(key.encoded) }
    mac.update(bytes, 0, SMB2PacketHeader.SIGNATURE_OFFSET)
    mac.update(SMB2PacketHeader.EMPTY_SIGNATURE)
    mac.update(bytes, SMB2PacketHeader.STRUCTURE_SIZE, header.messageEndPosition - SMB2PacketHeader.STRUCTURE_SIZE)
    return MessageDigest.isEqual(mac.doFinal().copyOf(SMB2PacketHeader.SIGNATURE_SIZE), header.signature)
}

private fun closeSocket(socket: Socket) {
    try {
        socket.close()
    } catch (_: IOException) {
        // Cancellation and failures retain their original result.
    }
}

private class SmbjShare(private val share: DiskShare, private val server: UUID) : SmbShare {
    override fun <T> list(path: String, visit: (Sequence<SmbEntry>) -> T): T =
        share.openDirectory(path, setOf(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES), null,
            SMB2ShareAccess.ALL, FILE_OPEN, setOf(FILE_OPEN_REPARSE_POINT)).use { directory ->
            rejectReparse(directory.getFileInformation(FileBasicInformation::class.java).fileAttributes)
            visit(directory.iterator().asSequence().map { SmbEntry(it.fileName, it.fileAttributes) })
        }

    override fun <T> open(path: String, read: (SmbFile) -> T): T =
        share.openFile(path, setOf(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES), null,
            setOf(SMB2ShareAccess.FILE_SHARE_READ), FILE_OPEN, setOf(FILE_OPEN_REPARSE_POINT)).use { file ->
            read(SmbjFile(file, server))
        }
}

private class SmbjFile(private val file: File, private val server: UUID) : SmbFile {
    override fun identity(): SmbIdentity {
        val info = file.fileInformation
        val basic = info.basicInformation
        val standard = info.standardInformation
        rejectReparse(basic.fileAttributes)
        if (standard.isDeletePending || standard.isDirectory) throw SmbRejected(NetworkFailure.STALE_RESPONSE)
        // Samba can synthesize creation time using access time on filesystems without birth time.
        return SmbIdentity(server, info.internalInformation.indexNumber, basic.lastWriteTime.windowsTimeStamp,
            basic.changeTime.windowsTimeStamp, standard.endOfFile)
    }

    override fun read(buffer: ByteArray, offset: Long, start: Int, length: Int): Int =
        file.read(buffer, offset, start, length)
}

private fun rejectReparse(attributes: Long) {
    if (attributes and reparseAttribute != 0L) throw SmbRejected(NetworkFailure.UNSUPPORTED)
}

internal fun smbFailure(error: Throwable): NetworkFailure? {
    for (cause in generateSequence(error) { it.cause?.takeUnless { next -> next === it } }.take(16)) {
        when (cause) {
            is SmbRejected -> return cause.reason
            is SMB1NotSupportedException -> return NetworkFailure.UNSUPPORTED
            is SMB2GuestSigningRequiredException -> return NetworkFailure.SIGNATURE_REJECTED
            is SMBApiException -> return when {
                cause.statusCode == 0xC000A000L -> NetworkFailure.SIGNATURE_REJECTED // STATUS_INVALID_SIGNATURE
                else -> smbStatus(cause.status)
            }
            is SocketTimeoutException, is TimeoutException -> return NetworkFailure.TIMEOUT
            is NoRouteToHostException, is UnknownHostException -> return NetworkFailure.OFFLINE
            is SecurityException -> return NetworkFailure.PERMISSION_REQUIRED
        }
    }
    return when (error) {
        is IOException, is SMBRuntimeException -> NetworkFailure.IO
        is CancellationException -> null
        is RuntimeException -> NetworkFailure.INVALID_RESPONSE
        else -> null
    }
}

private fun smbStatus(status: NtStatus): NetworkFailure = when (status) {
    NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_PASSWORD_EXPIRED, NtStatus.STATUS_ACCOUNT_DISABLED,
    NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED -> NetworkFailure.AUTH_REQUIRED
    NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_SHARING_VIOLATION -> NetworkFailure.PERMISSION_REQUIRED
    NtStatus.STATUS_IO_TIMEOUT -> NetworkFailure.TIMEOUT
    NtStatus.STATUS_NETWORK_NAME_DELETED, NtStatus.STATUS_USER_SESSION_DELETED,
    NtStatus.STATUS_NETWORK_SESSION_EXPIRED, NtStatus.STATUS_FILE_CLOSED -> NetworkFailure.IO
    NtStatus.STATUS_NOT_SUPPORTED, NtStatus.STATUS_NOT_IMPLEMENTED, NtStatus.STATUS_PATH_NOT_COVERED,
    NtStatus.STATUS_STOPPED_ON_SYMLINK, NtStatus.STATUS_IO_REPARSE_TAG_NOT_HANDLED -> NetworkFailure.UNSUPPORTED
    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_NO_SUCH_FILE,
    NtStatus.STATUS_FILE_DELETED, NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_NOT_A_DIRECTORY,
    NtStatus.STATUS_FILE_IS_A_DIRECTORY -> NetworkFailure.STALE_RESPONSE
    else -> NetworkFailure.INVALID_RESPONSE
}
