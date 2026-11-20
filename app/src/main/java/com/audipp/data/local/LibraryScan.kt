package com.audipp.data.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LocalTreeRegistration {
    data class Registered(val rootId: String) : LocalTreeRegistration

    enum class Rejected : LocalTreeRegistration {
        INVALID_SELECTION, READ_GRANT_REQUIRED, PERSISTABLE_GRANT_REQUIRED, ACCESS_DENIED, STORAGE_ERROR,
    }
}

enum class LibraryScanStatus { COMPLETE, PARTIAL, CANCELLED, ACCESS_LOST }

enum class LibraryScanIssue {
    UNREGISTERED_ROOT, ROOT_UNAVAILABLE, DIRECTORY_READ, ANALYSIS_FAILED, TIMEOUT,
    DEPTH_LIMIT, ENTRY_LIMIT, MEMORY_LIMIT, STORAGE_ERROR, SUPERSEDED,
}

data class LibraryScanProgress(
    val visited: Int = 0,
    val directories: Int = 0,
    val indexed: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
)

data class LibraryScanResult(
    val scanId: String,
    val status: LibraryScanStatus,
    val progress: LibraryScanProgress,
    val missing: Int,
    val issues: Set<LibraryScanIssue>,
)

class LibraryScan internal constructor(
    val scanId: String,
    progress: MutableStateFlow<LibraryScanProgress>,
    private val result: CompletableDeferred<LibraryScanResult>,
    private val cancelScan: () -> Unit,
) {
    val progress: StateFlow<LibraryScanProgress> = progress.asStateFlow()

    fun cancel() = cancelScan()

    suspend fun await(): LibraryScanResult = result.await()
}
