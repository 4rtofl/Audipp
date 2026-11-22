package com.audipp.integrations.network.smb

import com.audipp.data.secrets.SecretFiles
import com.audipp.data.secrets.SecretKeys
import com.audipp.data.secrets.SecretStore
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.TransitionId
import com.audipp.integrations.network.DirectoryRequest
import com.audipp.integrations.network.NetworkCallPolicy
import com.audipp.integrations.network.NetworkCalls
import com.audipp.integrations.network.NetworkCredentials
import com.audipp.integrations.network.NetworkEndpoint
import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.NetworkProtocol
import com.audipp.integrations.network.NetworkResult
import com.audipp.playback.source.AdapterKey
import com.audipp.playback.source.NetworkLibrarySourceAdapter
import com.audipp.playback.source.PlaybackTarget
import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceDescriptor
import com.audipp.playback.source.SourceResolution
import com.audipp.playback.source.SourceResolver
import com.audipp.playback.source.TargetKind
import com.hierynomus.msfscc.FileAttributes
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal val smbDeviceContext = ResolutionContext(PlaybackTarget("phone", TargetKind.DEVICE), 1, TransitionId("select"))

class SmbLibraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val endpoint = NetworkEndpoint(NetworkProtocol.SMB, "smb://127.0.0.1/share/Music%20root")
    private val calls = NetworkCalls(NetworkCallPolicy(initialBackoffMs = 1, maxBackoffMs = 1))
    private val fixture = SmbFixture()
    private val password = SmbPassword.encode("listener", "fixture-password".toCharArray(), "WORKGROUP")
    private var allowed = true

    @Test fun rejectsAbsentShareTraversalEncodedSeparatorsAndAlternateStreams() {
        for (path in listOf("", "/", "/share//album", "/share/%2e%2e", "/share/%2Falbum",
            "/share/%5Calbum", "/share/track.wav%3Astream", "/share/name%20", "/share/%FF")) {
            assertThrows(IllegalArgumentException::class.java) {
                SmbLibrary(NetworkEndpoint(NetworkProtocol.SMB, "smb://127.0.0.1$path"), { true })
            }
        }
    }

    @Test fun listsOnlyDirectChildrenAndResolvesThroughTheSharedSourceResolver() = runBlocking {
        val library = library()
        val root = DirectoryRequest(library.rootDirectoryId)
        val directory = success(library.client.listDirectory(root)).single()
        assertEquals("Album", directory.name)
        assertTrue(success(library.client.listDirectory(root.copy(offset = 1))).isEmpty())
        assertEquals(emptyList<Any>(), success(library.client.listMedia(root)))
        assertEquals(NetworkResult.Failure(NetworkFailure.INVALID_INPUT),
            library.client.listMedia(DirectoryRequest("..\\outside")))
        val item = success(library.client.listMedia(DirectoryRequest(directory.id))).single()
        assertEquals("song.wav", item.name)
        assertFalse(item.toString().contains("song.wav"))
        assertFalse(item.variant.source.sourceId.value.contains("song.wav"))

        val resolver = SourceResolver(mapOf(AdapterKey(SourceKind.NETWORK, TargetKind.DEVICE) to
            NetworkLibrarySourceAdapter(library.client)), { smbDeviceContext })
        val resolved = resolver.resolve(ResolutionRequest(item.variant, smbDeviceContext)) as SourceResolution.Resolved.Playback
        assertTrue(resolved.plan.capabilities.canSeek)
        val descriptor = resolved.descriptor as SourceDescriptor.DeviceResource
        assertArrayEquals(byteArrayOf(3, 4), success(library.readRange(descriptor, 2, 20)))
        assertArrayEquals(byteArrayOf(), success(library.readRange(descriptor, 4, 1)))
        assertEquals(NetworkResult.Failure(NetworkFailure.INVALID_INPUT), library.readRange(descriptor, 5, 1))
        assertEquals(NetworkResult.Failure(NetworkFailure.INVALID_INPUT), library.readRange(descriptor, 0, 256 * 1024 + 1))
        val renderer = smbDeviceContext.copy(target = PlaybackTarget("renderer", TargetKind.RENDERER))
        assertEquals(NetworkResult.Failure(NetworkFailure.UNSUPPORTED),
            library.client.resolveStream(ResolutionRequest(item.variant, renderer)))
    }

    @Test fun failedPartialReadRetriesAtTheOriginalOffsetBeforePublishingAnyBytes() = runBlocking {
        val library = library()
        val descriptor = select(library)
        fixture.interruptNextRead = true
        assertArrayEquals(byteArrayOf(2, 3, 4), success(library.readRange(descriptor, 1, 3)))
        assertEquals(listOf(1L, 2L, 1L), fixture.offsets)
        assertTrue(fixture.borrowed!!.all { it == 0.toByte() })
    }

    @Test fun replacedFileCannotAppendToOldBufferEvenWhenSizeAndLastWriteMatch() = runBlocking {
        val library = library()
        val descriptor = select(library)
        assertArrayEquals(byteArrayOf(1), success(library.readRange(descriptor, 0, 1)))
        fixture.interruptNextRead = true
        fixture.afterDisconnect = { fixture.identity = fixture.identity.copy(index = 22) }
        assertEquals(NetworkResult.Failure(NetworkFailure.STALE_RESPONSE), library.readRange(descriptor, 1, 3))
        assertEquals(listOf(0L, 1L, 2L), fixture.offsets)
        assertEquals(NetworkResult.Failure(NetworkFailure.STALE_RESPONSE), library.readRange(descriptor, 1, 1))
        val next = select(library)
        assertNotEquals(descriptor.handle, next.handle)
        assertArrayEquals(byteArrayOf(2), success(library.readRange(next, 1, 1)))
    }

    @Test fun changeDuringReadOrFailedReconnectInvalidatesOnlyTheAttemptedSelection() = runBlocking {
        val library = library()
        val changed = select(library)
        fixture.changeAfterRead = true
        assertEquals(NetworkResult.Failure(NetworkFailure.STALE_RESPONSE), library.readRange(changed, 0, 4))
        val unavailable = select(library)
        fixture.offline = true
        val previousAttempts = fixture.attempts
        assertEquals(NetworkResult.Failure(NetworkFailure.OFFLINE), library.readRange(unavailable, 2, 1))
        assertEquals(previousAttempts + 3, fixture.attempts)
        fixture.offline = false
        assertEquals(NetworkResult.Failure(NetworkFailure.STALE_RESPONSE), library.readRange(unavailable, 2, 1))
        assertArrayEquals(byteArrayOf(1), success(library.readRange(select(library), 0, 1)))
    }

    @Test fun releaseAndCancellationDiscardAnOutstandingResponseAndClearBorrowedCredentials() = runBlocking {
        val library = library()
        val descriptor = select(library)
        val waiting = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        fixture.beforeAction = { waiting.complete(Unit); resume.await() }
        val pending = async { library.readRange(descriptor, 0, 1) }
        waiting.await()
        library.release(descriptor)
        resume.complete(Unit)
        assertEquals(NetworkResult.Failure(NetworkFailure.STALE_RESPONSE), pending.await())

        fixture.beforeAction = {}
        val next = select(library)
        val started = CompletableDeferred<Unit>()
        fixture.beforeAction = { started.complete(Unit); CompletableDeferred<Unit>().await() }
        val cancelled = async { library.readRange(next, 0, 1) }
        started.await()
        cancelled.cancelAndJoin()
        assertTrue(fixture.borrowed!!.all { it == 0.toByte() })
    }

    @Test fun permissionAndCredentialScopeAreCheckedBeforeTransport() = runBlocking {
        val store = credentials(temporary.newFolder())
        val token = store.authorize(endpoint, "listener", password)
        val other = NetworkEndpoint(NetworkProtocol.SMB, "smb://127.0.0.1/share/Elsewhere")
        val otherLibrary = SmbLibrary(other, { allowed }, calls, store, token, fixture)
        assertEquals(NetworkResult.Failure(NetworkFailure.AUTH_REQUIRED),
            otherLibrary.client.listDirectory(DirectoryRequest(otherLibrary.rootDirectoryId)))
        val library = SmbLibrary(endpoint, { allowed }, calls, store, token, fixture)
        allowed = false
        assertEquals(NetworkResult.Failure(NetworkFailure.PERMISSION_REQUIRED), library.verifyCredential(password))
        assertEquals(0, fixture.attempts)
    }

    private suspend fun library(): SmbLibrary {
        val store = credentials(temporary.newFolder())
        val token = store.authorize(endpoint, "listener", password)
        return SmbLibrary(endpoint, { allowed }, calls, store, token, fixture)
    }

    private suspend fun select(library: SmbLibrary): SourceDescriptor.DeviceResource {
        val directory = success(library.client.listDirectory(DirectoryRequest(library.rootDirectoryId))).single()
        val item = success(library.client.listMedia(DirectoryRequest(directory.id))).single()
        return success(library.client.resolveStream(ResolutionRequest(item.variant, smbDeviceContext)))
            .descriptor as SourceDescriptor.DeviceResource
    }
}

