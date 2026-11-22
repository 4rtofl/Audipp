package com.audipp.integrations.network.smb

import com.audipp.domain.model.Availability
import com.audipp.domain.model.PlaybackCapabilities
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.VariantId
import com.audipp.integrations.network.DirectoryRequest
import com.audipp.integrations.network.NetworkAccess
import com.audipp.integrations.network.NetworkCalls
import com.audipp.integrations.network.NetworkCredential
import com.audipp.integrations.network.NetworkCredentials
import com.audipp.integrations.network.NetworkDirectory
import com.audipp.integrations.network.NetworkEndpoint
import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.NetworkLibraryAdapter
import com.audipp.integrations.network.NetworkLibraryClient
import com.audipp.integrations.network.NetworkMedia
import com.audipp.integrations.network.NetworkResult
import com.audipp.playback.source.AdapterResolution
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceDescriptor
import com.audipp.playback.source.TargetKind
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

private const val MAX_RANGE_BYTES = 256 * 1024
private const val MAX_DIRECTORY_SCAN = 20_000
private const val MAX_PATH_CHARS = 2_048

class SmbLibrary internal constructor(
    val endpoint: NetworkEndpoint,
    private val isLanReadAllowed: () -> Boolean,
    private val calls: NetworkCalls,
    credentials: NetworkCredentials?,
    credential: NetworkCredential?,
    private val transport: SmbTransport,
) {
    constructor(
        endpoint: NetworkEndpoint,
        isLanReadAllowed: () -> Boolean,
        calls: NetworkCalls = NetworkCalls(),
        credentials: NetworkCredentials? = null,
        credential: NetworkCredential? = null,
    ) : this(endpoint, isLanReadAllowed, calls, credentials, credential,
        SmbjTransport(SmbEndpoint(endpoint), calls.policy, isLanReadAllowed))

    private val root = SmbEndpoint(endpoint).root
    val rootDirectoryId = id("dir", root)

    private val access = NetworkAccess(endpoint, calls, credentials, credential)
    private val directories = Entries<String>(4_096)
    private val media = Entries<String>(4_096)
    private val selections = Entries<Selection>(128)

    private val adapter = object : NetworkLibraryAdapter {
        override val endpoint get() = this@SmbLibrary.endpoint

        override suspend fun listDirectory(request: DirectoryRequest, credential: ByteArray?) =
            list(request, credential, SmbEntry::isDirectory) { path, name ->
                val id = id("dir", path)
                directories.put(id, path)
                NetworkDirectory(id, name)
            }

        override suspend fun listMedia(request: DirectoryRequest, credential: ByteArray?) =
            list(request, credential, ::isAudio) { path, name ->
                val id = id("media", path)
                media.put(id, path)
                NetworkMedia(variant(id), name)
            }

        override suspend fun resolveStream(request: ResolutionRequest, credential: ByteArray?) =
            resolve(request, credential)
    }

    val client = NetworkLibraryClient(adapter, calls, credentials, credential)

    /** Authenticates against the configured root before the caller saves the credential. */
    suspend fun verifyCredential(credential: ByteArray): NetworkResult<Unit> = calls.run(retrySafe = false) {
        exchange(credential) { share -> share.list(root) { NetworkResult.Success(Unit) } }
    }

    /**
     * Retries from the same byte offset, publishing the range only after verifying its identity.
     * On failure the caller can release this selection and return to media selection.
     */
    suspend fun readRange(
        descriptor: SourceDescriptor.DeviceResource,
        offset: Long,
        byteCount: Int,
    ): NetworkResult<ByteArray> {
        if (offset < 0 || byteCount !in 1..MAX_RANGE_BYTES) return failure(NetworkFailure.INVALID_INPUT)
        val selection = selections[descriptor.handle] ?: return failure(NetworkFailure.STALE_RESPONSE)
        val size = selection.identity.size
        if (offset > size) return failure(NetworkFailure.INVALID_INPUT)
        val result = access.call(rejection = {
            when {
                selections[descriptor.handle] !== selection -> NetworkFailure.STALE_RESPONSE
                !isLanReadAllowed() -> NetworkFailure.PERMISSION_REQUIRED
                else -> null
            }
        }) { secret ->
            exchange(secret) { share ->
                share.open(selection.path) { file ->
                    readVerified(file, selection.identity, offset, minOf(size - offset, byteCount.toLong()).toInt()).also {
                        if (it is NetworkResult.Failure && it.reason == NetworkFailure.STALE_RESPONSE) release(descriptor)
                    }
                }
            }.also {
                if (it is NetworkResult.Failure && it.reason == NetworkFailure.STALE_RESPONSE) release(descriptor)
            }
        }
        if (result is NetworkResult.Failure) release(descriptor)
        return result
    }

    fun release(descriptor: SourceDescriptor.DeviceResource) {
        selections.remove(descriptor.handle)
    }

    private fun readVerified(file: SmbFile, expected: SmbIdentity, offset: Long, count: Int): NetworkResult<ByteArray> {
        if (file.identity() != expected) return failure(NetworkFailure.STALE_RESPONSE)
        val bytes = ByteArray(count)
        var received = 0
        while (received < count) {
            val remaining = count - received
            val read = file.read(bytes, offset + received, received, remaining)
            if (read <= 0) return failure(NetworkFailure.IO)
            if (read > remaining) return failure(NetworkFailure.INVALID_RESPONSE)
            received += read
        }
        if (file.identity() != expected) return failure(NetworkFailure.STALE_RESPONSE)
        return NetworkResult.Success(bytes)
    }

    private suspend fun <T> list(
        request: DirectoryRequest,
        secret: ByteArray?,
        filter: (SmbEntry) -> Boolean,
        convert: (path: String, name: String) -> T,
    ): NetworkResult<List<T>> {
        val path = if (request.directoryId == rootDirectoryId) root else directories[request.directoryId]
            ?: return failure(NetworkFailure.INVALID_INPUT)
        if (request.offset >= MAX_DIRECTORY_SCAN) return failure(NetworkFailure.INVALID_INPUT)
        return exchange(secret) { share ->
            share.list(path) { entries ->
                val page = entries.withIndex()
                    .onEach { if (it.index >= MAX_DIRECTORY_SCAN) throw SmbRejected(NetworkFailure.UNSUPPORTED) }
                    .map { it.value }
                    .filter {
                        path.length + it.name.length < MAX_PATH_CHARS &&
                            !it.isReparsePoint && validSmbName(it.name) && filter(it)
                    }
                    .drop(request.offset)
                    .take(request.limit)
                    .map { convert(if (path.isEmpty()) it.name else "$path\\${it.name}", it.name) }
                    .toList()
                NetworkResult.Success(page)
            }
        }
    }

    private suspend fun resolve(request: ResolutionRequest, secret: ByteArray?): NetworkResult<AdapterResolution.Available> {
        if (request.context.target.kind != TargetKind.DEVICE) return failure(NetworkFailure.UNSUPPORTED)
        val id = request.variant.source.sourceId.value
        val path = media[id] ?: return failure(NetworkFailure.STALE_RESPONSE)
        val expected = variant(id)
        if (request.variant.variantId != expected.variantId || request.variant.trackId != expected.trackId) {
            return failure(NetworkFailure.INVALID_INPUT)
        }
        return exchange(secret) { share ->
            share.open(path) { file ->
                val identity = file.identity()
                if (!identity.usable || identity.size == 0L) return@open failure(NetworkFailure.UNSUPPORTED)
                val descriptor = SourceDescriptor.DeviceResource(UUID.randomUUID().toString())
                selections.put(descriptor.handle, Selection(path, identity))
                NetworkResult.Success(AdapterResolution.Available(
                    expected.variantId, expected.source.sourceId, descriptor,
                    PlaybackCapabilities(canPlay = true, canSeek = true),
                ))
            }
        }
    }

    private suspend fun <T> exchange(
        secret: ByteArray?,
        action: (SmbShare) -> NetworkResult<T>,
    ): NetworkResult<T> {
        if (!isLanReadAllowed()) return failure(NetworkFailure.PERMISSION_REQUIRED)
        if (secret == null) return failure(NetworkFailure.AUTH_REQUIRED)
        return transport.exchange(secret, action)
    }

    private fun variant(id: String) = TrackVariant(
        VariantId("smb:variant:$id"),
        TrackId("smb:track:$id"),
        SourceRef(SourceId(id), SourceKind.NETWORK, availability = Availability.PLAYABLE),
    )

    private fun id(kind: String, path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((endpoint.uri + "\u0000" + path).toByteArray(Charsets.UTF_8))
        return "smb:$kind:" + digest.joinToString("") { "%02x".format(it) }
    }

    private class Selection(val path: String, val identity: SmbIdentity)

    private class Entries<T>(private val limit: Int) {
        private val values = LinkedHashMap<String, T>(16, 0.75f, true)

        @Synchronized
        operator fun get(id: String): T? = values[id]

        @Synchronized
        fun put(id: String, value: T) {
            values[id] = value
            if (values.size > limit) values.remove(values.keys.first())
        }

        @Synchronized
        fun remove(id: String) {
            values.remove(id)
        }
    }
}

private fun failure(reason: NetworkFailure) = NetworkResult.Failure(reason)

private val audioExtensions = setOf(
    "aac", "aif", "aiff", "alac", "ape", "dff", "dsf", "flac", "m4a", "mp3",
    "oga", "ogg", "opus", "wav", "wma", "wv",
)

private fun isAudio(entry: SmbEntry): Boolean = !entry.isDirectory &&
    entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in audioExtensions
