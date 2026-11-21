package com.audipp.integrations.network.upnp

import com.audipp.integrations.network.NetworkCalls
import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.NetworkResult
import java.net.URI
import java.net.UnknownHostException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class UpnpCandidate(
    val udn: String,
    val type: String,
    val peer: UpnpPeer,
    val expiresAt: Long,
    val lifetimeMs: Long,
    val selection: Any,
    val name: String? = null,
) {
    override fun toString() = "UpnpCandidate(redacted)"
}

internal class UpnpService(val location: UpnpServiceLocation, val actions: Map<String, UpnpAction>)
internal class UpnpDevice(
    val candidate: UpnpCandidate,
    val name: String,
    val services: Map<String, UpnpService>,
    @Volatile var expiresAt: Long,
)

internal class UpnpControlPoint(
    private val network: UpnpNetwork,
    private val calls: NetworkCalls = NetworkCalls(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val selection = Any()

    suspend fun discover(
        limit: Int,
        targets: Set<String> = upnpSearchTargets,
    ): NetworkResult<List<UpnpCandidate>> = call(UPNP_SEARCH_MS + 1_000) {
        require(limit in 1..MAX_UPNP_ITEMS && targets.isNotEmpty() && upnpSearchTargets.containsAll(targets))
        val found = linkedMapOf<String, UpnpCandidate>()
        network.search(targets) { advertisement ->
            if (targets.any { upnpTypeMatches(advertisement.target, it) } &&
                network.isLocal(advertisement.peer.address)
            ) {
                val existing = found[advertisement.udn]
                if (existing == null) {
                    val lifetimeMs = advertisement.maxAgeSeconds * 1000
                    found[advertisement.udn] = UpnpCandidate(
                        advertisement.udn, advertisement.target, advertisement.peer,
                        now() + lifetimeMs, lifetimeMs, selection,
                    )
                }
            }
            found.size < limit
        }
        found.values.filter { it.expiresAt > now() && network.isLocal(it.peer.address) }
    }

    suspend fun manual(input: String, targets: Set<String> = upnpSearchTargets): NetworkResult<UpnpCandidate> =
        call(calls.policy.connectTimeoutMs) {
            val value = input.trim()
            val uri = httpUri(if ("://" in value) value else "http://$value")
                ?: throw UpnpException(NetworkFailure.INVALID_INPUT)
            val addresses = numericAddress(uri.host)?.let(::listOf) ?: try {
                network.resolve(uri.host)
            } catch (_: UnknownHostException) {
                throw UpnpException(NetworkFailure.OFFLINE)
            }
            val address = addresses.firstOrNull(network::isLocal) ?: throw UpnpException(NetworkFailure.INVALID_INPUT)
            val peer = UpnpPeer(uri, address)
            val description = deviceDescription(readXml(peer, uri), peer, null, targets)
            val lifetimeMs = 5 * 60_000L
            UpnpCandidate(description.udn, description.type, peer, now() + lifetimeMs, lifetimeMs, selection, description.name)
                .also(::checkCandidate)
        }

    suspend fun describe(candidate: UpnpCandidate): NetworkResult<String> = call(calls.policy.connectTimeoutMs) {
        checkCandidate(candidate)
        readDescription(candidate).name
    }

    suspend fun load(candidate: UpnpCandidate): NetworkResult<UpnpDevice> = call(calls.policy.connectTimeoutMs) {
        checkCandidate(candidate)
        val peer = candidate.peer
        val description = readDescription(candidate)
        val services = description.services.mapValues { (_, location) ->
            UpnpService(location, serviceActions(readXml(peer, location.description)))
        }
        checkLocal(peer)
        UpnpDevice(candidate, description.name, services, now() + candidate.lifetimeMs)
    }

    suspend fun invoke(
        device: UpnpDevice,
        serviceType: String,
        name: String,
        arguments: Map<String, String>,
    ): NetworkResult<UpnpSoapResult> = call(calls.policy.operationTimeoutMs) {
        checkCandidate(device.candidate, device.expiresAt)
        val service = device.services[serviceType] ?: throw UpnpException(NetworkFailure.UNSUPPORTED)
        val action = service.actions[name] ?: throw UpnpException(NetworkFailure.UNSUPPORTED)
        val post = soapRequest(service.location, name, action, arguments)
        val response = network.exchange(device.candidate.peer, service.location.control, post)
        val context = currentCoroutineContext()
        val result = soapResponse(
            parseUpnpXml(response.body) { context.ensureActive() }, response.status, service.location, name, action,
        )
        checkLocal(device.candidate.peer)
        context.ensureActive()
        device.expiresAt = now() + device.candidate.lifetimeMs
        result
    }

    fun isExpired(device: UpnpDevice): Boolean = device.expiresAt <= now()

    private suspend fun readDescription(candidate: UpnpCandidate): UpnpDescription {
        val peer = candidate.peer
        return deviceDescription(readXml(peer, peer.location), peer, candidate)
    }

    private suspend fun readXml(peer: UpnpPeer, uri: URI): UpnpXml {
        val response = network.exchange(peer, uri)
        if (response.status != 200) invalidUpnpResponse()
        val context = currentCoroutineContext()
        return parseUpnpXml(response.body) { context.ensureActive() }
    }

    private fun checkCandidate(candidate: UpnpCandidate, expiresAt: Long = candidate.expiresAt) {
        if (candidate.selection !== selection) throw UpnpException(NetworkFailure.STALE_RESPONSE)
        if (expiresAt <= now()) throw UpnpException(NetworkFailure.EXPIRED)
        checkLocal(candidate.peer)
    }

    private fun checkLocal(peer: UpnpPeer) {
        if (!network.isLocal(peer.address)) throw UpnpException(NetworkFailure.OFFLINE)
    }

    private suspend fun <T> call(timeoutMs: Long, body: suspend () -> T): NetworkResult<T> =
        calls.run(retrySafe = false, timeoutMs = timeoutMs, rejection = network::rejection) {
            try {
                NetworkResult.Success(body())
            } catch (failure: UpnpException) {
                NetworkResult.Failure(failure.failure)
            } catch (_: SecurityException) {
                // Nearby Wi-Fi permission recovery requires a socket permission errno.
                NetworkResult.Failure(NetworkFailure.IO)
            }
        }
}