internal fun <T> success(result: NetworkResult<T>): T = (result as? NetworkResult.Success)?.value ?: error("Expected success, received $result")

internal fun credentials(root: File): NetworkCredentials {
    val keys = object : SecretKeys {
        private val values = mutableMapOf<String, SecretKey>()
        override fun find(alias: String) = values[alias]
        override fun create(alias: String) = KeyGenerator.getInstance("AES").apply { init(256) }
            .generateKey().also { values[alias] = it }
        override fun delete(alias: String) { values.remove(alias) }
    }
    return NetworkCredentials(SecretStore(SecretFiles(root), keys))
}

private class SmbFixture : SmbTransport, SmbShare, SmbFile {
    var identity = SmbIdentity(UUID.randomUUID(), 11, 101, 102, 4)
    var interruptNextRead = false
    var afterDisconnect = {}
    var changeAfterRead = false
    var offline = false
    var beforeAction: suspend () -> Unit = {}
    var attempts = 0
    var borrowed: ByteArray? = null
    val offsets = mutableListOf<Long>()
    private var partial = false

    override suspend fun <T> exchange(secret: ByteArray, action: (SmbShare) -> NetworkResult<T>): NetworkResult<T> {
        attempts++
        borrowed = secret
        beforeAction()
        if (offline) return NetworkResult.Failure(NetworkFailure.OFFLINE)
        return try {
            action(this)
        } catch (_: IOException) {
            afterDisconnect()
            NetworkResult.Failure(NetworkFailure.IO)
        }
    }

