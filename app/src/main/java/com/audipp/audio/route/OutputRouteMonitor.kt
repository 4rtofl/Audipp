package com.audipp.audio.route

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi
import com.audipp.domain.model.AudioRouteSnapshot
import com.audipp.domain.model.EvidenceLevel
import com.audipp.domain.model.Measured
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Android port IDs are valid for the current connection, not for saved device profiles.
internal data class OutputEndpoint(val portId: Int, val type: Int, val name: String?)

internal data class OutputCapabilities(
    val endpoint: OutputEndpoint,
    val sampleRatesHz: List<Int>,
    val channelCounts: List<Int>,
    val encodings: List<Int>,
)

internal data class OutputRouteState(
    val connectedOutputs: Measured<List<OutputCapabilities>>,
    val observedOutputs: Measured<List<OutputEndpoint>>,
    val route: AudioRouteSnapshot,
)

internal class OutputRouteMonitor(
    private val audioManager: AudioManager?,
    private val handler: Handler,
    private val queryRoutedDevices: (AudioTrack) -> List<AudioDeviceInfo> = ::androidRoutedDevices,
    private val onRouteRevisionChanged: (Long) -> Unit,
) : AutoCloseable {
    constructor(context: Context, onRouteRevisionChanged: (Long) -> Unit) : this(
        context.getSystemService(AudioManager::class.java), Handler(context.mainLooper),
        onRouteRevisionChanged = onRouteRevisionChanged,
    )

    private var closed = false
    private var activeOutput: Any? = null
    private var track: AudioTrack? = null
    private var removeRoutingListener: (() -> Unit)? = null
    private var lastRouteId: String? = null
    private var hasObservedRoute = false
    private val mutableSnapshot = MutableStateFlow(state(inventoryEvidence(readInventory()), routeEvidence(null), 0))
    val snapshot: StateFlow<OutputRouteState> = mutableSnapshot.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshInventory()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshInventory()
    }

    init {
        audioManager?.registerAudioDeviceCallback(deviceCallback, handler)
    }

    // Media3 invokes its output on the playback thread. The handler also owns the inventory and plan.
    fun onPlaying(output: Any, audioTrack: AudioTrack?) {
        handler.post {
            if (closed) return@post
            if (audioTrack != null && audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) return@post
            if (activeOutput !== output) {
                removeRoutingListener?.invoke()
                activeOutput = output
                track = audioTrack
                removeRoutingListener = audioTrack?.let { listenForRouting(it, output) }
            }
            refreshRoute()
        }
    }

    fun onInactive(output: Any) {
        handler.post {
            if (closed || activeOutput !== output) return@post
            detachOutput()
            refreshRoute()
        }
    }

    fun refreshRoute() = refresh(snapshot.value.connectedOutputs.value)

    override fun close() {
        if (closed) return
        closed = true
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        detachOutput()
        refreshRoute()
    }

    private fun detachOutput() {
        removeRoutingListener?.invoke()
        removeRoutingListener = null
        activeOutput = null
        track = null
    }

    private fun refreshInventory() {
        if (!closed) refresh(readInventory())
    }

    private fun refresh(inventory: List<OutputCapabilities>?) {
        val previous = snapshot.value
        val currentTrack = track
        val playing = currentTrack != null && currentTrack.playState == AudioTrack.PLAYSTATE_PLAYING
        val outputs = if (playing) readRoute(currentTrack) else null
        val routeId = outputs.toRouteId()
        var routeChanged = false
        // A normal pause or stop removes the live observation but does not establish a reroute.
        if (playing || (activeOutput != null && currentTrack == null)) {
            routeChanged = hasObservedRoute && routeId != lastRouteId
            lastRouteId = routeId
            if (routeId != null) hasObservedRoute = true
        }
        val inventoryChanged = inventory != previous.connectedOutputs.value
        if (!routeChanged && !inventoryChanged && outputs == previous.observedOutputs.value) return
        val revision = previous.route.routeRevision + if (routeChanged || inventoryChanged) 1 else 0
        if (revision != previous.route.routeRevision) onRouteRevisionChanged(revision)
        val connected = if (inventoryChanged) inventoryEvidence(inventory) else previous.connectedOutputs
        val observed = if (outputs != previous.observedOutputs.value) routeEvidence(outputs)
            else previous.observedOutputs
        mutableSnapshot.value = state(connected, observed, revision)
    }

    private fun readInventory(): List<OutputCapabilities>? = try {
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.asSequence()?.filter { it.isSink }
            ?.sortedBy { it.id }?.map {
                OutputCapabilities(it.endpoint(), it.sampleRates.toList(),
                    it.channelCounts.toList(), it.encodings.toList())
            }?.toList()
    } catch (_: SecurityException) {
        null
    }

    private fun readRoute(track: AudioTrack): List<OutputEndpoint>? = try {
        queryRoutedDevices(track).filter { it.isSink }.map { it.endpoint() }
            .distinctBy { it.portId }.sortedBy { it.portId }.ifEmpty { null }
    } catch (_: IllegalStateException) {
        // Media3 may release the track on its playback thread before its notification reaches us.
        null
    } catch (_: SecurityException) {
        null
    }

    private fun listenForRouting(track: AudioTrack, output: Any): () -> Unit {
        val onChanged = { if (activeOutput === output) refreshRoute() }
        return if (Build.VERSION.SDK_INT >= 24) {
            listenForRoutingApi24(track, onChanged)
        } else {
            listenForRoutingApi23(track, onChanged)
        }
    }

    @RequiresApi(24)
    private fun listenForRoutingApi24(track: AudioTrack, onChanged: () -> Unit): () -> Unit {
        val listener = AudioRouting.OnRoutingChangedListener { onChanged() }
        track.addOnRoutingChangedListener(listener, handler)
        return { track.removeOnRoutingChangedListener(listener) }
    }

    @Suppress("DEPRECATION")
    private fun listenForRoutingApi23(track: AudioTrack, onChanged: () -> Unit): () -> Unit {
        val listener = AudioTrack.OnRoutingChangedListener { onChanged() }
        track.addOnRoutingChangedListener(listener, handler)
        return { track.removeOnRoutingChangedListener(listener) }
    }

    private fun inventoryEvidence(value: List<OutputCapabilities>?) =
        measured(value, EvidenceLevel.OS_REPORTED, "AudioManager.getDevices", System.currentTimeMillis())

    private fun routeEvidence(value: List<OutputEndpoint>?): Measured<List<OutputEndpoint>> {
        val source = if (Build.VERSION.SDK_INT >= 36) "AudioTrack.getRoutedDevices"
            else "AudioTrack.getRoutedDevice"
        return measured(value, EvidenceLevel.APP_OBSERVED, source, System.currentTimeMillis())
    }

    private fun state(
        inventory: Measured<List<OutputCapabilities>>,
        outputs: Measured<List<OutputEndpoint>>,
        revision: Long,
    ): OutputRouteState {
        val source = outputs.sourceName
        val time = outputs.recordedAtEpochMs
        val unknownFormat = Measured<Int>(null, EvidenceLevel.UNKNOWN, source, time)
        val route = AudioRouteSnapshot(outputs.value.toRouteId(), revision,
            measured(outputs.value?.singleOrNull()?.name, EvidenceLevel.APP_OBSERVED, source, time),
            unknownFormat, unknownFormat, unknownFormat, dspEnabled = null)
        return OutputRouteState(inventory, outputs, route)
    }
}

private fun androidRoutedDevices(track: AudioTrack): List<AudioDeviceInfo> =
    if (Build.VERSION.SDK_INT >= 36) routedDevicesApi36(track) else listOfNotNull(track.routedDevice)

@RequiresApi(36)
private fun routedDevicesApi36(track: AudioTrack): List<AudioDeviceInfo> = track.routedDevices

private fun AudioDeviceInfo.endpoint() =
    OutputEndpoint(id, type, productName?.toString()?.takeIf { it.isNotBlank() })

private fun List<OutputEndpoint>?.toRouteId() = this?.joinToString(",", prefix = "android:") {
    "${it.type}:${it.portId}"
}

private fun <T> measured(value: T?, level: EvidenceLevel, source: String, time: Long) =
    Measured(value, if (value == null) EvidenceLevel.UNKNOWN else level, source, time)
