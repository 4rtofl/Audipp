package com.audipp.integrations.network.upnp

import com.audipp.integrations.network.CommandReceipt
import com.audipp.integrations.network.NetworkCallPolicy
import com.audipp.integrations.network.NetworkCalls
import com.audipp.integrations.network.NetworkEndpoint
import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.NetworkProtocol
import com.audipp.integrations.network.NetworkRendererClient
import com.audipp.integrations.network.NetworkResult
import com.audipp.integrations.network.RendererAdapter
import com.audipp.integrations.network.RendererCapabilities
import com.audipp.integrations.network.RendererCommand
import com.audipp.integrations.network.RendererConnection
import com.audipp.integrations.network.RendererDevice
import com.audipp.integrations.network.RendererObservation
import com.audipp.integrations.network.RendererPhase
import com.audipp.playback.source.ResolutionContext
import java.net.URI
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TRANSPORT_INFO = "GetTransportInfo"
private const val DISCOVERY_NAMES_MS = 1_000L
private const val POLL_INTERVAL_MS = 2_000L
private val rendererTypes = upnpSearchTargets - MEDIA_SERVER

internal class UpnpRendererAdapter(private val point: UpnpControlPoint) : RendererAdapter {
    private val lock = Any()
    private var candidates = emptyMap<RendererDevice, UpnpCandidate>()
    private var discoveryLabels = emptyMap<RendererDevice, String>()
    private var generation = 0L
    private var selected: RendererDevice? = null
    private var loaded: UpnpDevice? = null
    private var sequence = 0L

    override suspend fun discover(limit: Int): NetworkResult<List<RendererDevice>> =
        when (val result = point.discover(limit, rendererTypes)) {
            is NetworkResult.Failure -> result
            is NetworkResult.Success -> {
                val discovered = result.value.associateBy(::rendererDevice)
                val names = discovered.keys.associateWith { URI(it.endpoint.uri).rawAuthority }.toMutableMap()
                withTimeoutOrNull(DISCOVERY_NAMES_MS) {
                    for ((device, candidate) in discovered) {
                        val name = point.describe(candidate)
                        if (name is NetworkResult.Success) names[device] = name.value
                        if (name == NetworkResult.Failure(NetworkFailure.PERMISSION_REQUIRED)) break
                    }
                }
                val shortLabels = names.mapValues { (device, name) -> shortLabel(device, name) }
                val labelCounts = shortLabels.values.groupingBy { it }.eachCount()
                val labels = shortLabels.mapValues { (device, label) ->
                    if (labelCounts.getValue(label) > 1) "${names.getValue(device)}\n${device.id}" else label
                }
                synchronized(lock) {
                    candidates = discovered
                    discoveryLabels = labels
                    NetworkResult.Success(discovered.keys.toList())
                }
            }
        }

    suspend fun manual(input: String): NetworkResult<RendererDevice> =
        when (val result = point.manual(input, rendererTypes)) {
            is NetworkResult.Failure -> result
            is NetworkResult.Success -> synchronized(lock) {
                val device = rendererDevice(result.value)
                candidates = mapOf(device to result.value)
                discoveryLabels = emptyMap()
                NetworkResult.Success(device)
            }
        }

    fun label(device: RendererDevice): String = synchronized(lock) {
        val connectedName = loaded?.name?.takeIf { selected === device }
        connectedName?.let { shortLabel(device, it) } ?: discoveryLabels[device] ?: shortLabel(device, candidates[device]?.name)
    }

    private fun shortLabel(device: RendererDevice, name: String?): String {
        val label = name ?: URI(device.endpoint.uri).rawAuthority
        return "$label · ${device.id.takeLast(12)}"
    }

    fun openSession(
        device: RendererDevice,
        context: ResolutionContext,
        currentContext: () -> ResolutionContext,
    ): UpnpRendererSession? = synchronized(lock) {
        val candidate = candidates[device] ?: return@synchronized null
        // The control point owns the renewable deadline for this connection.
        val sessionDevice = RendererDevice(device.id, device.endpoint, Long.MAX_VALUE)
        val connection = UpnpRendererAdapter(point).apply {
            candidates = mapOf(sessionDevice to candidate)
            discoveryLabels[device]?.let { discoveryLabels = mapOf(sessionDevice to it) }
        }
        UpnpRendererSession(connection, sessionDevice, context, currentContext)
    }

    override suspend fun connect(device: RendererDevice, credential: ByteArray?): NetworkResult<String> {
        if (credential != null) return NetworkResult.Failure(NetworkFailure.UNSUPPORTED)
        val (candidate, token) = synchronized(lock) {
            val candidate = candidates[device] ?: return NetworkResult.Failure(NetworkFailure.INVALID_INPUT)
            selected = device
            loaded = null
            candidate to ++generation
        }
        val result = point.load(candidate)
        return synchronized(lock) {
            if (token != generation) return@synchronized NetworkResult.Failure(NetworkFailure.STALE_RESPONSE)
            when (result) {
                is NetworkResult.Failure -> result
                is NetworkResult.Success -> {
                    loaded = result.value
                    NetworkResult.Success(device.id)
                }
            }
        }
    }

