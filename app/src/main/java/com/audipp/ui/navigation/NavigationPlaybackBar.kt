package com.audipp.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import com.audipp.data.local.Artwork
import com.audipp.domain.model.VariantId
import com.audipp.ui.nowplaying.ArtworkBox
import com.audipp.ui.nowplaying.rememberArtwork
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audipp.R
import com.audipp.audio.route.OutputConfirmation
import com.audipp.playback.OutputConfirmationRequest
import com.audipp.ui.theme.AudippSpacing

@Composable
internal fun NavigationPlaybackBar(
    playback: NavigationPlaybackState.Current,
    loadArtwork: suspend (VariantId, Int) -> Artwork,
    onExpand: () -> Unit,
    onTogglePlayback: (PlaybackControl) -> Unit,
    onConfirmOutput: (OutputConfirmationRequest) -> Unit,
    expandFocus: FocusRequester = remember { FocusRequester() },
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        val confirmation = playback.outputConfirmation
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            FlowRow(
                modifier = Modifier
                    .widthIn(max = AudippSpacing.contentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = AudippSpacing.extraLarge, vertical = AudippSpacing.small),
                horizontalArrangement = Arrangement.spacedBy(AudippSpacing.small),
            ) {
                // Expanding only navigates; it never issues a playback command.
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .weight(1f)
                        // Touch mode would otherwise refuse the programmatic focus that returns here from Now Playing.
                        .focusProperties { canFocus = true }
                        .focusRequester(expandFocus)
                        .clickable(
                            onClickLabel = stringResource(R.string.now_playing_open),
                            role = Role.Button,
                            onClick = onExpand,
                        )
                        // Below this width the pause button wraps to its own line instead of truncating the title.
                        .widthIn(min = 176.dp)
                        .heightIn(min = AudippSpacing.minimumTouchTarget),
                    horizontalArrangement = Arrangement.spacedBy(AudippSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtworkBox(
                        rememberArtwork(playback.variantId, MiniArtworkSize, loadArtwork),
                        Modifier.size(MiniArtworkSize),
                        describePlaceholder = true,
                    )
                    Column(Modifier.widthIn(min = AudippSpacing.minimumTouchTarget).weight(1f)) {
                        Text(
                            playback.title ?: stringResource(R.string.playback_unknown_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val stateLabel = playback.statusLabel
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
            if (confirmation != null) {
                key(confirmation.id) {
                    OutputConfirmationPrompt(confirmation) { onConfirmOutput(confirmation) }
                }
            }
        }
    }
}

private val MiniArtworkSize = 48.dp

@Composable
internal fun OutputConfirmationPrompt(
    request: OutputConfirmationRequest,
    padding: PaddingValues = PaddingValues(horizontal = AudippSpacing.extraLarge, vertical = AudippSpacing.small),
    boundedHeight: Boolean = true,
    onConfirm: () -> Unit,
) {
    val (message, action) = when (request.reason) {
        OutputConfirmation.ROUTE_CHANGED -> R.string.playback_output_changed to R.string.playback_output_play
        OutputConfirmation.DAC_VOLUME_UNKNOWN -> R.string.playback_output_dac to R.string.playback_output_checked
        OutputConfirmation.ROUTE_UNKNOWN -> R.string.playback_output_unknown to R.string.playback_output_retry
        OutputConfirmation.PROFILE_VOLUME -> R.string.playback_output_profile to R.string.playback_output_checked
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = AudippSpacing.contentMaxWidth).fillMaxWidth()
            .padding(padding)) {
            Column(
                Modifier.fillMaxWidth().then(
                    if (boundedHeight) Modifier.heightIn(max = 168.dp).verticalScroll(rememberScrollState()) else Modifier,
                ),
                verticalArrangement = Arrangement.spacedBy(AudippSpacing.extraSmall),
            ) {
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
                request.limit?.let { limit ->
                    limit.digitalGainDb?.let {
                        Text(stringResource(R.string.playback_output_digital_limit, it.toString()))
                    }
                    limit.androidVolumeStep?.let {
                        Text(stringResource(R.string.playback_output_android_limit, it))
                    }
                    limit.hardwareVolume?.let {
                        val value = listOfNotNull(it.toString(), limit.hardwareVolumeUnit).joinToString(" ")
                        Text(stringResource(R.string.playback_output_dac_limit, value))
                    }
                    if (limit.muted == true) Text(stringResource(R.string.playback_output_muted_limit))
                }
                request.physicalGainNote?.let {
                    Text(stringResource(R.string.playback_output_physical_gain, it))
                }
            }
            TextButton(
                modifier = Modifier.align(Alignment.End).heightIn(min = AudippSpacing.minimumTouchTarget),
                onClick = onConfirm,
            ) {
                Text(stringResource(action))
            }
        }
    }
}
