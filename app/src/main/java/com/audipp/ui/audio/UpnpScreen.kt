package com.audipp.ui.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audipp.R
import com.audipp.domain.model.TransitionId
import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.NetworkResult
import com.audipp.integrations.network.RendererConnection
import com.audipp.integrations.network.RendererDevice
import com.audipp.integrations.network.RendererDiscovery
import com.audipp.integrations.network.RendererPhase
import com.audipp.integrations.network.upnp.AndroidUpnpNetwork
import com.audipp.integrations.network.upnp.UpnpControlPoint
import com.audipp.integrations.network.upnp.UpnpRendererAdapter
import com.audipp.integrations.network.upnp.UpnpRendererSession
import com.audipp.integrations.network.upnp.isUpnpLan
import com.audipp.ui.theme.AudippSpacing
import com.audipp.playback.source.PlaybackTarget
import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.TargetKind
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun UpnpScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var networks by remember(context) { mutableStateOf(lanChoices(context)) }
    var choice by remember { mutableStateOf<LanChoice?>(null) }
    var host by remember { mutableStateOf("") }
    var request by remember { mutableStateOf<Job?>(null) }
    var working by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<RendererDevice>>(emptyList()) }
    var failure by remember { mutableStateOf<NetworkFailure?>(null) }
    var searched by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf<UpnpRendererSession?>(null) }
    var openPermissionSettings by remember { mutableStateOf(false) }
    val access = remember(choice) { AtomicBoolean(choice != null) }
    val adapter = remember(context, choice, access) {
        choice?.let { UpnpRendererAdapter(UpnpControlPoint(AndroidUpnpNetwork(context, it.network, allowed = access::get))) }
    }

    fun reset() {
        access.set(false)
        request?.cancel()
        active = null
        working = false
        devices = emptyList()
        searched = false
        failure = null
    }

    fun query(byHost: Boolean) {
        val selected = adapter ?: return
        request?.cancel()
        access.set(true)
        active = null
        working = true
        devices = emptyList()
        failure = null
        searched = false
        manual = byHost
        request = scope.launch {
            val result = if (byHost) {
                when (val found = selected.manual(host)) {
                    is NetworkResult.Failure -> found
                    is NetworkResult.Success -> NetworkResult.Success(listOf(found.value))
                }
            } else RendererDiscovery(selected).discover()
            when (result) {
                is NetworkResult.Failure -> failure = result.reason
                is NetworkResult.Success -> {
                    devices = result.value
                    searched = true
                }
            }
            working = false
        }
    }

    fun canRequestPermission(reason: NetworkFailure?): Boolean =
        Build.VERSION.SDK_INT == 36 && access.get() && reason == NetworkFailure.PERMISSION_REQUIRED

    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        openPermissionSettings = !granted
        if (granted) query(manual)
    }
    val launchNearbyPermission = {
        if (Build.VERSION.SDK_INT == 36) requestPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    DisposableEffect(access) {
        onDispose { access.set(false) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { reset() }

    Text(stringResource(R.string.upnp_output), style = MaterialTheme.typography.headlineLarge)
    Text(stringResource(R.string.upnp_choose_network))
    Column(Modifier.selectableGroup()) {
        for (network in networks) {
            val name = stringResource(
                if (network.wifi) R.string.upnp_wifi else R.string.upnp_ethernet,
                network.label,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AudippSpacing.minimumTouchTarget)
                    .selectable(
                        selected = choice == network,
                        role = Role.RadioButton,
                        onClick = {
                            if (choice != network) {
                                reset()
                                choice = network
                            }
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = choice == network, onClick = null)
                Text(name)
            }
        }
    }
    TextButton(onClick = {
        reset()
        choice = null
        networks = lanChoices(context)
    }) {
        Text(stringResource(R.string.upnp_refresh_networks))
    }
    if (networks.isEmpty()) Text(stringResource(R.string.upnp_no_networks))

    Button(enabled = adapter != null && !working && active == null, onClick = { query(false) }) {
        Text(stringResource(R.string.upnp_search))
    }
    OutlinedTextField(
        value = host,
        onValueChange = { if (it.length <= 2048) host = it },
        label = { Text(stringResource(R.string.upnp_host)) },
        placeholder = { Text(stringResource(R.string.upnp_host_example)) },
        enabled = !working && active == null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(enabled = adapter != null && host.isNotBlank() && !working && active == null, onClick = { query(true) }) {
        Text(stringResource(R.string.upnp_find_host))
    }
    if (working) {
        Text(stringResource(R.string.upnp_searching))
        TextButton(onClick = { reset() }) { Text(stringResource(R.string.upnp_cancel)) }
    }
    val selected = active
    if (selected == null) {
        if (searched && devices.isEmpty()) Text(stringResource(R.string.upnp_no_devices))
        for (device in devices) {
            val chosen = adapter ?: break
            TextButton(onClick = {
                val resolution = ResolutionContext(
                    PlaybackTarget(device.id, TargetKind.RENDERER), 0, TransitionId(UUID.randomUUID().toString()),
                )
                failure = null
                active = chosen.openSession(device, resolution) { resolution }
            }) {
                Text(chosen.label(device))
            }
        }
    } else {
        val snapshot by selected.state.collectAsStateWithLifecycle()
        LaunchedEffect(selected) {
            try {
                selected.connect()
                selected.poll()
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { selected.disconnect() }
            }
        }
        if (snapshot.failure == null) when (snapshot.connection) {
            RendererConnection.CONNECTED -> {
                Text(stringResource(R.string.upnp_connected, selected.label))
                val phase = snapshot.observation?.phase ?: RendererPhase.UNKNOWN
                Text(stringResource(when (phase) {
                    RendererPhase.PLAYING -> R.string.upnp_playing
                    RendererPhase.PAUSED -> R.string.upnp_paused
                    RendererPhase.STOPPED -> R.string.upnp_stopped
                    RendererPhase.UNKNOWN -> R.string.upnp_state_unknown
                }))
            }
            RendererConnection.DISCONNECTED -> Text(stringResource(R.string.upnp_connecting))
            else -> Unit
        }
        snapshot.failure?.let { UpnpFailure(it) }
        TextButton(onClick = { active = null }) { Text(stringResource(R.string.upnp_disconnect)) }
        if (canRequestPermission(snapshot.failure)) {
            TextButton(onClick = {
                active = null
                launchNearbyPermission()
            }) {
                Text(stringResource(R.string.upnp_request_access))
            }
        }
    }
    failure?.let {
        UpnpFailure(it)
        if (canRequestPermission(it)) {
            TextButton(onClick = launchNearbyPermission) {
                Text(stringResource(R.string.upnp_request_access))
            }
        }
        TextButton(onClick = { query(manual) }) { Text(stringResource(R.string.retry)) }
    }
    if (openPermissionSettings) {
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }) {
            Text(stringResource(R.string.upnp_permission_settings))
        }
    }
}

@Composable
private fun UpnpFailure(failure: NetworkFailure) {
    Text(
        stringResource(when (failure) {
            NetworkFailure.PERMISSION_REQUIRED -> R.string.upnp_permission_denied
            NetworkFailure.INVALID_INPUT -> R.string.upnp_invalid_host
            NetworkFailure.INVALID_RESPONSE -> R.string.upnp_invalid_response
            NetworkFailure.OFFLINE, NetworkFailure.EXPIRED, NetworkFailure.STALE_RESPONSE -> R.string.upnp_connection_lost
            NetworkFailure.TIMEOUT -> R.string.upnp_timeout
            NetworkFailure.AUTH_REQUIRED -> R.string.upnp_auth_required
            NetworkFailure.UNSUPPORTED -> R.string.upnp_unsupported
            else -> R.string.upnp_io_failure
        }),
        color = MaterialTheme.colorScheme.error,
    )
}

private data class LanChoice(val network: Network, val wifi: Boolean, val label: String)

@Suppress("DEPRECATION")
private fun lanChoices(context: Context): List<LanChoice> {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    return connectivity.allNetworks.mapNotNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network)?.takeIf { it.isUpnpLan() } ?: return@mapNotNull null
        val links = connectivity.getLinkProperties(network) ?: return@mapNotNull null
        val name = links.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
            ?: links.interfaceName ?: return@mapNotNull null
        LanChoice(network, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), name)
    }
}
