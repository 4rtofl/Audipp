package com.audipp.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.audipp.R
import com.audipp.domain.model.PlaybackPhase
import com.audipp.ui.theme.AudippSpacing

@Composable
internal fun NavigationPlaybackBar(
    playback: NavigationPlaybackState.Current,
    onTogglePlayback: (PlaybackControl) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            FlowRow(
                modifier = Modifier
                    .widthIn(max = AudippSpacing.contentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = AudippSpacing.extraLarge, vertical = AudippSpacing.small),
                horizontalArrangement = Arrangement.spacedBy(AudippSpacing.small),
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .widthIn(min = AudippSpacing.minimumTouchTarget)
                        .weight(1f),
                ) {
                    Text(
                        playback.title ?: stringResource(R.string.playback_unknown_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val stateLabel = when (playback.phase) {
                        PlaybackPhase.RESOLVING, PlaybackPhase.BUFFERING, PlaybackPhase.ADVANCING ->
                            R.string.playback_loading
                        PlaybackPhase.PLAYING -> R.string.playback_playing
                        else -> R.string.playback_paused
                    }
                    Text(
                        playback.artist?.let {
                            stringResource(R.string.playback_artist_and_state, it, stringResource(stateLabel))
                        } ?: stringResource(stateLabel),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                playback.control?.let { control ->
                    TextButton(
                        modifier = Modifier.heightIn(min = AudippSpacing.minimumTouchTarget),
                        onClick = { onTogglePlayback(control) },
                    ) {
                        Text(stringResource(if (control.pause) R.string.playback_pause else R.string.playback_resume))
                    }
                }
            }
        }
    }
}
