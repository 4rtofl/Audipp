package com.audipp.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.os.ConfigurationCompat
import com.audipp.R
import com.audipp.domain.model.ErrorDomain
import com.audipp.ui.navigation.FolderRegistration
import com.audipp.ui.navigation.HomeSources
import com.audipp.ui.navigation.NavigationPlaybackState
import com.audipp.ui.navigation.RecentPlays
import com.audipp.ui.theme.AudippSpacing
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LocalMusic(
    sources: HomeSources,
    registration: FolderRegistration,
    onAddMusic: () -> Unit,
    onRetrySources: () -> Unit,
    onRetryScan: (String) -> Unit,
    onCancelScan: () -> Unit,
) {
    Text(
        stringResource(R.string.local_sources),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
    when (sources) {
        HomeSources.Loading -> {
            LoadingIndicator(R.string.local_sources_loading)
        }
        HomeSources.Failed -> {
            Text(stringResource(R.string.local_sources_read_failed))
            TextButton(onClick = onRetrySources) { Text(stringResource(R.string.retry)) }
        }
        is HomeSources.Ready -> {
            val summary = sources.summary
            if (summary.registered == 0L) {
                if (!registration.busy && !sources.accessLost && sources.retryRootId == null) {
                    Text(stringResource(
                        if (sources.hasFolders) R.string.local_sources_folder_empty else R.string.local_sources_empty,
                    ))
                }
            } else {
                Text(pluralStringResource(
                    R.plurals.local_sources_registered,
                    quantityFor(summary.registered),
                    summary.registered,
                ))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AudippSpacing.large)) {
                    if (summary.unavailable > 0) Text(stringResource(R.string.local_sources_unavailable, summary.unavailable))
                    if (summary.unknown > 0) Text(stringResource(R.string.local_sources_unknown, summary.unknown))
                }
            }
        }
    }

    val ready = sources as? HomeSources.Ready
    val retryRoot = (registration as? FolderRegistration.ScanFailed)?.rootId ?: ready?.retryRootId
    val accessLost = registration == FolderRegistration.AccessLost || ready?.accessLost == true
    when (registration) {
        FolderRegistration.Idle, FolderRegistration.Picking, FolderRegistration.AccessLost -> Unit
        FolderRegistration.PickerUnavailable -> Text(stringResource(R.string.music_picker_unavailable))
        FolderRegistration.Registering -> LoadingIndicator(R.string.music_registering)
        FolderRegistration.Denied -> Text(stringResource(R.string.music_permission_denied))
        FolderRegistration.RegistrationFailed -> Text(stringResource(R.string.music_registration_failed))
        is FolderRegistration.ScanFailed -> Unit
        is FolderRegistration.Scanning -> {
            if (registration.visited == 0) {
                LoadingIndicator(R.string.music_scan_loading)
            } else {
                Text(pluralStringResource(R.plurals.music_scan_visited, registration.visited, registration.visited))
            }
            TextButton(onClick = onCancelScan) { Text(stringResource(android.R.string.cancel)) }
        }
    }
    if (!registration.busy) {
        if (accessLost) Text(stringResource(R.string.music_access_lost))
        if (retryRoot != null && registration != FolderRegistration.AccessLost) {
            Text(stringResource(R.string.music_scan_failed))
            TextButton(onClick = { onRetryScan(retryRoot) }) { Text(stringResource(R.string.music_retry_scan)) }
        }
    }
    if (ready != null) {
        Button(enabled = !registration.busy, onClick = onAddMusic) {
            Text(stringResource(if (accessLost) R.string.music_choose_again else R.string.music_add))
        }
    }
}

@Composable
internal fun HomeScreen(
    recentPlays: RecentPlays,
    playback: NavigationPlaybackState,
    onRetryHistory: () -> Unit,
    onRetryPlayback: () -> Unit,
) {
    Text(
        stringResource(R.string.recent_plays),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
    when (recentPlays) {
        RecentPlays.Loading -> LoadingIndicator(R.string.recent_plays_loading)
        RecentPlays.Failed -> {
            Text(stringResource(R.string.recent_plays_failed))
            TextButton(onClick = onRetryHistory) { Text(stringResource(R.string.retry)) }
        }
        is RecentPlays.Ready -> {
            if (recentPlays.plays.isEmpty()) Text(stringResource(R.string.recent_plays_empty))
            val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
            val dateTime = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
            recentPlays.plays.forEach { play ->
                Column {
                    Text(play.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.recent_play_unknown_title))
                    Text(
                        dateTime.format(Date(play.startUtcMs)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    when (playback) {
        NavigationPlaybackState.Disconnected -> {
            Text(stringResource(R.string.playback_disconnected))
            TextButton(onClick = onRetryPlayback) { Text(stringResource(R.string.retry_playback)) }
        }
        is NavigationPlaybackState.Failed -> {
            val error = when (playback.domain) {
                ErrorDomain.PERMISSION_DENIED -> R.string.playback_permission_denied
                ErrorDomain.FORMAT_UNSUPPORTED -> R.string.playback_format_unsupported
                else -> R.string.playback_failed
            }
            Text(stringResource(error))
        }
        else -> Unit
    }
}

@Composable
private fun LoadingIndicator(labelId: Int) {
    val label = stringResource(labelId)
    CircularProgressIndicator(Modifier.semantics { contentDescription = label })
}

private fun quantityFor(count: Long): Int = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
