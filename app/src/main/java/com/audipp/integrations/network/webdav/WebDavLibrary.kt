package com.audipp.integrations.network.webdav

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
import com.audipp.integrations.network.NetworkProtocol
import com.audipp.integrations.network.NetworkResult
import com.audipp.playback.source.AdapterResolution
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceDescriptor
import com.audipp.playback.source.TargetKind
import java.io.EOFException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class WebDavLibrary internal constructor(
    val endpoint: NetworkEndpoint,
    private val isLanReadAllowed: () -> Boolean,
    private val calls: NetworkCalls,
    credentials: NetworkCredentials?,
    credential: NetworkCredential?,
    httpBuilder: OkHttpClient.Builder,
) {
    constructor(
        endpoint: NetworkEndpoint,
        isLanReadAllowed: () -> Boolean,
        calls: NetworkCalls = NetworkCalls(),
        credentials: NetworkCredentials? = null,
        credential: NetworkCredential? = null,
    ) : this(endpoint, isLanReadAllowed, calls, credentials, credential,
        OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)))

    init {
        require(endpoint.protocol == NetworkProtocol.WEBDAV)
    }

    private val root = webDavRoot(endpoint.uri)
    val rootDirectoryId: String = directoryId(root)

    private val http = WebDavHttp(calls.policy, isLanReadAllowed, httpBuilder)
    private val access = NetworkAccess(endpoint, calls, credentials, credential)
    private val directories = Entries<HttpUrl>()
    private val selections = Entries<Selection>(limit = 128)

    private val adapter = object : NetworkLibraryAdapter {
        override val endpoint get() = this@WebDavLibrary.endpoint

        override suspend fun listDirectory(request: DirectoryRequest, credential: ByteArray?) =
            list(request, credential, WebDavEntry::collection) { entry ->
                val id = directoryId(entry.url)
                directories.put(id, entry.url)
                NetworkDirectory(id, entry.name)
            }

        override suspend fun listMedia(request: DirectoryRequest, credential: ByteArray?) =
            list(request, credential, ::isAudio) { entry ->
                val id = mediaId(entry.url)
                val path = relativeMediaPath(root, entry.url)
                NetworkMedia(variant(id, path), entry.name)
            }

        override suspend fun resolveStream(request: ResolutionRequest, credential: ByteArray?) =
            resolve(request, credential)
    }

    val client = NetworkLibraryClient(adapter, calls, credentials, credential)

    /** Checks the Basic credential bundle against the root before NetworkCredentials.authorize. */
    suspend fun verifyCredential(credential: ByteArray): NetworkResult<Unit> = calls.run {
        readDirectory(root, credential) { NetworkResult.Success(Unit) }
    }

    /**
     * Returns up to [byteCount] bytes from one verified representation.
     * STALE_RESPONSE, MISSING and REFRESH_REQUIRED mean discard buffered data and resolve the selection again.
     * UNSUPPORTED means this selection cannot seek.
     */
    suspend fun readRange(
        descriptor: SourceDescriptor.DeviceResource,
        offset: Long,
        byteCount: Int,
    ): NetworkResult<ByteArray> {
        if (offset < 0 || byteCount !in 1..MAX_WEB_DAV_RANGE_BYTES) return failure(NetworkFailure.INVALID_INPUT)
        return readSelected(descriptor) { selection, secret ->
            val length = selection.identity.length
            when {
                !selection.canSeek || length == null -> failure(NetworkFailure.UNSUPPORTED)
                offset > length -> failure(NetworkFailure.INVALID_INPUT)
                offset == length -> NetworkResult.Success(ByteArray(0))
                else -> loadRange(descriptor, selection, offset, minOf(byteCount.toLong(), length - offset).toInt(), secret)
            }
        }
    }

    /**
     * Streams a fresh GET from byte zero. The buffer belongs to this call and is reused.
     * After a failure, discard partial data before reselecting. Reads after emission are never retried.
     */
    suspend fun readSequential(
        descriptor: SourceDescriptor.DeviceResource,
        consume: (buffer: ByteArray, size: Int) -> Unit,
    ): NetworkResult<Long> =
        readSelected(descriptor, streaming = true) { selection, secret ->
            val request = selection.identity.constrain(Request.Builder().url(selection.url)).get().build()
            http.exchange(request, secret, streaming = true) { response, checkAccess ->
                readFailure(response.code, descriptor)?.let { return@exchange it }
                if (response.code != 200) invalidResponse()
                val identity = response.fullIdentity()
                if (!selection.identity.matches(identity)) return@exchange invalidate(descriptor, NetworkFailure.STALE_RESPONSE)
                val length = identity.length ?: selection.identity.length
                var total = 0L
                val buffer = ByteArray(32 * 1024)
                val source = response.body.byteStream()
                while (true) {
                    checkAccess()
                    val count = source.read(buffer)
                    checkAccess()
                    if (selections[descriptor.handle] !== selection) return@exchange failure(NetworkFailure.REFRESH_REQUIRED)
                    if (count == -1) break
                    if (length != null && count > length - total) {
                        return@exchange invalidate(descriptor, NetworkFailure.STALE_RESPONSE)
                    }
                    consume(buffer, count)
                    total += count
                }
                if (length != null && total < length) throw EOFException()
                if (total == 0L) invalidResponse()
                NetworkResult.Success(total)
            }
        }

    fun release(descriptor: SourceDescriptor.DeviceResource) {
        selections.remove(descriptor.handle)
    }

    private suspend fun <T> readSelected(
        descriptor: SourceDescriptor.DeviceResource,
        streaming: Boolean = false,
        read: suspend (Selection, ByteArray?) -> NetworkResult<T>,
    ): NetworkResult<T> {
        val selection = selections[descriptor.handle] ?: return failure(NetworkFailure.REFRESH_REQUIRED)
        val result = access.call(
            retrySafe = !streaming,
            timeoutMs = if (streaming) null else calls.policy.operationTimeoutMs,
            rejection = { if (isLanReadAllowed()) null else NetworkFailure.PERMISSION_REQUIRED },
        ) { secret ->
            if (selections[descriptor.handle] !== selection) failure(NetworkFailure.REFRESH_REQUIRED)
            else read(selection, secret)
        }
        return if (result is NetworkResult.Success && selections[descriptor.handle] !== selection) {
            failure(NetworkFailure.REFRESH_REQUIRED)
        } else result
    }

    private suspend fun <T> list(
        request: DirectoryRequest,
        secret: ByteArray?,
        filter: (WebDavEntry) -> Boolean,
        convert: (WebDavEntry) -> T,
    ): NetworkResult<List<T>> {
        val directory = if (request.directoryId == rootDirectoryId) root else directories[request.directoryId]
            ?: return failure(if (directoryIdPattern.matches(request.directoryId)) NetworkFailure.REFRESH_REQUIRED
                else NetworkFailure.INVALID_INPUT)
        return readDirectory(directory, secret) { entries ->
            val page = entries.filter(filter).drop(request.offset).take(request.limit).map(convert)
            NetworkResult.Success(page)
        }
    }

    private suspend fun <T> readDirectory(
        directory: HttpUrl,
        secret: ByteArray?,
        receive: (List<WebDavEntry>) -> NetworkResult<T>,
    ): NetworkResult<T> = http.exchange(propfind(directory), secret) { response, checkAccess ->
        if (response.code == 404 || response.code == 410) return@exchange failure(NetworkFailure.MISSING)
        if (response.code != 207) invalidResponse()
        val entries = parseDirectory(response, directory)
        checkAccess()
        receive(entries)
    }

    private suspend fun resolve(request: ResolutionRequest, secret: ByteArray?): NetworkResult<AdapterResolution.Available> {
        if (request.context.target.kind != TargetKind.DEVICE) return failure(NetworkFailure.UNSUPPORTED)
        val id = request.variant.source.sourceId.value
        val path = request.variant.source.providerItemId ?: return failure(NetworkFailure.REFRESH_REQUIRED)
        val url = resolveMediaPath(root, path) ?: return failure(NetworkFailure.INVALID_INPUT)
        val expected = variant(mediaId(url), path)
        if (id != expected.source.sourceId.value || request.variant.variantId != expected.variantId ||
            request.variant.trackId != expected.trackId
        ) {
            return failure(NetworkFailure.INVALID_INPUT)
        }
        val probe = Request.Builder().url(url).header("Range", "bytes=0-0").get().build()
        return http.exchange(probe, secret) { response, checkAccess ->
            val identity = when (response.code) {
                200 -> response.fullIdentity()
                206 -> {
                    val range = response.partialContent()
                    if (range.start != 0L || range.end != 0L) invalidResponse()
                    response.exactBody(1)
                    response.mediaIdentity(range.total)
                }
                404, 410 -> return@exchange failure(NetworkFailure.MISSING)
                416 -> return@exchange failure(NetworkFailure.UNSUPPORTED)
                else -> invalidResponse()
            }
            if (identity.length == 0L) return@exchange failure(NetworkFailure.UNSUPPORTED)
            checkAccess()
            val canSeek = response.code == 206 && identity.strongTag != null
            val descriptor = SourceDescriptor.DeviceResource(UUID.randomUUID().toString())
            selections.put(descriptor.handle, Selection(url, identity, canSeek))
            NetworkResult.Success(AdapterResolution.Available(
                expected.variantId,
                expected.source.sourceId,
                descriptor,
                PlaybackCapabilities(canPlay = true, canSeek = canSeek),
            ))
        }
    }

    private suspend fun loadRange(
        descriptor: SourceDescriptor.DeviceResource,
        selection: Selection,
        offset: Long,
        count: Int,
        secret: ByteArray?,
    ): NetworkResult<ByteArray> {
        val end = offset + count - 1
        val request = selection.identity.constrain(Request.Builder().url(selection.url))
            .header("Range", "bytes=$offset-$end").get().build()
        return http.exchange(request, secret) { response, _ ->
            readFailure(response.code, descriptor)?.let { return@exchange it }
            when (response.code) {
                200 -> {
                    if (!selection.identity.matches(response.fullIdentity())) invalidate(descriptor, NetworkFailure.STALE_RESPONSE)
                    else {
                        selection.canSeek = false
                        failure(NetworkFailure.UNSUPPORTED)
                    }
                }
                206 -> {
                    val range = response.partialContent()
                    if (!selection.identity.matches(response.mediaIdentity(range.total))) {
                        invalidate(descriptor, NetworkFailure.STALE_RESPONSE)
                    }
                    else {
                        if (range.start != offset || range.end != end) invalidResponse()
                        NetworkResult.Success(response.exactBody(count))
                    }
                }
                else -> invalidResponse()
            }
        }
    }

    private fun readFailure(code: Int, descriptor: SourceDescriptor.DeviceResource): NetworkResult.Failure? = when (code) {
        404, 410 -> invalidate(descriptor, NetworkFailure.MISSING)
        412 -> invalidate(descriptor, NetworkFailure.STALE_RESPONSE)
        416 -> invalidate(descriptor, NetworkFailure.UNSUPPORTED)
        else -> null
    }

    private fun invalidate(descriptor: SourceDescriptor.DeviceResource, reason: NetworkFailure): NetworkResult.Failure {
        release(descriptor)
        return failure(reason)
    }

    private fun variant(id: String, path: String) = TrackVariant(
        VariantId("webdav:variant:" + id),
        TrackId("webdav:track:" + id),
        SourceRef(SourceId(id), SourceKind.NETWORK, providerItemId = path, availability = Availability.PLAYABLE),
    )

    private fun directoryId(url: HttpUrl) = "webdav:dir:" + urlId(url)
    private fun mediaId(url: HttpUrl) = "webdav:media:" + urlId(url)

    private fun urlId(url: HttpUrl): String = MessageDigest.getInstance("SHA-256")
        .digest((endpoint.uri + "\u0000" + url.pathSegments.joinToString("/")).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private class Selection(val url: HttpUrl, val identity: MediaIdentity, @Volatile var canSeek: Boolean)

    private class Entries<T>(private val limit: Int = 4_096) {
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

private val directoryIdPattern = Regex("webdav:dir:[0-9a-f]{64}")

private val audioExtensions = setOf(
    "aac", "aif", "aiff", "alac", "ape", "dff", "dsf", "flac", "m4a", "mp3",
    "oga", "ogg", "opus", "wav", "wma", "wv",
)

private fun isAudio(entry: WebDavEntry): Boolean = !entry.collection &&
    (entry.contentType?.startsWith("audio/", ignoreCase = true) == true ||
        entry.url.pathSegments.last().substringAfterLast('.', "").lowercase(Locale.ROOT) in audioExtensions)
