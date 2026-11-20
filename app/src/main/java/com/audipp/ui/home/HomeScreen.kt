package com.audipp.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.audipp.R
import com.audipp.domain.model.ErrorDomain
import com.audipp.ui.navigation.HomeSources
import com.audipp.ui.navigation.NavigationPlaybackState
import com.audipp.ui.theme.AudippSpacing

@Composable
internal fun HomeScreen(
    sources: HomeSources,
    playback: NavigationPlaybackState,
    onSettings: () -> Unit,
    onRetrySources: () -> Unit,
    onRetryPlayback: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.home),
            modifier = Modifier.align(Alignment.CenterVertically).semantics { heading() },
            style = MaterialTheme.typography.headlineLarge,
        )
        TextButton(onClick = onSettings) { Text(stringResource(R.string.settings)) }
    }

    Text(
        stringResource(R.string.local_sources),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
    when (sources) {
        HomeSources.Loading -> {
            val label = stringResource(R.string.local_sources_loading)
            CircularProgressIndicator(Modifier.semantics { contentDescription = label })
        }
        HomeSources.Failed -> {
            Text(stringResource(R.string.local_sources_read_failed))
            TextButton(onClick = onRetrySources) { Text(stringResource(R.string.retry)) }
        }
        is HomeSources.Ready -> {
            val summary = sources.summary
            if (summary.registered == 0L) {
                Text(stringResource(R.string.local_sources_empty))
                Text(
                    stringResource(R.string.local_sources_onboarding),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

private fun quantityFor(count: Long): Int = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
