package com.audipp.data.local

import android.net.Uri
import kotlinx.coroutines.flow.Flow

internal interface SafAccess {
    fun hasReadGrant(tree: Uri): Boolean
    fun takeReadGrant(tree: Uri)
    fun releaseReadGrant(tree: Uri)
    suspend fun rootReadable(tree: Uri): Boolean
    fun children(tree: Uri, directoryId: String): Flow<SafEntry>
    suspend fun analyze(document: Uri): SafAudio?
}

internal data class SafEntry(val id: String, val directory: Boolean)

internal data class SafAudio(
    val codec: String,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val durationMs: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
)

internal data class ScanLimits(
    val depth: Int = 32,
    val entries: Int = 50_000,
    val retainedBytes: Long = 16L * 1024 * 1024,
) {
    init {
        require(depth >= 0 && entries > 0 && retainedBytes > 0)
    }
}

internal class SafTimeout : Exception()

internal class SafIncompleteRead : Exception()
