package com.audipp.audio.route

import android.media.AudioDeviceInfo
import android.os.Build
import com.audipp.domain.device.AudioPreset
import com.audipp.domain.device.AudioPresetSettings
import com.audipp.domain.device.OutputBackend
import com.audipp.domain.device.OutputMode
import com.audipp.domain.device.OutputTarget
import com.audipp.domain.device.VolumeSettings
import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class OutputConfirmation { ROUTE_CHANGED, ROUTE_UNKNOWN, DAC_VOLUME_UNKNOWN, PROFILE_VOLUME }

internal sealed interface OutputSafetyState {
    data object Observing : OutputSafetyState
    data object Ready : OutputSafetyState

    data class ConfirmationRequired(
        val id: Long,
        val reason: OutputConfirmation,
        val route: OutputEndpoint?,
        val restore: VolumeSettings? = null,
        val limit: VolumeSettings? = null,
        val physicalGainNote: String? = null,
    ) : OutputSafetyState
}

internal enum class ProfileApplication { APPLIED, CONFIRMATION_REQUIRED, STALE_ROUTE, UNSUPPORTED }

// Owned by the service looper. Setting zero here closes the AudioTrack gate before requesting pause.
internal class OutputSafety(
    private val androidVolumeStep: () -> Int,
    private val pause: () -> Unit,
) {
    private val mutableState = MutableStateFlow<OutputSafetyState>(OutputSafetyState.Observing)
    val state = mutableState.asStateFlow()
    var revision = 0L
        private set
    private var approved: OutputEndpoint? = null
    private var digitalGainDb = 0.0
    private var muted = false
    private var limit: VolumeSettings? = null
    private var confirmedRestore: VolumeSettings? = null
    private var setGain: ((Float) -> Unit)? = null

    fun onObservation(outputs: List<OutputEndpoint>?, setGain: (Float) -> Unit) {
        this.setGain = setGain
        val route = outputs?.singleOrNull()?.takeUnless { it.type == AudioDeviceInfo.TYPE_UNKNOWN }
        val pending = state.value as? OutputSafetyState.ConfirmationRequired
        if (pending != null) {
            if (!sameRoute(pending.route, route)) {
                requireRouteConfirmation(route)
            } else {
                setGain(0f)
                pause()
            }
        } else if (route == null || (approved != null && !sameRoute(approved, route)) ||
            (approved == null && route.isUsb())
        ) {
            requireRouteConfirmation(route)
        } else {
            if (approved == null) revision++
            approved = route
            mutableState.value = OutputSafetyState.Ready
            if (canPlay()) {
                confirmedRestore?.let { restore ->
                    restore.digitalGainDb?.let { digitalGainDb = it.coerceAtMost(limit?.digitalGainDb ?: 0.0) }
                    restore.muted?.let { muted = it }
                }
                confirmedRestore = null
                setGain(if (muted) 0f else gainFor(digitalGainDb))
            }
        }
    }

    fun onInactive() {
        setGain = null
    }

    fun onRemoved(portIds: Set<Int>): Boolean {
        val pending = state.value as? OutputSafetyState.ConfirmationRequired
        val portId = (approved ?: pending?.route)?.portId ?: return false
        if (portId !in portIds) return false
        requireRouteConfirmation(null)
        return true
    }

    fun canPlay(): Boolean {
        if (state.value is OutputSafetyState.ConfirmationRequired) return false
        val ceiling = limit?.androidVolumeStep
        if (ceiling != null && androidVolumeStep() > ceiling) {
            setGain?.invoke(0f)
            publishConfirmation(OutputConfirmation.PROFILE_VOLUME, approved, limit = limit)
            pause()
            return false
        }
        return true
    }

    // The caller obtained an explicit confirmation for this state, including any manual DAC check.
    // Playback still has to probe again at zero gain before opening the gate.
    fun confirm(id: Long, restoreDigitalVolume: Boolean): Boolean {
        val pending = state.value as? OutputSafetyState.ConfirmationRequired ?: return false
        if (pending.id != id || pending.route == null ||
            pending.limit?.androidVolumeStep?.let { androidVolumeStep() > it } == true
        ) return false
        confirmedRestore = pending.restore.takeIf { restoreDigitalVolume && pending.limit?.muted != true }
        revision++
        approved = pending.route
        mutableState.value = OutputSafetyState.Ready
        return true
    }

    // Unknown observations can only trigger another silent probe; Play by itself cannot confirm them.
    fun canRetryObservation(id: Long): Boolean {
        val pending = state.value as? OutputSafetyState.ConfirmationRequired ?: return false
        return pending.id == id && pending.route == null
    }

    fun applyProfile(
        expectedRevision: Long,
        preset: AudioPreset,
        applyBackend: (OutputBackend) -> Boolean,
    ): ProfileApplication {
        val route = approved
        if (revision != expectedRevision || route == null || state.value != OutputSafetyState.Ready) {
            return ProfileApplication.STALE_ROUTE
        }
        val backend = preset.settings.outputBackend ?: OutputBackend.ANDROID_STANDARD
        val settings = preset.settings.copy(outputBackend = null)
        setGain?.invoke(0f)
        pause()
        if (!applyBackend(backend) || !settings.supportedOn(route) || preset.impulseResponseUri != null) {
            return ProfileApplication.UNSUPPORTED
        }
        confirmedRestore = null
        limit = preset.safeVolume
        limit?.digitalGainDb?.let { digitalGainDb = minOf(digitalGainDb, it) }
        if (limit?.muted == true) muted = true
        val restore = preset.initialVolume ?: preset.defaultVolume
        if (restore != null || limit?.hardwareVolume != null || preset.physicalGainNote != null ||
            limit?.androidVolumeStep?.let { androidVolumeStep() > it } == true
        ) {
            publishConfirmation(OutputConfirmation.PROFILE_VOLUME, route, restore, limit, preset.physicalGainNote)
            return ProfileApplication.CONFIRMATION_REQUIRED
        }
        revision++
        return ProfileApplication.APPLIED
    }

    private fun requireRouteConfirmation(route: OutputEndpoint?) {
        approved = null
        limit = null
        confirmedRestore = null
        // Keep any existing attenuation and discard the pending restoration bound to the old route.
        val reason = when {
            route == null -> OutputConfirmation.ROUTE_UNKNOWN
            route.isUsb() -> OutputConfirmation.DAC_VOLUME_UNKNOWN
            else -> OutputConfirmation.ROUTE_CHANGED
        }
        setGain?.invoke(0f)
        publishConfirmation(reason, route)
        pause()
    }

    private fun publishConfirmation(
        reason: OutputConfirmation,
        route: OutputEndpoint?,
        restore: VolumeSettings? = null,
        limit: VolumeSettings? = null,
        physicalGainNote: String? = null,
    ) {
        mutableState.value = OutputSafetyState.ConfirmationRequired(++revision, reason, route,
            restore, limit, physicalGainNote)
    }
}

private fun sameRoute(first: OutputEndpoint?, second: OutputEndpoint?) =
    first?.portId == second?.portId && first?.type == second?.type

private fun OutputEndpoint.isUsb() = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
    type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
    (Build.VERSION.SDK_INT >= 26 && type == AudioDeviceInfo.TYPE_USB_HEADSET)

private fun gainFor(db: Double) = 10.0.pow(db / 20.0).toFloat()

private fun AudioPresetSettings.supportedOn(route: OutputEndpoint): Boolean {
    val target = when (route.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputTarget.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> OutputTarget.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> OutputTarget.BLUETOOTH
        else -> if (route.isUsb()) OutputTarget.USB else null
    }
    return (outputTarget == null || outputTarget == target) &&
        (outputMode == null || outputMode == OutputMode.STANDARD) && pcm == null && dsdMode == null &&
        dsdRate == null && resampler == null && dsp == null && buffer == null
}
