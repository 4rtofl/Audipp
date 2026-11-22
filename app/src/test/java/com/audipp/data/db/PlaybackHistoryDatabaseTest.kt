package com.audipp.data.db

import android.content.ContextWrapper
import androidx.room.Room
import com.audipp.data.preferences.providerPolicyStore
import com.audipp.domain.model.PermissionState
import com.audipp.domain.model.ProviderPermissionEvidence
import com.audipp.domain.model.ProviderScope
import com.audipp.domain.model.ProviderUse
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.UsePermission
import com.audipp.integrations.streaming.ProviderAccessGate
import com.audipp.playback.PlaybackHistoryJournal
import com.audipp.playback.PlaybackHistoryWrite
import com.audipp.playback.saveHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlaybackHistoryDatabaseTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val event = PlayEventEntity(
        "event", "session", "entry", "track", "variant", "source",
        SourceKind.LOCAL, "pcm", 100, "UTC", 1_000,
    )
    private val segment = PlaySegmentEntity("segment", event.id, 100, "UTC", 250, 0, 250, 1.0, null, null)

    @Test
    fun checkpointsReplaceTheSameSegmentAndTheFinalEventIsIdempotent() = runBlocking {
        withDatabase(Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()) { database ->
            val dao = database.playbackHistoryDao()
            dao.save(event, segment)
            assertFalse(dao.event(event.id)!!.qualified)
            assertNull(dao.event(event.id)!!.endUtcMs)

            val updated = segment.copy(listenedMs = 500, sourceEndMs = 500)
            dao.save(event, updated)
            dao.save(event, updated)
            val final = event.copy(endUtcMs = 600)
            dao.save(final, null)
            dao.save(final, null)
            dao.save(event, segment)
            assertEquals(final.copy(playedMs = 500, qualified = true), dao.event(event.id))
            assertEquals(listOf(updated), dao.segments(event.id))
        }
    }

    @Test
    fun homeReceivesJournalWritesThroughTheSharedDatabaseAfterTheWriterCloses() = runBlocking {
        val uiDatabase = ContextWrapper(context).libraryDatabase
        val serviceDatabase = ContextWrapper(context).libraryDatabase
        assertSame(uiDatabase, serviceDatabase)
        uiDatabase.libraryDao().save(LibraryBatch(tracks = listOf(TrackEntity(event.trackId, title = "Test recording"))))

        val journal = PlaybackHistoryJournal(serviceDatabase, ProviderAccessGate(context.providerPolicyStore))
        val later = event.copy(id = "later-event", trackId = "removed-track", startUtcMs = 200)
        val observed = withTimeout(10_000) {
            uiDatabase.playbackHistoryDao().observeRecentPlays().onEach { plays ->
                if (plays.isEmpty()) {
                    journal.enqueue(PlaybackHistoryWrite(event, segment, null))
                    journal.enqueue(PlaybackHistoryWrite(later, segment.copy(id = "later-segment", eventId = later.id), null))
                    journal.enqueue(PlaybackHistoryWrite(event.copy(id = "unplayed", startUtcMs = 300), null, null))
                    journal.close()
                }
            }.first { it.size == 2 }
        }
        assertEquals(listOf(RecentPlay(null, 200), RecentPlay("Test recording", 100)), observed)
        assertEquals(observed, uiDatabase.playbackHistoryDao().observeRecentPlays().first())
    }

    @Test
    fun providerHistoryRequiresAnAllowedAndUnexpiredStorageGrant() = runBlocking {
        val store = context.providerPolicyStore
        val scope = ProviderScope("provider", "account", "app", "JP", "contract")
        val evidence = ProviderPermissionEvidence("evidence", "conditions", 10, 200)
        val allowed = UsePermission(PermissionState.ALLOWED, evidence)
        var now = 100L
        val gate = ProviderAccessGate(store, nowEpochMs = { now })
        withDatabase(Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()) { database ->
            val dao = database.playbackHistoryDao()
            val provider = event.copy(id = "provider-event", sourceKind = SourceKind.PROVIDER)
            val write = PlaybackHistoryWrite(provider, segment.copy(eventId = provider.id), scope)
            store.setPermission(scope, ProviderUse.DIRECT_PLAYBACK, allowed)
            dao.saveHistory(write, gate)
            assertNull(dao.event(provider.id))
            store.setPermission(scope, ProviderUse.HISTORY_STORAGE, UsePermission(PermissionState.DENIED))
            dao.saveHistory(write, gate)
            assertNull(dao.event(provider.id))
            store.setPermission(scope, ProviderUse.HISTORY_STORAGE, allowed)
            dao.saveHistory(write.copy(providerScope = null), gate)
            dao.saveHistory(write.copy(providerScope = scope.copy(accountId = "another-account")), gate)
            assertNull(dao.event(provider.id))
            now = 200
            dao.saveHistory(write, gate)
            assertNull(dao.event(provider.id))
            assertTrue(dao.segments(provider.id).isEmpty())
            dao.saveHistory(PlaybackHistoryWrite(event, null, null), gate)
            assertNotNull(dao.event(event.id))
            now = 100
            dao.saveHistory(write, gate)
            assertEquals(250L, dao.event(provider.id)!!.playedMs)
            assertEquals(listOf(write.segment), dao.segments(provider.id))
        }
    }

    @Test
    fun migratesV5PreservingQueuePlaylistAndCheckpointAndHistorySurvivesQueueRemoval() = runBlocking {
        val name = "history-migration-v5.db"
        context.createLibraryDatabaseFromSchema(name, version = 5) { old ->
            old.execSQL("INSERT INTO sources (source_id, kind, availability, updated_at) VALUES ('source', 'LOCAL', 'PLAYABLE', 1)")
            old.execSQL("INSERT INTO tracks (track_id) VALUES ('track')")
            old.execSQL("INSERT INTO track_variants (variant_id, track_id, source_id) VALUES ('variant', 'track', 'source')")
            old.execSQL("INSERT INTO queues (queue_id, created_at, updated_at, revision, shuffle_enabled, repeat_mode) " +
                "VALUES ('queue', 1, 2, 7, 0, 'OFF')")
            old.execSQL("INSERT INTO queue_items (entry_id, queue_id, ordinal, variant_id, added_at) " +
                "VALUES ('entry', 'queue', 0, 'variant', 2)")
            old.execSQL("INSERT INTO playback_checkpoint (slot_id, queue_id, queue_revision, entry_id, position_ms, saved_at) " +
                "VALUES (1, 'queue', 7, 'entry', 42, 3)")
            old.execSQL("INSERT INTO playlists (playlist_id, name, created_at, updated_at, revision) VALUES ('list', 'List', 1, 2, 3)")
            old.execSQL("INSERT INTO playlist_items (entry_id, playlist_id, ordinal, variant_id) " +
                "VALUES ('playlist-entry', 'list', 0, 'variant')")
        }
        try {
            withDatabase(LibraryDatabase.open(context, name)) { database ->
                val queues = database.queueDao()
                val current = queues.read("queue")!!.entries.single()
                assertEquals("entry", current.entryId.value)
                assertEquals("playlist-entry", database.playlistDao().read("list")!!.entries.single().entryId)
                assertEquals(PlaybackCheckpointEntity("queue", 7, "entry", 42, 3), queues.checkpoint())
                assertEquals(6, database.openHelper.writableDatabase.version)

                val history = database.playbackHistoryDao()
                history.save(event.copy(endUtcMs = 350), segment)
                assertEquals(QueueEditResult.StopRequired(current.entryId), queues.replaceEntries("queue", 7, emptyList(), 4))
                assertEquals(QueueEditResult.Updated(8), queues.replaceEntries("queue", 7, emptyList(), 4, current.entryId))
                assertEquals(250L, history.event(event.id)!!.playedMs)
                assertEquals(listOf(segment), history.segments(event.id))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private suspend fun withDatabase(database: LibraryDatabase, test: suspend (LibraryDatabase) -> Unit) {
        try {
            test(database)
        } finally {
            database.close()
        }
    }
}
