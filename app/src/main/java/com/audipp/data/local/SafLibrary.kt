package com.audipp.data.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.audipp.data.db.AlbumEntity
import com.audipp.data.db.LibraryBatch
import com.audipp.data.db.LibraryDatabase
import com.audipp.data.db.LocalScanRoot
import com.audipp.data.db.LocalScanSource
import com.audipp.data.db.SourceEntity
import com.audipp.data.db.TrackEntity
import com.audipp.data.db.VariantEntity
import com.audipp.data.db.VariantLocalMetadata
import com.audipp.domain.model.Availability
import com.audipp.domain.model.SourceKind
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SafLibrary internal constructor(
    private val db: LibraryDatabase,
    private val access: SafAccess,
    private val limits: ScanLimits,
) {
    constructor(context: Context, db: LibraryDatabase) :
        this(db, AndroidSafAccess(context.applicationContext.contentResolver), ScanLimits())

    private val scans get() = db.safScanDao()
    private val library get() = db.libraryDao()

    suspend fun registeredRoots(): List<String> = scans.roots()

    suspend fun lastScanStatus(rootId: String): LibraryScanStatus? = scans.lastScanStatus(rootId)

    suspend fun registerLocalTree(selection: Intent?): LocalTreeRegistration {
        val uri = selection?.data ?: return LocalTreeRegistration.Rejected.INVALID_SELECTION
        val authority = uri.authority?.takeIf { it.isNotBlank() }
            ?: return LocalTreeRegistration.Rejected.INVALID_SELECTION
        val rootDocument = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: IllegalArgumentException) {
            return LocalTreeRegistration.Rejected.INVALID_SELECTION
        }
        if (uri.scheme != "content" || rootDocument.isBlank() || uri.toString().length > 8192 ||
            DocumentsContract.buildTreeDocumentUri(authority, rootDocument) != uri
        ) return LocalTreeRegistration.Rejected.INVALID_SELECTION

        val flags = selection.flags
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            return LocalTreeRegistration.Rejected.READ_GRANT_REQUIRED
        }
        if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) {
            return LocalTreeRegistration.Rejected.PERSISTABLE_GRANT_REQUIRED
        }

        val root = LocalScanRoot("saf-root:${stableId(uri)}", uri.toString())
        return withContext(Dispatchers.IO) {
            withRootLock(root.id) {
                currentCoroutineContext().ensureActive()
                // Keep the OS grant and the application registration consistent across caller cancellation.
                withContext(NonCancellable) { register(root, uri) }
            }
        }
    }

    private suspend fun register(root: LocalScanRoot, uri: Uri): LocalTreeRegistration {
        val alreadyGranted = try {
            val persisted = access.hasReadGrant(uri)
            access.takeReadGrant(uri)
            if (!access.hasReadGrant(uri)) return LocalTreeRegistration.Rejected.ACCESS_DENIED
            persisted
        } catch (_: Exception) {
            return LocalTreeRegistration.Rejected.ACCESS_DENIED
        }
        return try {
            val existing = scans.root(root.id)
            check(existing == null || existing.treeUri == root.treeUri)
            scans.register(root)
            LocalTreeRegistration.Registered(root.id)
        } catch (_: Exception) {
            if (!alreadyGranted) runCatching { access.releaseReadGrant(uri) }
            LocalTreeRegistration.Rejected.STORAGE_ERROR
        }
    }

    fun startScan(rootId: String, scope: CoroutineScope): LibraryScan {
        val scanId = UUID.randomUUID().toString()
        val progress = MutableStateFlow(LibraryScanProgress())
        val result = CompletableDeferred<LibraryScanResult>()
        val mayFinish = AtomicBoolean(true)
        val scan = Scan(rootId, scanId, progress)
        val worker = scope.launch(Dispatchers.IO) {
            withRootLock(rootId) {
                val status = scan.traverse()
                currentCoroutineContext().ensureActive()
                if (mayFinish.compareAndSet(true, false)) {
                    currentCoroutineContext().ensureActive()
                    // Once finalization starts, report the same outcome as the atomic Room write.
                    withContext(NonCancellable) { result.complete(scan.finish(status)) }
                }
            }
        }
        worker.invokeOnCompletion { failure ->
            if (result.isCompleted) return@invokeOnCompletion
            if (failure is CancellationException || failure == null) {
                result.complete(scan.result(LibraryScanStatus.CANCELLED))
            } else {
                result.completeExceptionally(failure)
            }
        }
        return LibraryScan(scanId, progress, result) {
            if (mayFinish.compareAndSet(true, false)) worker.cancel()
        }
    }

    private inner class Scan(
        val rootId: String,
        val scanId: String,
        val progress: MutableStateFlow<LibraryScanProgress>,
    ) {
        private val issues = mutableSetOf<LibraryScanIssue>()

        suspend fun traverse(): LibraryScanStatus = try {
            val root = db.withTransaction {
                scans.root(rootId)?.also { check(scans.beginScan(rootId, scanId) == 1) }
            }
            if (root == null) {
                issues += LibraryScanIssue.UNREGISTERED_ROOT
                LibraryScanStatus.ACCESS_LOST
            } else {
                val tree = root.treeUri.toUri()
                val rootAccess = checkRoot(tree)
                if (rootAccess == LibraryScanStatus.COMPLETE) {
                    walk(tree, DocumentsContract.getTreeDocumentId(tree))
                } else rootAccess
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SupersededScan) {
            issues += LibraryScanIssue.SUPERSEDED
            LibraryScanStatus.PARTIAL
        } catch (_: Exception) {
            issues += LibraryScanIssue.STORAGE_ERROR
            LibraryScanStatus.PARTIAL
        }

        private suspend fun checkRoot(tree: Uri): LibraryScanStatus = try {
            if (!access.hasReadGrant(tree) || !access.rootReadable(tree)) {
                issues += LibraryScanIssue.ROOT_UNAVAILABLE
                LibraryScanStatus.ACCESS_LOST
            } else LibraryScanStatus.COMPLETE
        } catch (_: SafTimeout) {
            issues += LibraryScanIssue.TIMEOUT
            LibraryScanStatus.PARTIAL
        } catch (_: SafIncompleteRead) {
            issues += LibraryScanIssue.DIRECTORY_READ
            LibraryScanStatus.PARTIAL
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            issues += LibraryScanIssue.ROOT_UNAVAILABLE
            LibraryScanStatus.ACCESS_LOST
        }

        private suspend fun walk(tree: Uri, rootDocument: String): LibraryScanStatus {
            val pending = ArrayDeque<Directory>()
            val seen = mutableMapOf<String, SeenEntry>()
            var retainedBytes = 0L
            fun remember(entry: SafEntry): Boolean {
                val previous = seen[entry.id]
                if (previous != null) {
                    if ((previous != SeenEntry.FILE) != entry.directory) {
                        throw ScanInterrupted(LibraryScanIssue.DIRECTORY_READ)
                    }
                    return false
                }
                retainedBytes += 128L + 2L * entry.id.length
                if (retainedBytes > limits.retainedBytes) throw ScanInterrupted(LibraryScanIssue.MEMORY_LIMIT)
                seen[entry.id] = if (entry.directory) SeenEntry.DIRECTORY_PENDING else SeenEntry.FILE
                return true
            }

            var interrupted = false
            try {
                remember(SafEntry(rootDocument, directory = true))
                pending.addLast(Directory(rootDocument, 0))
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    // Providers can list one ID under multiple parents. Visit shallower paths first
                    // so deduplication preserves the most depth available for its descendants.
                    val dir = pending.removeFirst()
                    var directoryComplete = true
                    access.children(tree, dir.id).catch { failure ->
                        when (failure) {
                            is CancellationException -> throw failure
                            is SafTimeout -> issues += LibraryScanIssue.TIMEOUT
                            is Exception -> issues += LibraryScanIssue.DIRECTORY_READ
                            else -> throw failure
                        }
                        directoryComplete = false
                    }.collect { entry ->
                        currentCoroutineContext().ensureActive()
                        val before = progress.value
                        if (before.visited == limits.entries) throw ScanInterrupted(LibraryScanIssue.ENTRY_LIMIT)
                        progress.value = before.copy(visited = before.visited + 1)
                        if (!remember(entry)) return@collect
                        if (entry.directory) {
                            // The skipped directory stays pending, so the scan remains PARTIAL without
                            // abandoning the sibling folders that are still within the limit.
                            if (dir.depth == limits.depth) {
                                issues += LibraryScanIssue.DEPTH_LIMIT
                            } else pending.addLast(Directory(entry.id, dir.depth + 1))
                        } else {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
                            inspect(uri, entry.displayName)
                        }
                    }
                    if (directoryComplete) {
                        seen[dir.id] = SeenEntry.DIRECTORY_COMPLETE
                        progress.value = progress.value.copy(directories = progress.value.directories + 1)
                    }
                }
            } catch (failure: ScanInterrupted) {
                issues += failure.issue
                interrupted = true
            }

            val rootAccess = checkRoot(tree)
            val incomplete = interrupted || seen.containsValue(SeenEntry.DIRECTORY_PENDING)
            return if (rootAccess == LibraryScanStatus.COMPLETE && incomplete) {
                LibraryScanStatus.PARTIAL
            } else rootAccess
        }

        private suspend fun inspect(uri: Uri, displayName: String?) {
            var scanIssue: LibraryScanIssue? = null
            var rejectedAvailability: Availability? = null
            val audio = try {
                access.analyze(uri, displayName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                scanIssue = when (failure) {
                    is SafTimeout -> LibraryScanIssue.TIMEOUT
                    is SafAudioRejected -> {
                        rejectedAvailability = if (failure.issue == LibraryScanIssue.UNSUPPORTED_AUDIO) {
                            Availability.UNSUPPORTED
                        } else Availability.UNKNOWN
                        failure.issue
                    }
                    else -> LibraryScanIssue.ANALYSIS_FAILED
                }
                null
            }

            scanIssue?.let { issues += it }
            if (audio != null && !audio.standardPath) issues += LibraryScanIssue.UNSUPPORTED_AUDIO

            val id = stableId(uri)
            val sourceId = "saf-source:$id"
            val variantId = "saf-variant:$id"
            val trackId = "saf-track:$id"
            val uriString = uri.toString()
            val updatedAt = System.currentTimeMillis()
            val existing = db.withTransaction {
                if (scans.currentScanId(rootId) != scanId) throw SupersededScan()
                val previous = library.source(sourceId)
                check(previous == null || previous.localUri == uriString)
                if (audio != null) {
                    val track = library.track(trackId) ?: TrackEntity(trackId)
                    val album = audio.album?.let { AlbumEntity("saf-album:$id", it, audio.albumArtist) }
                    library.save(LibraryBatch(
                        sources = listOf(SourceEntity(
                            sourceId, SourceKind.LOCAL, localUri = uriString,
                            availability = if (audio.standardPath) Availability.PLAYABLE else Availability.UNSUPPORTED,
                            grantMode = "PERSISTED_READ", updatedAt = updatedAt,
                        )),
                        tracks = listOf(track.copy(title = audio.title, artistDisplay = audio.artist)),
                        albums = listOfNotNull(album),
                        variants = listOf(VariantEntity(
                            variantId, trackId, sourceId, album?.albumId, audio.disc, audio.track,
                            audio.durationMs, audio.codec, audio.sampleRate, audio.bitDepth, audio.channels,
                            masteringLabel = library.variant(variantId)?.masteringLabel,
                            localMetadata = VariantLocalMetadata(
                                container = audio.container?.name,
                                title = audio.title,
                                artist = audio.artist,
                                album = audio.album,
                                albumArtist = audio.albumArtist,
                                pcmStorageBits = audio.pcm?.storageBits,
                                pcmFloatingPoint = audio.pcm?.floatingPoint,
                                artworkRef = audio.artwork?.let { "embedded:${it.offset}:${it.length}:${it.mimeType}" },
                            ),
                        )),
                    ))
                } else if (previous != null) {
                    val availability = rejectedAvailability
                        ?: if (previous.availability == Availability.MISSING) Availability.UNKNOWN else null
                    if (availability != null && availability != previous.availability) {
                        library.save(LibraryBatch(sources = listOf(previous.copy(
                            availability = availability, updatedAt = updatedAt,
                        ))))
                    }
                }
                if (audio != null || previous != null) {
                    scans.recordVisit(LocalScanSource(sourceId, rootId, scanId))
                }
                previous != null
            }
            val before = progress.value
            progress.value = when {
                scanIssue != null -> before.copy(failed = before.failed + 1)
                audio == null -> before.copy(skipped = before.skipped + 1)
                existing -> before.copy(updated = before.updated + 1)
                else -> before.copy(indexed = before.indexed + 1)
            }
        }

        suspend fun finish(status: LibraryScanStatus): LibraryScanResult {
            var finalStatus = status
            var missing = 0
            try {
                missing = db.withTransaction {
                    // Only the latest scan of a registered root matches, so superseded scans record nothing.
                    val current = scans.recordStatus(rootId, scanId, status) == 1
                    when {
                        status != LibraryScanStatus.COMPLETE -> 0
                        current -> scans.markUnvisitedMissing(rootId, scanId, System.currentTimeMillis())
                        else -> throw SupersededScan()
                    }
                }
            } catch (_: SupersededScan) {
                finalStatus = LibraryScanStatus.PARTIAL
                issues += LibraryScanIssue.SUPERSEDED
            } catch (_: Exception) {
                if (status == LibraryScanStatus.COMPLETE) finalStatus = LibraryScanStatus.PARTIAL
                issues += LibraryScanIssue.STORAGE_ERROR
            }
            return result(finalStatus, missing)
        }

        fun result(status: LibraryScanStatus, missing: Int = 0) =
            LibraryScanResult(scanId, status, progress.value, missing, issues.toSet())
    }

    private enum class SeenEntry { FILE, DIRECTORY_PENDING, DIRECTORY_COMPLETE }
    private data class Directory(val id: String, val depth: Int)
    private class ScanInterrupted(val issue: LibraryScanIssue) : Exception()
    private class SupersededScan : Exception()

    companion object {
        fun openTreeIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )

        private fun stableId(uri: Uri): UUID = UUID.nameUUIDFromBytes(uri.toString().toByteArray(Charsets.UTF_8))

        private class RootLock(val mutex: Mutex = Mutex(), var users: Int = 0)

        private val rootLocks = mutableMapOf<String, RootLock>()

        private suspend fun <T> withRootLock(id: String, block: suspend () -> T): T {
            val lock = synchronized(rootLocks) { rootLocks.getOrPut(id, ::RootLock).also { it.users++ } }
            try {
                return lock.mutex.withLock { block() }
            } finally {
                synchronized(rootLocks) {
                    if (--lock.users == 0) rootLocks.remove(id)
                }
            }
        }
    }
}
