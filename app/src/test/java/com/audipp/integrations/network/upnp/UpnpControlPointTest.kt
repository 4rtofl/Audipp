package com.audipp.integrations.network.upnp

import com.audipp.domain.model.TransitionId
import com.audipp.integrations.network.NetworkCallPolicy
import com.audipp.integrations.network.NetworkCalls
import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.NetworkResult
import com.audipp.integrations.network.RendererConnection
import com.audipp.integrations.network.RendererDiscovery
import com.audipp.integrations.network.RendererPhase
import com.audipp.playback.source.PlaybackTarget
import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.TargetKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpControlPointTest {
    @Test fun discoveryIsBoundedDeduplicatedScopedAndExpiring() = runBlocking {
        var time = 1000L
        val network = FixtureNetwork()
        val duplicate = checkNotNull(advertisement(url = "http://192.168.10.20:9999/changed"))
        val outsideAddress = checkNotNull(numericAddress("192.168.11.20"))
        val outside = checkNotNull(advertisement(url = "http://192.168.11.20/device", sender = outsideAddress))
        val second = checkNotNull(advertisement(udn = "uuid:second"))
        network.adverts = listOf(checkNotNull(advertisement()), duplicate, outside, second)
        val point = UpnpControlPoint(network, now = { time })
        val result = success(point.discover(2))
        assertEquals(listOf(TEST_UDN, "uuid:second"), result.map { it.udn })
        assertEquals(TEST_URL, result.first().peer.location.toString())
        assertEquals(listOf("SEARCH"), network.events)
        val otherNetwork = FixtureNetwork()
        val otherSelection = UpnpControlPoint(otherNetwork, now = { time })
        assertEquals(NetworkResult.Failure(NetworkFailure.STALE_RESPONSE), otherSelection.load(result.first()))
        assertTrue(otherNetwork.events.isEmpty())
        time += 120_000
        assertEquals(NetworkResult.Failure(NetworkFailure.EXPIRED), point.load(result.first()))
        assertEquals(listOf("SEARCH"), network.events)
    }

    @Test fun manualHostResolvesOnceAndNeverFollowsAnOffNetworkAnswer() = runBlocking {
        val network = FixtureNetwork()
        val point = UpnpControlPoint(network)
        val external = checkNotNull(numericAddress("192.168.11.20"))
        network.dns = listOf(external)
        val host = "renderer.example:8000/upnp/device.xml"
        assertEquals(NetworkResult.Failure(NetworkFailure.INVALID_INPUT), point.manual(host))
        assertEquals(listOf("DNS renderer.example"), network.events)
        network.dns = listOf(external, testAddress)
        val device = success(point.manual(host))
        network.dns = listOf(external)
        val loaded = success(point.load(device))
        assertEquals(setOf(AV_TRANSPORT), loaded.services.keys)
        assertEquals(2, network.events.count { it == "DNS renderer.example" })
        network.localMembers = emptySet()
        assertEquals(NetworkResult.Failure(NetworkFailure.OFFLINE), point.load(device))
        assertEquals(2, network.events.count { it == "GET /upnp/device.xml" })
    }

    @Test fun manualSelectsAnEmbeddedRendererWithANewerDeviceType() = runBlocking {
        val network = FixtureNetwork()
        val renderer = deviceXml(type = "${UPNP_DEVICE_PREFIX}MediaRenderer:3", udn = "uuid:RINCON_123_MR")
            .substringAfter("<device>").substringBeforeLast("</device>")
        val description = deviceXml(type = MEDIA_SERVER, service = CONTENT_DIRECTORY)
            .replace("</device>", "<deviceList><device>$renderer</device></deviceList></device>")
        val original = network.exchangeResponse
        network.exchangeResponse = { uri, post ->
            if (uri.path.endsWith("device.xml")) response(description) else original(uri, post)
        }
        val adapter = UpnpRendererAdapter(UpnpControlPoint(network))
        val device = success(adapter.manual(TEST_URL))
        assertEquals("uuid:rincon_123_mr", device.id)
        success(adapter.connect(device, null))
        assertTrue(success(adapter.getCapabilities(device, null)).canObserveState)
    }

    @Test fun descriptionCanDeclareANewerVersionThanTheSsdpTarget() = runBlocking {
        val network = FixtureNetwork()
        val original = network.exchangeResponse
        network.exchangeResponse = { uri, post ->
            if (uri.path.endsWith("device.xml")) {
                response(deviceXml(type = "${UPNP_DEVICE_PREFIX}MediaRenderer:2"))
            } else original(uri, post)
        }
        val point = UpnpControlPoint(network)
        val candidate = success(point.discover(1)).single()
        assertEquals(setOf(AV_TRANSPORT), success(point.load(candidate)).services.keys)
    }

    @Test fun identityMismatchPreventsAnyScpdOrControlRequest() = runBlocking {
        val network = FixtureNetwork()
        network.exchangeResponse = { _, _ -> response(deviceXml(udn = "uuid:changed")) }
        val point = UpnpControlPoint(network)
        val device = success(point.discover(1)).single()
        assertEquals(NetworkResult.Failure(NetworkFailure.INVALID_RESPONSE), point.load(device))
        assertEquals(listOf("SEARCH", "GET /upnp/device.xml"), network.events)
    }

    @Test fun denialRevocationAndExplicitRetryDoNotPublishUnauthorizedSuccess() = runBlocking {
        val network = FixtureNetwork()
        val point = UpnpControlPoint(network)
        network.failure = NetworkFailure.PERMISSION_REQUIRED
        assertEquals(NetworkResult.Failure(NetworkFailure.PERMISSION_REQUIRED), point.discover(1))
        assertEquals(emptyList<String>(), network.events)
        network.failure = null
        val candidate = success(point.discover(1)).single()
        network.exchangeResponse = { _, _ ->
            network.failure = NetworkFailure.PERMISSION_REQUIRED
            response(deviceXml())
        }
        assertEquals(NetworkResult.Failure(NetworkFailure.PERMISSION_REQUIRED), point.load(candidate))
        network.failure = null
        network.exchangeResponse = { uri, _ ->
            if (uri.path.endsWith("device.xml")) response(deviceXml()) else response(scpdXml())
        }
        assertEquals(setOf(AV_TRANSPORT), success(point.load(candidate)).services.keys)
    }

    @Test fun timeoutAndCancellationReleaseRequestsAndAllowAUserRetry() = runBlocking {
        val network = FixtureNetwork()
        val calls = NetworkCalls(NetworkCallPolicy(connectTimeoutMs = 100, maxAttempts = 1))
        val point = UpnpControlPoint(network, calls)
        val callerPoint = UpnpControlPoint(network)
        val candidate = success(point.discover(1)).single()
        val retryCandidate = success(callerPoint.discover(1)).single()
        val released = CompletableDeferred<Unit>()
        network.exchangeResponse = { _, _ ->
            try {
                awaitCancellation()
            } finally {
                released.complete(Unit)
            }
        }
        assertEquals(NetworkResult.Failure(NetworkFailure.TIMEOUT), point.load(candidate))
        assertTrue(released.isCompleted)
        val callerEntered = CompletableDeferred<Unit>()
        val callerReleased = CompletableDeferred<Unit>()
        network.exchangeResponse = { _, _ ->
            callerEntered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                callerReleased.complete(Unit)
            }
        }
        val job = launch { callerPoint.load(retryCandidate) }
        withTimeout(5_000) { callerEntered.await() }
        job.cancelAndJoin()
        assertTrue(callerReleased.isCompleted)
        network.exchangeResponse = { uri, _ ->
            if (uri.path.endsWith("device.xml")) response(deviceXml()) else response(scpdXml())
        }
        assertEquals(setOf(AV_TRANSPORT), success(callerPoint.load(retryCandidate)).services.keys)
    }

    @Test fun sessionPollsOnlyWhenConnectedAndReadsStateAfterAdvertisedAction() = runBlocking {
        val network = FixtureNetwork()
        val adapter = UpnpRendererAdapter(UpnpControlPoint(network))
        val device = success(RendererDiscovery(adapter).discover()).single()
        val context = ResolutionContext(PlaybackTarget(device.id, TargetKind.RENDERER), 0, TransitionId("test"))
        val session = checkNotNull(adapter.openSession(device, context) { context })
        session.poll()
        assertEquals(listOf("SEARCH", "GET /upnp/device.xml"), network.events)

        success(session.connect())
        assertTrue(session.state.value.capabilities?.canObserveState == true)
        assertTrue(session.state.value.capabilities?.actions.orEmpty().isEmpty())
        assertFalse(session.state.value.capabilities?.independentQueue == true)
        assertNull(session.state.value.observation)
        assertEquals(NetworkResult.Failure(NetworkFailure.UNSUPPORTED), session.invoke(AV_TRANSPORT, "Unknown", emptyMap()))
        assertFalse(network.events.any { it.startsWith("POST") && it.endsWith("#Unknown") })
        network.events.clear()
        success(session.invoke(AV_TRANSPORT, "Play", mapOf("Speed" to "1", "InstanceID" to "0")))
        val read = "POST $AV_TRANSPORT#GetTransportInfo"
        assertEquals(listOf("POST $AV_TRANSPORT#Play", read), network.events)
        assertEquals(RendererPhase.PLAYING, session.state.value.observation?.phase)

        val initial = checkNotNull(session.state.value.observation).sequence
        val polling = launch { session.poll() }
        withTimeout(5_000) { session.state.first { (it.observation?.sequence ?: -1) >= initial + 2 } }
        assertEquals(3, network.events.count { it == read })
        success(session.disconnect())
        polling.cancelAndJoin()
        session.poll()
        assertEquals(3, network.events.count { it == read })
        assertEquals(RendererConnection.DISCONNECTED, session.state.value.connection)
    }

    @Test fun discoveryLabelsUseNamesAndTheConnectionUsesTheRefreshedName() = runBlocking {
        val network = FixtureNetwork()
        val otherUrl = TEST_URL.replace("device.xml", "other.xml")
        network.adverts = listOfNotNull(advertisement(), advertisement(url = otherUrl, udn = "uuid:other"))
        val original = network.exchangeResponse
        network.exchangeResponse = { uri, post ->
            if (uri.path.endsWith("other.xml")) response(deviceXml(udn = "uuid:other")) else original(uri, post)
        }
        val adapter = UpnpRendererAdapter(UpnpControlPoint(network))
        val (first, second) = success(adapter.discover(2))
        assertTrue(adapter.label(first).startsWith("Fixture & Renderer"))
        assertTrue(adapter.label(second).startsWith("Fixture & Renderer"))
        assertTrue(adapter.label(first) != adapter.label(second))
        val context = ResolutionContext(PlaybackTarget(first.id, TargetKind.RENDERER), 0, TransitionId("label"))
        val session = checkNotNull(adapter.openSession(first, context) { context })
        network.exchangeResponse = { uri, post ->
            if (uri.path.endsWith("device.xml")) {
                response(deviceXml().replace("Fixture &amp; Renderer", "Updated Renderer"))
            } else original(uri, post)
        }
        success(session.connect())
        assertTrue(session.label.startsWith("Updated Renderer"))
        network.exchangeResponse = { _, _ -> response("<invalid/>") }
        val unnamed = success(adapter.discover(1)).single()
        assertTrue(adapter.label(unnamed).startsWith("192.168.10.20:8000"))
    }

    @Test fun duplicateDiscoveryLabelsShowTheFullUdn() = runBlocking {
        val ids = listOf(
            "uuid:12345678-abcd-1234-8000-aabbccddeeff",
            "uuid:87654321-abcd-1234-8000-aabbccddeeff",
            "uuid:12345678-abcd-1234-8000-ffeeddccbbaa",
        )
        val network = FixtureNetwork()
        network.adverts = ids.mapIndexed { index, udn ->
            checkNotNull(advertisement(url = TEST_URL.replace("device.xml", "$index.xml"), udn = udn))
        }
        network.exchangeResponse = { uri, _ ->
            val index = uri.path.substringAfterLast('/').substringBefore('.').toInt()
            response(deviceXml(udn = ids[index]))
        }
        val adapter = UpnpRendererAdapter(UpnpControlPoint(network))
        val devices = success(adapter.discover(ids.size)).associateBy { it.id }
        assertEquals("Fixture & Renderer\n${ids[0]}", adapter.label(devices.getValue(ids[0])))
        assertEquals("Fixture & Renderer\n${ids[1]}", adapter.label(devices.getValue(ids[1])))
        assertEquals("Fixture & Renderer · ffeeddccbbaa", adapter.label(devices.getValue(ids[2])))
    }

    @Test fun successfulDescriptionAndSoapExtendOnlyTheConnectedSession() = runBlocking {
        var time = System.currentTimeMillis()
        val network = FixtureNetwork()
        network.adverts = listOf(checkNotNull(advertisement(maxAge = 10)))
        val adapter = UpnpRendererAdapter(UpnpControlPoint(network, now = { time }))
        val device = success(adapter.discover(1)).single()
        val context = ResolutionContext(PlaybackTarget(device.id, TargetKind.RENDERER), 0, TransitionId("expiry"))
        val session = checkNotNull(adapter.openSession(device, context) { context })
        time += 9_000
        success(session.connect())
        time += 8_000
        val polling = launch { session.poll() }
        withTimeout(5_000) { session.state.first { it.observation?.phase == RendererPhase.PLAYING } }
        polling.cancelAndJoin()

        time += 9_000
        success(session.invoke(AV_TRANSPORT, "Play", mapOf("Speed" to "1", "InstanceID" to "0")))
        assertEquals(NetworkResult.Failure(NetworkFailure.EXPIRED), adapter.connect(device, null))
        val original = network.exchangeResponse
        network.exchangeResponse = { uri, post ->
            if (post != null) response("<invalid/>") else original(uri, post)
        }
        time += 9_000
        val play = mapOf("Speed" to "1", "InstanceID" to "0")
        assertEquals(NetworkResult.Failure(NetworkFailure.INVALID_RESPONSE), session.invoke(AV_TRANSPORT, "Play", play))
        time += 1_000
        val eventsBeforeExpiry = network.events.toList()
        assertEquals(NetworkResult.Failure(NetworkFailure.EXPIRED), session.invoke(AV_TRANSPORT, "Play", play))
        assertEquals(eventsBeforeExpiry, network.events.toList())
    }

    @Test fun delayedResponseCannotOverwriteAnotherRouteOrReconnectedSession() = runBlocking {
        val network = FixtureNetwork()
        val adapter = UpnpRendererAdapter(UpnpControlPoint(network))
        val device = success(adapter.discover(1)).single()
        val context = ResolutionContext(PlaybackTarget(device.id, TargetKind.RENDERER), 0, TransitionId("old"))
        val next = context.copy(routeRevision = 1, transitionId = TransitionId("new"))
        val current = java.util.concurrent.atomic.AtomicReference(context)
        val oldSession = checkNotNull(adapter.openSession(device, context, current::get))
        val newSession = checkNotNull(adapter.openSession(device, next, current::get))
        success(oldSession.connect())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val normal = network.exchangeResponse
        network.exchangeResponse = { uri, post ->
            if (post != null) {
                entered.complete(Unit)
                release.await()
            }
            normal(uri, post)
        }
        val delayed = async { oldSession.poll() }
        withTimeout(5_000) { entered.await() }
        current.set(next)
        success(newSession.connect())
        success(oldSession.disconnect())
        release.complete(Unit)
        withTimeout(5_000) { delayed.await() }
        assertNull(oldSession.state.value.observation)
        assertEquals(RendererConnection.CONNECTED, newSession.state.value.connection)
        val nextPoll = launch { newSession.poll() }
        withTimeout(5_000) { newSession.state.first { it.observation?.phase == RendererPhase.PLAYING } }
        nextPoll.cancelAndJoin()
        assertEquals(RendererConnection.CONNECTED, newSession.state.value.connection)
    }

    private fun <T> success(result: NetworkResult<T>): T {
        assertTrue(result.toString(), result is NetworkResult.Success)
        return (result as NetworkResult.Success).value
    }
}
