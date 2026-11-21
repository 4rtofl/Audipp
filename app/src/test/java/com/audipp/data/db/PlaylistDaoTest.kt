package com.audipp.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import com.audipp.data.db.PlaylistEditResult.NAME_CONFLICT
import com.audipp.data.db.PlaylistEditResult.STALE_REVISION
import com.audipp.data.db.PlaylistEditResult.UPDATED
import com.audipp.domain.model.Availability
import com.audipp.domain.model.QueueEntry
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.VariantId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlaylistDaoTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: LibraryDatabase
    private val playlists get() = db.playlistDao()
    private val queues get() = db.queueDao()
    private val variant = VariantId("variant")
    private val otherVariant = VariantId("other")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun createRenameAndDeleteHandleNameCollisionsAndStaleRevisions() = runBlocking {
        assertEquals(UPDATED, playlists.create("one", " One ", 1))
        assertEquals(NAME_CONFLICT, playlists.create("duplicate", "One", 1))
        assertEquals(UPDATED, playlists.create("two", "Two", 1))

        assertEquals(NAME_CONFLICT, playlists.rename("two", 0, " One ", 2))
        assertEquals(UPDATED, playlists.rename("two", 0, "New", 2))
        assertEquals(STALE_REVISION, playlists.rename("two", 0, "Stale", 3))
        assertEquals(STALE_REVISION, playlists.delete("two", 0))
        assertEquals(listOf("New", "One"), playlists.list().map { it.name })
        assertEquals(UPDATED, playlists.delete("two", 1))
        assertNull(playlists.read("two"))
    }

    @Test
    fun mixedEditsAndReversalKeepOrderAndRollBackOnInvalidVariant() = runBlocking {
        seedLibrary()
        val a = PlaylistEntry("a", variant)
        val b = PlaylistEntry("b", otherVariant)
        val c = PlaylistEntry("c", variant)
        val d = PlaylistEntry("d", variant)
        playlists.create("list", "List", 1)
        assertEquals(UPDATED, playlists.replaceEntries("list", 0, listOf(a, b, c), 2))
        assertEquals(UPDATED, playlists.replaceEntries("list", 1, listOf(b, a, c), 3))
        assertEquals(UPDATED, playlists.replaceEntries("list", 2, listOf(c, a, b), 4))
        assertEquals(UPDATED, playlists.replaceEntries("list", 3, listOf(d, b, c), 5))
        val before = playlists.read("list")
        assertEquals(listOf(d, b, c), before?.entries)
        assertEquals(STALE_REVISION, playlists.replaceEntries("list", 3, emptyList(), 6))
        try {
            val unknown = PlaylistEntry("unknown", VariantId("missing-variant"))
            playlists.replaceEntries("list", 4, listOf(c, unknown), 6)
            fail("The new item must reference an existing variant")
        } catch (_: SQLiteConstraintException) {
            assertEquals(before, playlists.read("list"))
        }
    }

    @Test
    fun appendCopiesDuplicatesAndUnavailableVariantsWithoutLinkingPlaylistAndQueue() = runBlocking {
        val saved = listOf(PlaylistEntry("a", variant), PlaylistEntry("b", variant), PlaylistEntry("c", otherVariant))
        val current = QueueEntry(QueueEntryId("current"), otherVariant)
        seedLibrary()
        playlists.create("list", "List", 1)
        assertEquals(UPDATED, playlists.replaceEntries("list", 0, saved, 2))
        db.libraryDao().markSourceMissing("source", 3)
        queues.create("queue", null, 1)
        queues.replaceEntries("queue", 0, listOf(current), 2)
        val checkpoint = PlaybackCheckpointEntity("queue", 1, current.entryId.value, 9_000, 2)
        assertTrue(queues.saveCheckpoint(checkpoint))

        assertEquals(PlaylistCopyResult.Copied(2), db.appendPlaylistToQueue("list", 1, "queue", 1, 4))
        val queue = requireNotNull(queues.read("queue"))
        assertEquals(current, queue.entries.first())
        val copied = queue.entries.drop(1)
        assertEquals(saved.map { it.variantId }, copied.map { it.variantId })
        assertEquals(copied.size, copied.map { it.entryId }.toSet().size)
        assertFalse(copied.any { copy -> saved.any { it.entryId == copy.entryId.value } })
        assertEquals(Availability.MISSING, db.libraryDao().trackVariant(copied.last().variantId)?.source?.availability)
        assertEquals(checkpoint.copy(queueRevision = 2), queues.checkpoint())

        assertEquals(UPDATED, playlists.replaceEntries("list", 1, saved.reversed(), 5))
        assertEquals(queue, queues.read("queue"))
        assertEquals(QueueEditResult.Updated(3), queues.replaceEntries("queue", 2, queue.entries.reversed(), 6))
        assertEquals(saved.reversed(), playlists.read("list")?.entries)
        assertEquals(UPDATED, playlists.delete("list", 2))
        assertNull(playlists.read("list"))
        assertEquals(queue.entries.reversed(), queues.read("queue")?.entries)
        assertEquals(checkpoint.copy(queueRevision = 3), queues.checkpoint())
    }

    @Test
    fun appendRejectsStalePlaylistOrQueueWithoutChangingTarget() = runBlocking {
        seedLibrary()
        playlists.create("list", "List", 1)
        playlists.replaceEntries("list", 0, listOf(PlaylistEntry("a", variant)), 2)
        queues.create("queue", null, 1)
        assertEquals(PlaylistCopyResult.StalePlaylist, db.appendPlaylistToQueue("list", 0, "queue", 0, 3))
        assertEquals(PlaylistCopyResult.StaleQueue, db.appendPlaylistToQueue("list", 1, "queue", 1, 3))
        assertEquals(emptyList<QueueEntry>(), queues.read("queue")?.entries)
        assertEquals(0L, queues.read("queue")?.queue?.revision)
    }

    @Test
    fun migratesV2WithoutDroppingQueueOrCheckpoint() = runBlocking {
        val name = "playlist-migration.db"
        context.createLibraryDatabaseFromSchema(name, version = 2) { old ->
            old.execSQL(
                "INSERT INTO sources (source_id, kind, availability, updated_at) " +
                    "VALUES ('source', 'LOCAL', 'PLAYABLE', 1)",
            )
            old.execSQL("INSERT INTO tracks (track_id) VALUES ('track')")
            old.execSQL(
                "INSERT INTO track_variants (variant_id, track_id, source_id) " +
                    "VALUES ('variant', 'track', 'source')",
            )
            old.execSQL(
                "INSERT INTO queues (queue_id, created_at, updated_at, revision, shuffle_enabled, repeat_mode) " +
                    "VALUES ('queue', 1, 2, 7, 0, 'OFF')",
            )
            old.execSQL(
                "INSERT INTO queue_items (entry_id, queue_id, ordinal, variant_id, added_at) " +
                    "VALUES ('a', 'queue', 1, 'variant', 2), ('b', 'queue', 0, 'variant', 2)",
            )
            old.execSQL(
                "INSERT INTO playback_checkpoint (slot_id, queue_id, queue_revision, entry_id, position_ms, saved_at) " +
                    "VALUES (1, 'queue', 7, 'a', 9000, 2)",
            )
        }
        val upgraded = LibraryDatabase.open(context, name)
        try {
            val queue = requireNotNull(upgraded.queueDao().read("queue"))
            assertEquals(listOf(QueueEntry(QueueEntryId("b"), variant), QueueEntry(QueueEntryId("a"), variant)), queue.entries)
            assertEquals(7L, queue.queue.revision)
            assertEquals(PlaybackCheckpointEntity("queue", 7, "a", 9_000, 2), upgraded.queueDao().checkpoint())
            assertEquals(UPDATED, upgraded.playlistDao().create("list", "List", 3))
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun seedLibrary() {
        db.libraryDao().save(
            LibraryBatch(
                sources = listOf(SourceEntity("source", SourceKind.LOCAL, updatedAt = 1)),
                tracks = listOf(TrackEntity("track")),
                variants = listOf(variant, otherVariant).map { VariantEntity(it.value, "track", "source") },
            ),
        )
    }
}
