package com.audipp.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.os.ConfigurationCompat
import com.audipp.R
import com.audipp.data.local.LibraryScanIssue
import com.audipp.data.local.LibraryScanProgress
import com.audipp.data.local.LibraryScanResult
import com.audipp.data.local.LibraryScanStatus
import com.audipp.ui.home.LocalMusic
import com.audipp.ui.navigation.FolderRegistration
import com.audipp.ui.navigation.HomeSources
import com.audipp.ui.theme.AudippSpacing
import java.text.NumberFormat
import java.util.Locale

/** Registration progress: the running scan's counts, the last result, and each folder's state and grant. */
@Composable
internal fun SourceScreen(
    sources: HomeSources,
    registration: FolderRegistration,
    lastScan: LibraryScanResult?,
    onAddMusic: () -> Unit,
    onRetrySources: () -> Unit,
    onRetryScan: (String) -> Unit,
    onCancelScan: () -> Unit,
) {
    LocalMusic(sources, registration, onAddMusic, onRetrySources, onRetryScan, onCancelScan)
    if (registration is FolderRegistration.Scanning) Counts(registration.progress, missing = null)
    if (lastScan != null && registration !is FolderRegistration.Scanning) {
        Heading(R.string.source_last_scan)
        Text(stringResource(lastScan.status.label))
        Counts(lastScan.progress, lastScan.missing)
        lastScan.issues.map { it.label }.distinct().forEach { Text(stringResource(it)) }
    }
    val folders = (sources as? HomeSources.Ready)?.folders.orEmpty()
    if (folders.isEmpty()) return
    Heading(R.string.source_folders)
    folders.forEach { folder ->
        Column {
            Text(folder.name)
            Text(
                stringResource(if (!folder.granted) R.string.source_status_no_access else folder.lastStatus.label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (folder.granted) {
                TextButton(enabled = !registration.busy, onClick = { onRetryScan(folder.rootId) }) {
                    Text(stringResource(R.string.music_retry_scan))
                }
            } else {
                TextButton(enabled = !registration.busy, onClick = onAddMusic) {
                    Text(stringResource(R.string.music_choose_again))
                }
            }
        }
    }
}

@Composable
private fun Heading(@StringRes id: Int) {
    Text(stringResource(id), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun Counts(progress: LibraryScanProgress, missing: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(AudippSpacing.extraSmall)) {
        Count(R.string.source_count_visited, progress.visited)
        Count(R.string.source_count_directories, progress.directories)
        Count(R.string.source_count_indexed, progress.indexed)
        Count(R.string.source_count_updated, progress.updated)
        Count(R.string.source_count_skipped, progress.skipped)
        Count(R.string.source_count_failed, progress.failed)
        missing?.let { Count(R.string.source_count_missing, it) }
    }
}

@Composable
private fun Count(@StringRes label: Int, value: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
        Text(remember(locale) { NumberFormat.getIntegerInstance(locale) }.format(value))
    }
}

private val LibraryScanStatus?.label: Int
    @StringRes get() = when (this) {
        null -> R.string.source_status_not_scanned
        LibraryScanStatus.COMPLETE -> R.string.source_status_complete
        LibraryScanStatus.PARTIAL -> R.string.source_status_partial
        LibraryScanStatus.CANCELLED -> R.string.source_status_cancelled
        LibraryScanStatus.ACCESS_LOST -> R.string.source_status_no_access
    }

private val LibraryScanIssue.label: Int
    @StringRes get() = when (this) {
        LibraryScanIssue.UNREGISTERED_ROOT, LibraryScanIssue.ROOT_UNAVAILABLE -> R.string.source_issue_root
        LibraryScanIssue.DIRECTORY_READ -> R.string.source_issue_directory
        LibraryScanIssue.TIMEOUT -> R.string.source_issue_timeout
        LibraryScanIssue.ANALYSIS_FAILED, LibraryScanIssue.EMPTY_AUDIO, LibraryScanIssue.CORRUPT_AUDIO,
        LibraryScanIssue.FORMAT_MISMATCH, LibraryScanIssue.FORMAT_INSPECTION_LIMIT -> R.string.source_issue_unreadable
        LibraryScanIssue.UNSUPPORTED_AUDIO, LibraryScanIssue.PLATFORM_AUDIO_UNAVAILABLE -> R.string.source_issue_unsupported
        LibraryScanIssue.DEPTH_LIMIT, LibraryScanIssue.ENTRY_LIMIT, LibraryScanIssue.MEMORY_LIMIT -> R.string.source_issue_limit
        LibraryScanIssue.STORAGE_ERROR -> R.string.source_issue_storage
        LibraryScanIssue.SUPERSEDED -> R.string.source_issue_superseded
    }
