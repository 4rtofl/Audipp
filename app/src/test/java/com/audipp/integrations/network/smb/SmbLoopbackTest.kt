package com.audipp.integrations.network.smb

import com.audipp.integrations.network.DirectoryRequest
import com.audipp.integrations.network.NetworkCallPolicy
import com.audipp.integrations.network.NetworkCalls
import com.audipp.integrations.network.NetworkEndpoint
import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.NetworkProtocol
import com.audipp.integrations.network.NetworkResult
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceDescriptor
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.ntlm.functions.NtlmFunctions
import com.hierynomus.ntlm.functions.NtlmV2Functions
import com.hierynomus.protocol.commons.buffer.Buffer
import com.hierynomus.protocol.commons.buffer.Endian
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.spnego.NegTokenTarg
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.security.SecureRandom
import java.util.concurrent.TimeUnit.SECONDS
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SmbLoopbackTest {
    @get:Rule val temporary = TemporaryFolder()
    private val password = SmbPassword.encode("root", "audipp-fixture-test".toCharArray(), "WORKGROUP")

    @Test fun sendsSmb2NegotiateAndClosesSocketOnOperationTimeout() = runBlocking {
        assertTimeoutAfterNegotiate(listener(), "127.0.0.1")
    }

    @Test fun connectsToTheSelectedIpv6Literal() = runBlocking {
        val server = try {
            listener("::1")
        } catch (error: SocketException) {
            assumeNoException(error)
            return@runBlocking
        }
        assertTimeoutAfterNegotiate(server, "[::1]")
    }

    @Test fun cancellationClosesTheSocketWithoutWaitingForItsReadTimeout() = runBlocking {
        listener().use { server ->
            val library = SmbLibrary(endpoint(server.localPort, "music"), { true })
            val request = async { library.verifyCredential(password) }
            val peer = withContext(Dispatchers.IO) { server.accept() }
            peer.use {
                peer.soTimeout = 3_000
                val input = DataInputStream(peer.getInputStream())
                withContext(Dispatchers.IO) { assertSmb2Negotiate(input) }
                request.cancelAndJoin()
                assertEquals(-1, withContext(Dispatchers.IO) { input.read() })
            }
        }
    }

    @Test fun authenticationSigningAndAccessDeniedAreDistinct() {
        val statuses = mapOf(
            NtStatus.STATUS_LOGON_FAILURE.value to NetworkFailure.AUTH_REQUIRED,
            0xC000A000L to NetworkFailure.SIGNATURE_REJECTED,
            NtStatus.STATUS_ACCESS_DENIED.value to NetworkFailure.PERMISSION_REQUIRED,
        )
        for ((status, failure) in statuses) {
            assertEquals(failure, smbFailure(SMBApiException(status, SMB2MessageCommandCode.SMB2_SESSION_SETUP, null)))
        }
        val config = smbConfig(NetworkCallPolicy()).build()
        assertTrue(config.isSigningRequired)
        assertTrue(config.securityProvider is BCSecurityProvider)
    }

    @Test fun responsesNotSignedByTheAuthenticatedSessionFailWithoutRetrying() = runBlocking {
        val forgeries = mapOf<String, (ByteArray, ByteArray) -> ByteArray>(
            "unsigned without a session" to { request, _ -> reply(request, created(), tree = TREE) },
            "unsigned for another session" to { request, _ -> reply(request, created(), session = SESSION + 1, tree = TREE) },
            "flagged as signed without a session" to { request, _ ->
                reply(request, created(), tree = TREE).also { it[16] = (it[16].toInt() or SIGNED).toByte() }
            },
            "altered after signing" to { request, key ->
                signed(reply(request, created(), session = SESSION, tree = TREE), key).also { it[72]++ }
            },
        )
        val outcomes = forgeries.mapValues { (_, forge) -> exchangeForgedCreate(forge) }
        val rejected = ForgeryOutcome(NetworkResult.Failure(NetworkFailure.SIGNATURE_REJECTED), emptyList(), retried = false)
        assertEquals(forgeries.mapValues { rejected }, outcomes)
    }

    @Test fun sambaFixtureListsSeeksAndDistinguishesWrongPasswordFromShareDenial() = runBlocking {
        val ports = System.getenv("AUDIPP_SMB_FIXTURE_PORTS").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
        assumeTrue("Optional signed Samba fixture is not running", ports.isNotEmpty())
        for (port in ports) {
            val endpoint = endpoint(port, "music")
            val verification = SmbLibrary(endpoint, { true })
            val wrong = SmbPassword.encode("root", "Wrong-fixture-password".toCharArray(), "WORKGROUP")
            assertEquals(NetworkResult.Failure(NetworkFailure.AUTH_REQUIRED), verification.verifyCredential(wrong))
            val denied = SmbLibrary(endpoint(port, "denied"), { true })
            assertEquals(NetworkResult.Failure(NetworkFailure.PERMISSION_REQUIRED), denied.verifyCredential(password))
            success(verification.verifyCredential(password))

            val store = credentials(temporary.newFolder())
            val token = store.authorize(endpoint, "fixture", password)
            val library = SmbLibrary(endpoint, { true }, credentials = store, credential = token)
            val albums = success(library.client.listDirectory(DirectoryRequest(library.rootDirectoryId)))
            val album = albums.single { it.name == "album" }
            val item = success(library.client.listMedia(DirectoryRequest(album.id))).single()
            assertEquals("track.wav", item.name)
            val resource = success(library.client.resolveStream(ResolutionRequest(item.variant, smbDeviceContext)))
                .descriptor as SourceDescriptor.DeviceResource
            assertArrayEquals("RIFF".toByteArray(), success(library.readRange(resource, 0, 4)))
            assertArrayEquals("klmn".toByteArray(), success(library.readRange(resource, 64, 4)))
            val samples = "0123456789abcdefghijklmnopqrstuvwxyz".repeat(2_048).toByteArray()
            assertArrayEquals(samples, success(library.readRange(resource, 44, samples.size + 4)))
            assertArrayEquals("z".toByteArray(), success(library.readRange(resource, 44L + samples.size - 1, 4)))
        }
    }

    private suspend fun assertTimeoutAfterNegotiate(listener: ServerSocket, host: String) = coroutineScope {
        listener.use { server ->
            val calls = NetworkCalls(NetworkCallPolicy(operationTimeoutMs = 1_000))
            val library = SmbLibrary(NetworkEndpoint(NetworkProtocol.SMB, "smb://$host:${server.localPort}/music"), { true }, calls)
            val request = async { library.verifyCredential(password) }
            withContext(Dispatchers.IO) {
                server.accept().use { peer ->
                    peer.soTimeout = 5_000
                    val input = DataInputStream(peer.getInputStream())
                    assertSmb2Negotiate(input)
                    assertEquals(-1, input.read())
                }
            }
            assertEquals(NetworkResult.Failure(NetworkFailure.TIMEOUT), request.await())
        }
    }

    private suspend fun exchangeForgedCreate(forge: (ByteArray, ByteArray) -> ByteArray) = coroutineScope {
        listener().use { server ->
            val endpoint = endpoint(server.localPort, "music")
            val store = credentials(temporary.newFolder())
            val calls = NetworkCalls(NetworkCallPolicy(operationTimeoutMs = 3_000, initialBackoffMs = 1, maxBackoffMs = 1))
            val library = SmbLibrary(endpoint, { true }, calls, store, store.authorize(endpoint, "fixture", password))
            val result = async { library.client.listDirectory(DirectoryRequest(library.rootDirectoryId)) }
            val followUps = withContext(Dispatchers.IO) { server.accept().use { answerUntilForgedCreate(it, forge) } }
            val outcome = result.await()
            server.soTimeout = 100
            val retried = try {
                server.accept().close()
                true
            } catch (_: SocketTimeoutException) {
                false
            }
            ForgeryOutcome(outcome, followUps, retried)
        }
    }

    /**
     * Completes SMB 2.1 NTLM authentication and a TREE_CONNECT signed with the client's session key, then answers
     * CREATE with [forge]. Returns the commands the client sent after accepting the forged answer.
     */
    private fun answerUntilForgedCreate(peer: Socket, forge: (ByteArray, ByteArray) -> ByteArray): List<Int> {
        peer.soTimeout = 10_000
        val input = DataInputStream(peer.getInputStream())
        fun send(message: ByteArray) = peer.getOutputStream().write(ByteBuffer.allocate(4).putInt(message.size).array() + message)
        var key = ByteArray(0)
        var forged = false
        val followUps = mutableListOf<Int>()
        while (true) {
            val request = try {
                ByteArray(input.readInt()).also(input::readFully)
            } catch (_: EOFException) {
                return followUps
            } catch (_: SocketException) {
                return followUps
            }
            val fields = ByteBuffer.wrap(request).order(LITTLE_ENDIAN)
            val command = fields.getShort(12).toInt()
            if (forged) {
                followUps += command
                continue
            }
            when (command) {
                0 -> send(reply(request, negotiated()))
                1 -> if (fields.getLong(40) == 0L) {
                    send(reply(request, sessionSetup(spnego(ntlmChallenge())), MORE_PROCESSING_REQUIRED, SESSION))
                } else {
                    val token = request.copyOfRange(fields.getShort(76).toInt(), fields.getShort(76) + fields.getShort(78))
                    key = sessionKey(NegTokenTarg().read(token).responseToken)
                    send(signed(reply(request, sessionSetup(ByteArray(0)), session = SESSION), key))
                }
                3 -> send(signed(reply(request, treeConnected(), session = SESSION, tree = TREE), key))
                5 -> {
                    send(forge(request, key))
                    forged = true
                }
                else -> error("Unexpected SMB2 command $command")
            }
        }
    }

    private fun reply(request: ByteArray, body: ByteArray, status: Int = 0, session: Long = 0, tree: Int = 0): ByteArray {
        val header = request.copyOf(64)
        ByteBuffer.wrap(header).order(LITTLE_ENDIAN).putInt(8, status).putShort(14, 64).putInt(16, SERVER_TO_REDIR)
            .putInt(20, 0).putInt(32, 0).putInt(36, tree).putLong(40, session)
        header.fill(0, 48, 64)
        return header + body
    }

    private fun signed(message: ByteArray, key: ByteArray): ByteArray {
        message[16] = (message[16].toInt() or SIGNED).toByte()
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)
            .copyInto(message, 48, 0, 16)
        return message
    }

    private fun sessionKey(authenticate: ByteArray): ByteArray {
        val fields = ByteBuffer.wrap(authenticate).order(LITTLE_ENDIAN)
        fun field(at: Int) = fields.getInt(at + 4).let { authenticate.copyOfRange(it, it + fields.getShort(at)) }
        val provider = BCSecurityProvider()
        val ntlm = NtlmV2Functions(SecureRandom(), provider)
        val baseKey = ntlm.getSessionBaseKey(ntlm.NTOWFv2("audipp-fixture-test", "root", "WORKGROUP"), field(20).copyOf(16))
        return NtlmFunctions.rc4k(provider, baseKey, field(52))
    }

    private fun negotiated() = ByteBuffer.allocate(64).order(LITTLE_ENDIAN)
        .putShort(65).putShort(3).putShort(0x0210).putShort(0) // signing required, SMB 2.1
        .put(ByteArray(16) { 7 }).putInt(0).putInt(65_536).putInt(65_536).putInt(65_536)
        .putLong(0).putLong(0).putShort(128).putShort(0).putInt(0)
        .array()

    private fun ntlmChallenge() = ByteBuffer.allocate(60).order(LITTLE_ENDIAN)
        .put("NTLMSSP\u0000".toByteArray()).putInt(2).putShort(0).putShort(0).putInt(56)
        .putInt(0x60888215) // unicode, target, sign, NTLM, always sign, ESS, target info, 128-bit, key exchange
        .put(ByteArray(8) { it.toByte() }).putLong(0).putShort(4).putShort(4).putInt(56).putLong(0).putInt(0)
        .array()

    private fun spnego(token: ByteArray) =
        Buffer.PlainBuffer(Endian.LE).also { NegTokenTarg().apply { responseToken = token }.write(it) }.compactData

    private fun sessionSetup(token: ByteArray) = ByteBuffer.allocate(8 + token.size).order(LITTLE_ENDIAN)
        .putShort(9).putShort(0).putShort(72).putShort(token.size.toShort()).put(token).array()

    private fun treeConnected() = ByteBuffer.allocate(16).order(LITTLE_ENDIAN)
        .putShort(16).put(1).put(0).putInt(0).putInt(0).putInt(0x001F01FF).array()

    private fun created() = ByteBuffer.allocate(88).order(LITTLE_ENDIAN)
        .putShort(89).put(0).put(0).putInt(1).putLong(1).putLong(1).putLong(1).putLong(1).putLong(0).putLong(0)
        .putInt(0x10).putInt(0).putLong(1).putLong(1).putInt(0).putInt(0)
        .array()

    private fun listener(host: String = "127.0.0.1") = ServerSocket(0, 1, InetAddress.getByName(host)).apply {
        soTimeout = SECONDS.toMillis(5).toInt()
    }

    private fun endpoint(port: Int, share: String) =
        NetworkEndpoint(NetworkProtocol.SMB, "smb://127.0.0.1:$port/$share")

    private fun assertSmb2Negotiate(input: DataInputStream) {
        val tcpHeader = input.readInt()
        val length = tcpHeader and 0x00ffffff
        check(tcpHeader ushr 24 == 0 && length in 66..4096)
        val packet = ByteArray(length).also(input::readFully)
        assertArrayEquals(byteArrayOf(0xFE.toByte(), 0x53, 0x4d, 0x42), packet.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0, 0), packet.copyOfRange(12, 14))
    }
}

private const val SESSION = 0x0000_4000_0000_0011L
private const val TREE = 7
private const val SERVER_TO_REDIR = 0x1
private const val SIGNED = 0x8
private const val MORE_PROCESSING_REQUIRED = 0xC0000016.toInt()

private data class ForgeryOutcome(val result: NetworkResult<*>, val followUps: List<Int>, val retried: Boolean)
