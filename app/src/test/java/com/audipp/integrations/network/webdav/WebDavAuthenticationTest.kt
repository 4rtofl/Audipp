package com.audipp.integrations.network.webdav

import com.audipp.data.secrets.SecretFiles
import com.audipp.data.secrets.SecretKeys
import com.audipp.data.secrets.SecretState
import com.audipp.data.secrets.SecretStore
import com.audipp.integrations.network.DirectoryRequest
import com.audipp.integrations.network.NetworkCredentials
import com.audipp.integrations.network.NetworkFailure
import com.audipp.playback.source.ResolutionFailure
import com.audipp.playback.source.SourceResolution
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebDavAuthenticationTest {
    @get:Rule val temporary = TemporaryFolder()

    private val keys = object : SecretKeys {
        private val values = mutableMapOf<String, SecretKey>()
        override fun find(alias: String) = values[alias]
        override fun create(alias: String) = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            .also { values[alias] = it }
        override fun delete(alias: String) { values.remove(alias) }
    }
    private val bundle = "fixture-user:fixture-password:with-colon".toByteArray()
    private val basic = "Basic " + Base64.getEncoder().encodeToString(bundle)

    @Test fun server401AndLocalRevocationRequireFreshCredentialsAndReselection() = runBlocking {
        val tls = FixtureTls(File(temporary.root, "fixture.p12"))
        val credentials = NetworkCredentials(SecretStore(SecretFiles(temporary.newFolder()), keys))
        WebDavFixture(tls).use { fixture ->
            val discovery = WebDavLibrary(fixture.endpoint, { true }, callPolicy(), null, null, tls.client())
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val ready: (RecordedRequest) -> MockResponse = {
                when {
                    it.headers["Authorization"] != basic -> reply(401)
                    it.method == "PROPFIND" -> directory()
                    it.headers["Range"] == "bytes=0-0" -> range(0, 0)
                    else -> range(2, 3)
                }
            }
            fixture.handler = ready
            assertEquals(NetworkFailure.AUTH_REQUIRED, discovery.verifyCredential("wrong:input".toByteArray()).reason())
            assertEquals(NetworkFailure.INVALID_INPUT, discovery.verifyCredential("bad\nuser:password".toByteArray()).reason())
            discovery.verifyCredential(bundle).value()
            val original = credentials.authorize(fixture.endpoint, "owner", bundle)
            val library = WebDavLibrary(fixture.endpoint, { true }, callPolicy(), credentials, original, tls.client())
            val song = library.song()
            val old = library.resolve(song).playback().deviceResource()
            fixture.handler = { reply(401) }
            assertEquals(NetworkFailure.AUTH_REQUIRED, library.readRange(old, 2, 2).reason())
            assertEquals(SecretState.AUTH_REQUIRED, original.state.value)
            assertEquals(SecretState.AUTH_REQUIRED, credentials.resume(fixture.endpoint, "owner").state.value)
            val blockedCalls = fixture.requests.size
            assertEquals(SourceResolution.Unavailable(ResolutionFailure.AUTH_REQUIRED), library.resolve(song))
            assertEquals(blockedCalls, fixture.requests.size)

            fixture.handler = ready
            discovery.verifyCredential(bundle).value()
            val fresh = credentials.authorize(fixture.endpoint, "owner", bundle)
            val recovered = WebDavLibrary(fixture.endpoint, { true }, callPolicy(8_000), credentials, fresh, tls.client())
            val refreshed = recovered.resolve(recovered.song()).playback().deviceResource()
            assertNotEquals(old.handle, refreshed.handle)
            assertEquals(NetworkFailure.STALE_RESPONSE, recovered.readRange(old, 2, 2).reason())
            assertEquals("cd", recovered.readRange(refreshed, 2, 2).value().decodeToString())

            fixture.handler = {
                MockResponse.Builder().code(200).setHeader("ETag", "\"original\"")
                    .setHeader("Content-Length", 6)
                    .stallBody(started, release)
            }
            val pending = async { recovered.readSequential(refreshed) { _, _ -> error("Revoked read emitted bytes") } }
            try {
                awaitStarted(started)
                credentials.requireAuthentication(fresh)
                withTimeout(2_000) { pending.join() }
                assertTrue(pending.isCancelled)
                assertEquals(SecretState.AUTH_REQUIRED, fresh.state.value)
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun httpsRedirectsNeverSendCredentialsToOtherOriginsAndCleartextAuthNeverStarts() = runBlocking {
        val tls = FixtureTls(File(temporary.root, "fixture.p12"))
        val credentials = NetworkCredentials(SecretStore(SecretFiles(temporary.newFolder()), keys))
        WebDavFixture(tls).use { fixture ->
            WebDavFixture().use { foreign ->
                val discovery = WebDavLibrary(fixture.endpoint, { true }, callPolicy(), null, null, tls.client())
                fixture.handler = { directory() }
                discovery.verifyCredential(bundle).value()
                val authorized = credentials.authorize(fixture.endpoint, "owner", bundle)
                val library = WebDavLibrary(fixture.endpoint, { true }, callPolicy(), credentials, authorized, tls.client())
                val song = library.song()
                fixture.handler = {
                    val redirect = foreign.endpoint.uri.replace("127.0.0.1", "localhost")
                    reply(302, headers = arrayOf("Location" to redirect))
                }
                assertEquals(SourceResolution.Unavailable(ResolutionFailure.INVALID_DESCRIPTOR), library.resolve(song))
                assertEquals(NetworkFailure.INVALID_RESPONSE, library.client.listMedia(DirectoryRequest(library.rootDirectoryId)).reason())
                assertTrue(fixture.requests.all { it.authorization == basic })
                assertTrue(foreign.requests.isEmpty())

                assertEquals(NetworkFailure.PERMISSION_REQUIRED, foreign.library().verifyCredential(bundle).reason())
                val wrongScope = WebDavLibrary(foreign.endpoint, { true }, callPolicy(), credentials, authorized)
                assertEquals(NetworkFailure.AUTH_REQUIRED, wrongScope.client.listMedia(DirectoryRequest(wrongScope.rootDirectoryId)).reason())
                assertTrue(foreign.requests.isEmpty())
            }
        }
    }
}
