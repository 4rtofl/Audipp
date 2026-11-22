package com.audipp.playback

import android.database.sqlite.SQLiteException
import com.audipp.data.db.LibraryDatabase
import com.audipp.data.db.PlaybackHistoryDao
import com.audipp.domain.model.PermissionState
import com.audipp.domain.model.ProviderUse
import com.audipp.domain.model.SourceKind
import com.audipp.integrations.streaming.ProviderAccessGate
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class PlaybackHistoryJournal(database: LibraryDatabase, gate: ProviderAccessGate) {
    // Conflation would discard completed segments and attempts queued behind a Room write.
    private val writes = Channel<PlaybackHistoryWrite>(Channel.UNLIMITED)

    init {
        val writer = CoroutineScope(Dispatchers.IO).launch {
            val dao = database.playbackHistoryDao()
            for (write in writes) {
                try {
                    dao.saveHistory(write, gate)
                } catch (_: IOException) {
                    // An unreadable policy cannot authorize a write.
                } catch (_: SQLiteException) {
                    // Each Room transaction rolls back independently of playback.
                }
            }
        }
        writer.invokeOnCompletion { writes.close() }
    }

    fun enqueue(write: PlaybackHistoryWrite) {
        writes.trySend(write)
    }

    fun close() {
        writes.close()
    }
}

internal suspend fun PlaybackHistoryDao.saveHistory(write: PlaybackHistoryWrite, gate: ProviderAccessGate) {
    if (write.event.sourceKind == SourceKind.PROVIDER) {
        val scope = write.providerScope ?: return
        if (gate.permissionState(scope, ProviderUse.HISTORY_STORAGE) != PermissionState.ALLOWED) return
    }
    save(write.event, write.segment)
}
