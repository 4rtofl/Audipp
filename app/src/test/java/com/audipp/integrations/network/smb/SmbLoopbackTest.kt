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
import com.hierynomus.security.bc.BCSecurityProvider
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.TimeUnit.SECONDS
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