    override fun <T> list(path: String, visit: (Sequence<SmbEntry>) -> T): T = visit(when (path) {
        "Music root" -> sequenceOf(
            SmbEntry("Album", FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value),
            SmbEntry("..", FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value),
            SmbEntry("escape.wav", reparseAttribute),
        )
        "Music root\\Album" -> sequenceOf(SmbEntry("song.wav", 0), SmbEntry("note.txt", 0), SmbEntry("x:y.wav", 0))
        else -> error("Unexpected directory")
    })

    override fun <T> open(path: String, read: (SmbFile) -> T): T {
        assertEquals("Music root\\Album\\song.wav", path)
        return read(this)
    }

    override fun identity() = identity

    override fun read(buffer: ByteArray, offset: Long, start: Int, length: Int): Int {
        offsets += offset
        if (partial) {
            partial = false
            interruptNextRead = false
            throw IOException()
        }
        val count = if (interruptNextRead) {
            partial = true
            buffer[start] = 99
            1
        } else {
            byteArrayOf(1, 2, 3, 4).copyInto(buffer, start, offset.toInt(), offset.toInt() + length)
            length
        }
        if (changeAfterRead) {
            identity = identity.copy(changed = identity.changed + 1)
            changeAfterRead = false
        }
        return count
    }
}
