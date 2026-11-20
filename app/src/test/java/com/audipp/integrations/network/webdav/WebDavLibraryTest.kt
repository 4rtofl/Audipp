package com.audipp.integrations.network.webdav

import com.audipp.domain.model.ExecutionMode
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.TransitionId
import com.audipp.integrations.network.DirectoryRequest
import com.audipp.integrations.network.NetworkCallPolicy
import com.audipp.integrations.network.NetworkCalls
import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.NetworkResult
import com.audipp.playback.source.AdapterKey
import com.audipp.playback.source.NetworkLibrarySourceAdapter
import com.audipp.playback.source.PlaybackTarget
import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.ResolutionFailure
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceDescriptor
import com.audipp.playback.source.SourceResolution
import com.audipp.playback.source.SourceResolver
import com.audipp.playback.source.TargetKind
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavLibraryTest {
    @Test fun listsDirectChildrenAndResolvesTheSelectedDevicePlan() = runBlocking {
        WebDavFixture().use { fixture ->
            val escaped = AtomicBoolean(false)
            fixture.handler = {
                when {
                    it.method == "GET" -> range(0, 0)
                    it.url.encodedPath == "/music/albums/" ->
                        directory(xml(entry("/music/albums/", collection = true), entry("/music/albums/side.flac")))
                    else -> directory(xml(
                        entry(fixture.endpoint.uri.removeSuffix("/"), collection = true),
                        entry("/music/albums", collection = true),
                        entry(if (escaped.get()) "/music/%66irst%20track.flac" else "/music/first%20track.flac", additionalPropstat = """
                            <D:propstat><D:prop><D:displayname>unknown</D:displayname></D:prop>
                            <D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>
                        """.trimIndent()),
                        entry("/music/voice", type = "audio/wav"),
                        entry("/music/notes.txt"),
                    ))
                }
            }
            val library = fixture.library()
            val root = DirectoryRequest(library.rootDirectoryId)
            val folder = library.client.listDirectory(root).value().single()
            assertEquals("albums", folder.name)
            assertEquals("side.flac", library.client.listMedia(DirectoryRequest(folder.id)).value().single().name)
            val first = library.client.listMedia(root.copy(limit = 1)).value().single()
            assertEquals("first track.flac", first.name)
            assertEquals("voice", library.client.listMedia(root.copy(offset = 1, limit = 1)).value().single().name)
            assertTrue(library.client.listMedia(root.copy(offset = 2)).value().isEmpty())
            val plan = library.resolve(first.variant).playback()
            assertEquals(ExecutionMode.LOCAL_DIRECT, plan.plan.mode)
            assertTrue(plan.plan.capabilities.canSeek)
            assertTrue(plan.descriptor is SourceDescriptor.DeviceResource)
            assertFalse(plan.plan.toString().contains("first%20track"))
            val renderer = deviceContext.copy(target = PlaybackTarget("renderer", TargetKind.RENDERER))
            assertEquals(SourceResolution.Unavailable(ResolutionFailure.ADAPTER_UNAVAILABLE), library.resolve(first.variant, renderer))
            assertTrue(fixture.requests.filter { it.method == "PROPFIND" }.all {
                it.depth == "1" && "DAV:" in it.body && it.authorization == null
            })
            assertEquals("/music/first%20track.flac", fixture.requests.last().path)
            escaped.set(true)
            assertEquals(first.variant, library.client.listMedia(root.copy(limit = 1)).value().single().variant)
        }
    }

    @Test fun verifiedRangesHaveExactExtentAndClippedTail() = runBlocking {
        WebDavFixture().use { fixture ->
            fixture.handler = {
                when (it.headers["Range"]) {
                    null -> directory()
                    "bytes=0-0" -> range(0, 0)
                    else -> range(4, 5)
                }
            }
            val library = fixture.library()
            val selected = library.resolve(library.song()).playback().deviceResource()
            assertEquals("ef", library.readRange(selected, 4, 100).value().decodeToString())
            assertEquals("bytes=4-5", fixture.requests.last().range)
            assertEquals("\"original\"", fixture.requests.last().ifMatch)
            assertTrue(library.readRange(selected, 6, 20).value().isEmpty())
            for ((offset, count) in listOf(-1L to 4, 7L to 4, 0L to MAX_WEB_DAV_RANGE_BYTES + 1)) {
                assertEquals(NetworkFailure.INVALID_INPUT, library.readRange(selected, offset, count).reason())
            }
            assertEquals(3, fixture.requests.size)
        }
    }

    @Test fun ignoredRangeAllowsSequentialGetFromZero() = runBlocking {
        WebDavFixture().use { fixture ->
            fixture.handler = {
                if (it.method == "PROPFIND") directory()
                else reply(200, "abcdef".toByteArray(), "Last-Modified" to ORIGINAL_MODIFIED)
            }
            val library = fixture.library()
            val plan = library.resolve(library.song()).playback()
            assertTrue(plan.plan.capabilities.canPlay)
            assertFalse(plan.plan.capabilities.canSeek)
            assertEquals("bytes=0-0", fixture.requests.last().range)
            assertEquals(NetworkFailure.UNSUPPORTED, library.readRange(plan.deviceResource(), 2, 2).reason())
            val data = ByteArrayOutputStream()
            assertEquals(6L, library.readSequential(plan.deviceResource()) { buffer, size -> data.write(buffer, 0, size) }.value())
            assertEquals("abcdef", data.toString("UTF-8"))
            assertNull(fixture.requests.last().range)
            assertEquals(ORIGINAL_MODIFIED, fixture.requests.last().ifUnmodifiedSince)
        }
    }

    @Test fun subsequent200DowngradesSeekWithoutReturningUnrequestedBytes() = runBlocking {
        WebDavFixture().use { fixture ->
            fixture.handler = {
                when {
                    it.method == "PROPFIND" -> directory()
                    it.headers["Range"] == "bytes=0-0" -> range(0, 0)
                    else -> reply(200, "abcdef".toByteArray(), "ETag" to "\"original\"")
                }
            }
            val library = fixture.library()
            val plan = library.resolve(library.song()).playback()
            assertTrue(plan.plan.capabilities.canSeek)
            assertEquals(NetworkFailure.UNSUPPORTED, library.readRange(plan.deviceResource(), 2, 2).reason())
            assertEquals(NetworkFailure.UNSUPPORTED, library.readRange(plan.deviceResource(), 3, 1).reason())
            assertEquals(3, fixture.requests.size)
        }
    }

    @Test fun changedTagDateLengthOrFailedPreconditionInvalidatesTheSelection() = runBlocking {
        WebDavFixture().use { fixture ->
            val library = fixture.library()
            fixture.handler = { directory() }
            val song = library.song()
            for ((contents, tag, modified) in listOf(
                Triple("ABCDEF", "\"changed\"", ORIGINAL_MODIFIED),
                Triple("ABCDEF", "\"original\"", NEW_MODIFIED),
                Triple("ABCDEFG", "\"original\"", ORIGINAL_MODIFIED),
            )) {
                fixture.handler = { range(0, 0) }
                val old = library.resolve(song).playback().deviceResource()
                fixture.handler = {
                    if (it.headers["Range"] == "bytes=0-0") range(0, 0, contents, tag, modified)
                    else range(2, 3, contents, tag, modified)
                }
                assertEquals(NetworkFailure.STALE_RESPONSE, library.readRange(old, 2, 2).reason())
                assertEquals(NetworkFailure.STALE_RESPONSE, library.readRange(old, 0, 1).reason())
                val fresh = library.resolve(song).playback().deviceResource()
                assertNotEquals(old.handle, fresh.handle)
                assertEquals("CD", library.readRange(fresh, 2, 2).value().decodeToString())
            }
            fixture.handler = { range(0, 0) }
            val stale = library.resolve(song).playback().deviceResource()
            fixture.handler = { reply(412) }
            assertEquals(NetworkFailure.STALE_RESPONSE, library.readRange(stale, 2, 2).reason())
        }
    }

    @Test fun partialContentRequiresMatchingRangeAndBodyAndStrongValidatorForSeek() = runBlocking {
        WebDavFixture().use { fixture ->
            fixture.handler = { directory() }
            val library = fixture.library()
            val song = library.song()
            fixture.handler = { range(0, 0, tag = "W/\"original\"") }
            assertFalse(library.resolve(song).playback().plan.capabilities.canSeek)
            fixture.handler = { reply(206, "a".toByteArray(), "Content-Range" to "bytes 0-0/6") }
            assertFalse(library.resolve(song).playback().plan.capabilities.canSeek)
            for ((header, data) in listOf(
                "bytes 1-1/6" to "a", "bytes 0-0/*" to "a", "bytes 0-0/0" to "a",
                "bytes 0-0/6" to "ab", "bytes 0-0/6" to "",
            )) {
                fixture.handler = {
                    reply(206, data.toByteArray(), "Content-Range" to header, "ETag" to "\"original\"", chunked = true)
                }
                assertEquals(SourceResolution.Unavailable(ResolutionFailure.INVALID_DESCRIPTOR), library.resolve(song))
            }
            fixture.handler = { reply(200, "<html>Login</html>".toByteArray(), "Content-Type" to "text/html") }
            assertEquals(SourceResolution.Unavailable(ResolutionFailure.INVALID_DESCRIPTOR), library.resolve(song))
            fixture.handler = { range(0, 0) }
            val plan = library.resolve(song).playback()
            fixture.handler = { range(1, 2) }
            assertEquals(NetworkFailure.INVALID_RESPONSE, library.readRange(plan.deviceResource(), 2, 2).reason())
        }
    }

    @Test fun refusesDtdExternalEntitiesOversizedXmlExcessiveDepthAndEntryCount() = runBlocking {
        WebDavFixture().use { fixture ->
            val library = fixture.library()
            val root = entry("/music/", collection = true)
            val dtd = """<!DOCTYPE multistatus [<!ENTITY external SYSTEM "urn:audipp:fixture">]>"""
            for (payload in listOf(
                (dtd + xml(root)).toByteArray(),
                ("<?xml version=\"1.0\" encoding=\"UTF-16\"?>" + dtd + xml(root)).toByteArray(Charsets.UTF_16),
                xml(root, "<x>".repeat(32) + "</x>".repeat(32)).toByteArray(),
                xml(root, "<!--" + " ".repeat(2 * 1024 * 1024) + "-->").toByteArray(),
                xml(root, *(1..1_001).map { entry("/music/" + it + ".flac") }.toTypedArray()).toByteArray(),
            )) {
                fixture.handler = { reply(207, payload, chunked = true) }
                assertEquals(NetworkFailure.INVALID_RESPONSE, library.client.listMedia(DirectoryRequest(library.rootDirectoryId)).reason())
            }
            fixture.handler = { reply(207, xml(root).toByteArray(Charsets.UTF_16)) }
            assertTrue(library.client.listMedia(DirectoryRequest(library.rootDirectoryId)).value().isEmpty())
            assertTrue(fixture.requests.all { it.depth == "1" })
        }
    }

    @Test fun refusesCrossOriginOrRecursiveHrefsBeforeAnyMediaGet() = runBlocking {
        WebDavFixture().use { fixture ->
            val library = fixture.library()
            val root = DirectoryRequest(library.rootDirectoryId)
            for (href in listOf(
                "https://other.invalid/music/test.flac", "/other/test.flac", "/music/nested/test.flac",
                "/music/%2E%2E/test.flac", "/music/%2ftest.flac", "/music/%252Ftest.flac", "/music/%ff.flac",
                "/music/test.flac?secret=x", "/music/test.flac#fragment", "//127.0.0.1/music/test.flac",
            )) {
                fixture.handler = { directory(xml(entry("/music/", collection = true), entry(href))) }
                assertEquals(NetworkFailure.INVALID_RESPONSE, library.client.listMedia(root).reason())
            }
            assertEquals(NetworkFailure.INVALID_INPUT, library.client.listMedia(DirectoryRequest("/music/")).reason())
            assertTrue(fixture.requests.all { it.method == "PROPFIND" })
        }
    }

    @Test fun rateLimitWaitsThenRetriesOnceAndBlocksAnEarlySubsequentCall() = runBlocking {
        WebDavFixture().use { fixture ->
            val library = fixture.library()
            val root = DirectoryRequest(library.rootDirectoryId)
            fixture.handler = {
                if (fixture.requests.size == 1) reply(429, headers = arrayOf("Retry-After" to "1"))
                else directory()
            }
            assertEquals(1, library.client.listMedia(root).value().size)
            val (first, second) = fixture.requests.toList()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(second.receivedNanos - first.receivedNanos) >= 950)
            val retryAt = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
                .withZone(ZoneOffset.UTC).format(Instant.now().plusSeconds(60))
            fixture.handler = { reply(429, headers = arrayOf("Retry-After" to retryAt)) }
            assertEquals(NetworkFailure.RATE_LIMITED, library.client.listMedia(root).reason())
            assertEquals(NetworkFailure.RATE_LIMITED, library.client.listMedia(root).reason())
            assertEquals(3, fixture.requests.size)
        }
        WebDavFixture().use { fixture ->
            val library = fixture.library()
            fixture.handler = { reply(429, headers = arrayOf("Retry-After" to "0")) }
            assertEquals(NetworkFailure.RATE_LIMITED, library.client.listMedia(DirectoryRequest(library.rootDirectoryId)).reason())
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test fun deniedLanAccessStopsBeforeIoAndRejectsALateResponse() = runBlocking {
        WebDavFixture().use { fixture ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val allowed = AtomicBoolean(false)
            val library = fixture.library(isAllowed = allowed::get)
            val root = DirectoryRequest(library.rootDirectoryId)
            assertEquals(NetworkFailure.PERMISSION_REQUIRED, library.client.listMedia(root).reason())
            assertTrue(fixture.requests.isEmpty())
            allowed.set(true)
            fixture.handler = {
                started.countDown()
                release.await(3, TimeUnit.SECONDS)
                directory()
            }
            val pending = async { library.client.listMedia(root) }
            awaitStarted(started)
            allowed.set(false)
            release.countDown()
            assertEquals(NetworkFailure.PERMISSION_REQUIRED, pending.await().reason())
        }
    }

    @Test fun reselectsAfterConnectionLossAndNeverReplaysAPartialSequentialBody() = runBlocking {
        WebDavFixture().use { fixture ->
            val library = fixture.library(attempts = 2)
            fixture.handler = { if (it.method == "PROPFIND") directory() else range(0, 0) }
            val song = library.song()
            val before = library.resolve(song).playback().deviceResource()
            fixture.handler = { MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() }
            assertEquals(NetworkFailure.IO, library.readRange(before, 2, 2).reason())
            assertEquals(2, fixture.requests.count { it.range == "bytes=2-3" })
            fixture.handler = {
                if (it.headers["Range"] != null) range(0, 0)
                else reply(200, "abcdef".toByteArray(), "ETag" to "\"original\"")
                    .newBuilder().onResponseBody(SocketEffect.CloseSocket()).build()
            }
            val fresh = library.resolve(song).playback().deviceResource()
            val partial = ByteArrayOutputStream()
            assertEquals(NetworkFailure.IO, library.readSequential(fresh) { buffer, size -> partial.write(buffer, 0, size) }.reason())
            assertEquals("abc", partial.toString("UTF-8"))
            assertEquals(1, fixture.requests.count { it.method == "GET" && it.range == null })
            fixture.handler = {
                if (it.headers["Range"] == "bytes=0-0") range(0, 0)
                else range(2, 3)
            }
            val recovered = library.resolve(song).playback().deviceResource()
            assertNotEquals(before.handle, recovered.handle)
            assertEquals("cd", library.readRange(recovered, 2, 2).value().decodeToString())
        }
    }

    @Test fun cancelClosesAStalledResponseBeforeOperationTimeout() = runBlocking {
        WebDavFixture().use { fixture ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val library = fixture.library(timeout = 8_000)
            fixture.handler = {
                MockResponse.Builder().code(207).setHeader("Content-Length", 1)
                    .stallBody(started, release)
            }
            val pending = async { library.client.listMedia(DirectoryRequest(library.rootDirectoryId)) }
            try {
                awaitStarted(started)
                withTimeout(2_000) { pending.cancelAndJoin() }
                assertTrue(pending.isCancelled)
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun timeoutAndStaleRouteNeverPublishAPlaybackPlan() = runBlocking {
        WebDavFixture().use { fixture ->
            val library = fixture.library(timeout = 500)
            fixture.handler = { directory() }
            val song = library.song()
            fixture.handler = { Thread.sleep(1_000); range(0, 0) }
            assertEquals(SourceResolution.Unavailable(ResolutionFailure.TIMEOUT), library.resolve(song))
        }
        WebDavFixture().use { fixture ->
            val library = fixture.library()
            fixture.handler = { directory() }
            val song = library.song()
            val context = java.util.concurrent.atomic.AtomicReference(deviceContext)
            val resolver = library.resolver(context::get)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.handler = { started.countDown(); release.await(3, TimeUnit.SECONDS); range(0, 0) }
            val pending = async { resolver.resolve(ResolutionRequest(song, deviceContext)) }
            awaitStarted(started)
            context.set(deviceContext.copy(routeRevision = 1))
            release.countDown()
            assertEquals(SourceResolution.Unavailable(ResolutionFailure.STALE_ROUTE), pending.await())
        }
    }
}

internal fun WebDavFixture.library(
    timeout: Long = 5_000,
    attempts: Int = 1,
    isAllowed: () -> Boolean = { true },
): WebDavLibrary = WebDavLibrary(endpoint, isAllowed, callPolicy(timeout, attempts))

internal fun callPolicy(timeout: Long = 5_000, attempts: Int = 1) =
    NetworkCalls(NetworkCallPolicy(operationTimeoutMs = timeout, connectTimeoutMs = 1_000,
        maxAttempts = attempts, initialBackoffMs = 1, maxBackoffMs = 1))

internal suspend fun WebDavLibrary.song(): TrackVariant =
    client.listMedia(DirectoryRequest(rootDirectoryId)).value().single().variant

internal val deviceContext = ResolutionContext(PlaybackTarget("device", TargetKind.DEVICE), 0, TransitionId("select"))

private fun WebDavLibrary.resolver(currentContext: () -> ResolutionContext) = SourceResolver(
    mapOf(AdapterKey(SourceKind.NETWORK, TargetKind.DEVICE) to NetworkLibrarySourceAdapter(client)),
    currentContext,
)

internal suspend fun WebDavLibrary.resolve(variant: TrackVariant, context: ResolutionContext = deviceContext) =
    resolver { context }.resolve(ResolutionRequest(variant, context))

internal fun SourceResolution.playback(): SourceResolution.Resolved.Playback =
    this as? SourceResolution.Resolved.Playback ?: error("Resolution failed: " + this)

internal fun SourceResolution.Resolved.Playback.deviceResource() = descriptor as SourceDescriptor.DeviceResource

internal fun <T> NetworkResult<T>.value(): T =
    (this as? NetworkResult.Success ?: error("Network call failed: " + this)).value

internal fun NetworkResult<*>.reason(): NetworkFailure =
    (this as? NetworkResult.Failure ?: error("Network call unexpectedly succeeded")).reason

internal suspend fun awaitStarted(latch: CountDownLatch) {
    withContext(Dispatchers.IO) { check(latch.await(3, TimeUnit.SECONDS)) }
}
