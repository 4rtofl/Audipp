package com.audipp.ui.nowplaying

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.audipp.R
import com.audipp.data.local.Artwork
import com.audipp.domain.model.AudioFormat
import com.audipp.domain.model.ErrorDomain
import com.audipp.domain.model.PlaceholderReason
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.VariantId
import com.audipp.playback.OutputConfirmationRequest
import com.audipp.ui.navigation.NavigationPlaybackState
import com.audipp.ui.navigation.ObservedOutput
import com.audipp.ui.navigation.OutputConfirmationPrompt
import com.audipp.ui.navigation.PlaybackControl
import com.audipp.ui.navigation.SeekBlock
import com.audipp.ui.navigation.SeekState
import com.audipp.ui.theme.AudippSpacing

@Composable
internal fun NowPlayingScreen(
    playback: NavigationPlaybackState,
    loadArtwork: suspend (VariantId, Int) -> Artwork,
    onBack: () -> Unit,
    onQueue: () -> Unit,
    onTogglePlayback: (PlaybackControl) -> Unit,
    onSeek: (Long) -> Unit,
    onConfirmOutput: (OutputConfirmationRequest) -> Unit,
    onRetryPlayback: () -> Unit,
) {
    var showOutput by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = AudippSpacing.small),
                horizontalArrangement = Arrangement.spacedBy(AudippSpacing.small),
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.now_playing_collapse)) }
                if (playback is NavigationPlaybackState.Current) {
                    TextButton(onClick = onQueue) { Text(stringResource(R.string.navigation_queue)) }
                    TextButton(onClick = { showOutput = !showOutput }) {
                        Text(stringResource(if (showOutput) R.string.now_playing_output_hide else R.string.now_playing_output))
                    }
                    val reason = stringResource(R.string.now_playing_detail_unavailable)
                    TextButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.semantics { stateDescription = reason },
                    ) { Text(stringResource(R.string.now_playing_detail)) }
                }
            }
        }
        when (playback) {
            is NavigationPlaybackState.Current -> NowPlayingContent(
                playback, showOutput, loadArtwork, onTogglePlayback, onSeek, onConfirmOutput,
            )
            NavigationPlaybackState.Connecting -> Box(Modifier.fillMaxSize().padding(AudippSpacing.extraLarge)) {
                val label = stringResource(R.string.playback_loading)
                CircularProgressIndicator(Modifier.semantics { contentDescription = label })
            }
            else -> Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(AudippSpacing.extraLarge),
                verticalArrangement = Arrangement.spacedBy(AudippSpacing.large),
            ) {
                Text(
                    stringResource(
                        when (playback) {
                            NavigationPlaybackState.Disconnected -> R.string.playback_disconnected
                            is NavigationPlaybackState.Failed -> when (playback.domain) {
                                ErrorDomain.PERMISSION_DENIED -> R.string.playback_permission_denied
                                ErrorDomain.FORMAT_UNSUPPORTED -> R.string.playback_format_unsupported
                                else -> R.string.playback_failed
                            }
                            else -> R.string.now_playing_empty
                        },
                    ),
                )
                if (playback == NavigationPlaybackState.Disconnected) {
                    TextButton(onClick = onRetryPlayback) { Text(stringResource(R.string.retry_playback)) }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingContent(
    playback: NavigationPlaybackState.Current,
    showOutput: Boolean,
    loadArtwork: suspend (VariantId, Int) -> Artwork,
    onTogglePlayback: (PlaybackControl) -> Unit,
    onSeek: (Long) -> Unit,
    onConfirmOutput: (OutputConfirmationRequest) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val padding = AudippSpacing.extraLarge
        // Side by side only when the width allows it and the screen is landscape.
        val sideBySide = maxWidth >= 600.dp && maxWidth > maxHeight
        val details: @Composable (Modifier) -> Unit = {
            NowPlayingDetails(playback, showOutput, onTogglePlayback, onSeek, onConfirmOutput, it)
        }
        if (sideBySide) {
            // Leave room under the artwork for the placeholder reason.
            val art = minOf(maxHeight - padding * 2 - 24.dp * LocalDensity.current.fontScale.coerceAtLeast(1f), maxWidth * 0.4f)
            Row(Modifier.fillMaxSize().padding(horizontal = padding), horizontalArrangement = Arrangement.spacedBy(padding)) {
                Column(Modifier.width(art).padding(vertical = padding)) {
                    NowPlayingArtwork(playback.variantId, art, loadArtwork)
                }
                details(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = padding))
            }
        } else {
            // Larger text needs the room, so the artwork shrinks with the font scale to keep the controls near the top.
            val art = minOf(maxWidth - padding * 2, 320.dp, maxHeight * 0.4f) / LocalDensity.current.fontScale.coerceAtLeast(1f)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = padding, vertical = AudippSpacing.large),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AudippSpacing.large),
            ) {
                NowPlayingArtwork(playback.variantId, art, loadArtwork)
                details(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun NowPlayingArtwork(variantId: VariantId?, size: Dp, loadArtwork: suspend (VariantId, Int) -> Artwork) {
    // The reason is not limited to the artwork's width, which shrinks with large text.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AudippSpacing.small)) {
        val artwork = rememberArtwork(variantId, size, loadArtwork)
        ArtworkBox(artwork, Modifier.size(size))
        (artwork as? Artwork.Placeholder)?.let { Text(stringResource(it.reason.label()), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun NowPlayingDetails(
    playback: NavigationPlaybackState.Current,
    showOutput: Boolean,
    onTogglePlayback: (PlaybackControl) -> Unit,
    onSeek: (Long) -> Unit,
    onConfirmOutput: (OutputConfirmationRequest) -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AudippSpacing.medium)) {
        Text(
            playback.title ?: stringResource(R.string.playback_unknown_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        playback.artist?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Text(stringResource(playback.statusLabel), style = MaterialTheme.typography.titleSmall)

        SeekBar(playback.seek, onSeek)

        val confirmation = playback.outputConfirmation
        if (confirmation != null) {
            // The prompt is the only way to proceed, so it replaces the play button instead of sitting beside a disabled one.
            // The page already scrolls, so the prompt must not scroll inside it.
            OutputConfirmationPrompt(confirmation, PaddingValues(0.dp), boundedHeight = false) { onConfirmOutput(confirmation) }
        } else {
            TransportControls(playback, onTogglePlayback)
        }

        Column(verticalArrangement = Arrangement.spacedBy(AudippSpacing.extraSmall)) {
            playback.source?.let { source ->
                val provider = when (source.kind) {
                    SourceKind.LOCAL -> stringResource(R.string.now_playing_provider_local)
                    SourceKind.PROVIDER -> stringResource(R.string.now_playing_provider_service, source.providerKey.orEmpty())
                    SourceKind.NETWORK -> stringResource(R.string.now_playing_provider_network)
                }
                InfoLine(stringResource(R.string.now_playing_provider, provider))
            }
            InfoLine(stringResource(R.string.now_playing_format, sourceFormatText(playback.sourceFormat)))
            InfoLine(stringResource(R.string.now_playing_output_label, outputText(playback.observedOutput)))
            if (showOutput) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(AudippSpacing.small)) {
                    Text(stringResource(R.string.now_playing_output_note), Modifier.padding(AudippSpacing.medium),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun InfoLine(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun sourceFormatText(format: AudioFormat?): String {
    val parts = listOfNotNull(
        format?.codec,
        format?.bitDepth?.let { stringResource(R.string.now_playing_format_bit_depth, it) },
        format?.sampleRateHz?.let { stringResource(R.string.now_playing_format_sample_rate, formatKilohertz(it)) },
        format?.channelCount?.let { stringResource(R.string.now_playing_format_channels, it) },
    )
    return if (parts.isEmpty()) stringResource(R.string.now_playing_format_unknown) else parts.joinToString(" · ")
}

@Composable
private fun outputText(output: ObservedOutput): String = when (output) {
    ObservedOutput.Unobserved -> stringResource(R.string.now_playing_output_unobserved)
    ObservedOutput.Multiple -> stringResource(R.string.now_playing_output_multiple)
    is ObservedOutput.Single -> stringResource(outputTypeLabel(output.androidType))
}

@Composable
private fun SeekBar(seek: SeekState, onSeek: (Long) -> Unit) {
    val available = seek as? SeekState.Available
    val duration = when (seek) {
        is SeekState.Available -> seek.durationMs
        is SeekState.Blocked -> seek.durationMs
    }
    val reportedPosition = when (seek) {
        is SeekState.Available -> seek.positionMs
        is SeekState.Blocked -> seek.positionMs
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    // The service reports no position while a seek is applied; keep showing the requested one until it does.
    var requested by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(reportedPosition) { if (reportedPosition != null) requested = null }
    val position = dragging?.toLong() ?: reportedPosition ?: requested
    val unknown = stringResource(R.string.now_playing_seek_unknown_position)
    val value = stringResource(
        R.string.now_playing_seek_value,
        position?.let(::formatClock) ?: unknown,
        duration?.let(::formatClock) ?: unknown,
    )
    val label = stringResource(R.string.now_playing_seek)
    Column(verticalArrangement = Arrangement.spacedBy(AudippSpacing.extraSmall)) {
        Slider(
            value = (position ?: 0L).toFloat().coerceAtMost((duration ?: 1L).toFloat()),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { requested = it.toLong(); onSeek(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..(duration ?: 1L).toFloat().coerceAtLeast(1f),
            enabled = available != null,
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = value
            },
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
        (seek as? SeekState.Blocked)?.let {
            Text(
                stringResource(
                    when (it.reason) {
                        SeekBlock.OUTPUT_CONFIRMATION -> R.string.now_playing_seek_confirmation
                        SeekBlock.PREPARING -> R.string.now_playing_seek_preparing
                        SeekBlock.UNKNOWN_DURATION -> R.string.now_playing_seek_unknown_duration
                        SeekBlock.NOT_SEEKABLE -> R.string.now_playing_seek_not_seekable
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransportControls(playback: NavigationPlaybackState.Current, onTogglePlayback: (PlaybackControl) -> Unit) {
    val skipReason = stringResource(R.string.now_playing_skip_unavailable)
    Column(verticalArrangement = Arrangement.spacedBy(AudippSpacing.small)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AudippSpacing.small), verticalArrangement = Arrangement.spacedBy(AudippSpacing.small)) {
            TextButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.heightIn(min = AudippSpacing.minimumTouchTarget).semantics { stateDescription = skipReason },
            ) { Text(stringResource(R.string.now_playing_previous)) }
            val control = playback.control
            Button(
                onClick = { control?.let(onTogglePlayback) },
                enabled = control != null,
                modifier = Modifier.heightIn(min = AudippSpacing.minimumTouchTarget),
            ) {
                Text(stringResource(if (playback.phase == PlaybackPhase.PLAYING) R.string.playback_pause else R.string.playback_resume))
            }
            TextButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.heightIn(min = AudippSpacing.minimumTouchTarget).semantics { stateDescription = skipReason },
            ) { Text(stringResource(R.string.now_playing_next)) }
        }
        if (playback.control == null) {
            Text(
                stringResource(
                    if (playback.phase == PlaybackPhase.PLAYING || playback.phase == PlaybackPhase.PAUSED) {
                        R.string.now_playing_control_unavailable
                    } else {
                        R.string.now_playing_control_preparing
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(skipReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Requests are bucketed to powers of two so repeated draws at similar sizes share the provider's cache. */
@Composable
internal fun rememberArtwork(variantId: VariantId?, size: Dp, load: suspend (VariantId, Int) -> Artwork): Artwork? {
    val edge = with(LocalDensity.current) { size.roundToPx() }
    val bucket = generateSequence(64) { it * 2 }.first { it >= edge || it >= 1024 }
    return produceState<Artwork?>(null, variantId, bucket) {
        value = null
        value = if (variantId == null) Artwork.Placeholder(PlaceholderReason.NO_ARTWORK)
        else load(variantId, bucket)
    }.value
}

@Composable
internal fun ArtworkBox(artwork: Artwork?, modifier: Modifier, describePlaceholder: Boolean = false) {
    val shape = RoundedCornerShape(AudippSpacing.small)
    val description = stringResource(R.string.now_playing_artwork)
    when (artwork) {
        is Artwork.Image -> Image(
            bitmap = artwork.bitmap.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
        else -> {
            val reason = (artwork as? Artwork.Placeholder)?.reason?.label()?.let { stringResource(it) }
            Box(
                modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant).semantics {
                    if (describePlaceholder && reason != null) contentDescription = reason
                },
            )
        }
    }
}