    override suspend fun getCapabilities(device: RendererDevice, credential: ByteArray?): NetworkResult<RendererCapabilities> =
        withDevice(device, credential) {
            if (point.isExpired(it)) return@withDevice NetworkResult.Failure(NetworkFailure.EXPIRED)
            val info = it.services[AV_TRANSPORT]?.actions?.get(TRANSPORT_INFO)
            val canObserve = info?.input == setOf("InstanceID") && "CurrentTransportState" in info.output
            NetworkResult.Success(RendererCapabilities(canObserveState = canObserve))
        }

    override suspend fun observeState(device: RendererDevice, credential: ByteArray?): NetworkResult<RendererObservation> =
        withDevice(device, credential) {
            when (val result = point.invoke(it, AV_TRANSPORT, TRANSPORT_INFO, mapOf("InstanceID" to "0"))) {
                is NetworkResult.Failure -> result
                is NetworkResult.Success -> {
                    val phase = when (result.value["CurrentTransportState"]?.trim()) {
                        "STOPPED", "NO_MEDIA_PRESENT" -> RendererPhase.STOPPED
                        "PLAYING" -> RendererPhase.PLAYING
                        "PAUSED_PLAYBACK" -> RendererPhase.PAUSED
                        else -> RendererPhase.UNKNOWN
                    }
                    val serial = synchronized(lock) { ++sequence }
                    NetworkResult.Success(RendererObservation(device.id, serial, phase))
                }
            }
        }

    override suspend fun sendCommand(
        device: RendererDevice,
        command: RendererCommand,
        credential: ByteArray?,
    ): NetworkResult<CommandReceipt> = NetworkResult.Failure(NetworkFailure.UNSUPPORTED)

    suspend fun invoke(
        device: RendererDevice,
        service: String,
        action: String,
        arguments: Map<String, String>,
    ): NetworkResult<UpnpSoapResult> = withDevice(device, null) {
        point.invoke(it, service, action, arguments)
    }

    override suspend fun disconnect(device: RendererDevice): NetworkResult<Unit> = synchronized(lock) {
        if (device === selected) {
            generation++
            selected = null
            loaded = null
        }
        NetworkResult.Success(Unit)
    }

    private suspend fun <T> withDevice(
        device: RendererDevice,
        credential: ByteArray?,
        operation: suspend (UpnpDevice) -> NetworkResult<T>,
    ): NetworkResult<T> {
        if (credential != null) return NetworkResult.Failure(NetworkFailure.UNSUPPORTED)
        val active = synchronized(lock) { loaded.takeIf { selected === device } }
            ?: return NetworkResult.Failure(NetworkFailure.OFFLINE)
        val response = operation(active)
        return synchronized(lock) {
            if (loaded === active) response else NetworkResult.Failure(NetworkFailure.STALE_RESPONSE)
        }
    }

    private fun rendererDevice(candidate: UpnpCandidate): RendererDevice {
        val protocol = if (candidate.type.startsWith("urn:av-openhome-org:")) NetworkProtocol.OPENHOME else NetworkProtocol.UPNP
        return RendererDevice(candidate.udn, NetworkEndpoint(protocol, candidate.peer.location.toString()), candidate.expiresAt)
    }
}

internal class UpnpRendererSession(
    private val adapter: UpnpRendererAdapter,
    private val device: RendererDevice,
    private val context: ResolutionContext,
    private val currentContext: () -> ResolutionContext,
) {
    private val client = NetworkRendererClient(
        adapter, device, context, currentContext,
        calls = NetworkCalls(NetworkCallPolicy(maxAttempts = 1)),
    )
    private val requests = Mutex()
    val label: String get() = adapter.label(device)
    val state = client.state

    suspend fun connect(): NetworkResult<Unit> = when (val result = client.connect()) {
        is NetworkResult.Failure -> result
        is NetworkResult.Success -> when (val capabilities = client.getCapabilities()) {
            is NetworkResult.Failure -> capabilities
            is NetworkResult.Success -> NetworkResult.Success(Unit)
        }
    }

    suspend fun poll() {
        while (canObserve()) {
            val result = requests.withLock { client.observeState() }
            if (result is NetworkResult.Failure && result.reason in stopPolling) return
            delay(POLL_INTERVAL_MS)
        }
    }

    suspend fun invoke(
        service: String,
        action: String,
        arguments: Map<String, String>,
    ): NetworkResult<UpnpSoapResult> = requests.withLock {
        if (currentContext() != context) return@withLock NetworkResult.Failure(NetworkFailure.STALE_RESPONSE)
        if (state.value.connection != RendererConnection.CONNECTED) {
            return@withLock NetworkResult.Failure(NetworkFailure.OFFLINE)
        }
        val result = adapter.invoke(device, service, action, arguments)
        if (currentContext() != context || state.value.connection != RendererConnection.CONNECTED) {
            return@withLock NetworkResult.Failure(NetworkFailure.STALE_RESPONSE)
        }
        if (canObserve()) client.observeState()
        result
    }

    suspend fun disconnect(): NetworkResult<Unit> = client.disconnect()

    private fun canObserve(): Boolean = state.value.connection == RendererConnection.CONNECTED &&
        state.value.capabilities?.canObserveState == true && currentContext() == context

    private companion object {
        val stopPolling = setOf(
            NetworkFailure.PERMISSION_REQUIRED, NetworkFailure.AUTH_REQUIRED, NetworkFailure.OFFLINE,
            NetworkFailure.EXPIRED, NetworkFailure.STALE_RESPONSE, NetworkFailure.UNSUPPORTED,
        )
    }
}
