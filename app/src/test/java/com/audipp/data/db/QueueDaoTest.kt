package com.audipp.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
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
class QueueDaoTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: LibraryDatabase
    private val dao get() = db.queueDao()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun swapsReversalAndMixedEditsKeepTheCurrentEntryAndDuplicateMissingVariants() = runBlocking {
        val a = entry("a")
        val b = entry("b")
        val c = entry("c")
        val d = entry("d")
        prepareQueue(listOf(a, b, c), current = b)
        db.libraryDao().markSourceMissing("source", 2)

        assertUpdated(1, listOf(b, a, c))
        assertUpdated(2, listOf(c, a, b))
        assertUpdated(3, listOf(d, b, a))

        assertEquals(checkpoint(b).copy(queueRevision = 4), dao.checkpoint())
        assertEquals(Availability.MISSING, db.libraryDao().trackVariant(b.variantId)?.source?.availability)
        assertFalse(dao.saveCheckpoint(checkpoint(c).copy(queueRevision = 4)))
        assertEquals(QueueEditResult.StaleRevision, dao.replaceEntries("queue", 3, emptyList(), 5))
    }

    @Test
    fun failedInsertRollsBackOrderRevisionAndCheckpoint() = runBlocking {
        val a = entry("a")
        val b = entry("b")
        prepareQueue(listOf(a, b), current = a)
        val before = dao.read("queue")

        try {
            dao.replaceEntries("queue", 1, listOf(b, entry("unknown", "missing-variant")), 2, a.entryId)
            fail("The new entry must reference an existing variant")
        } catch (_: SQLiteConstraintException) {
            assertEquals(before, dao.read("queue"))
            assertEquals(checkpoint(a), dao.checkpoint())
        }
    }

    @Test
    fun clearRequiresStopForTheCurrentEntryAndRejectsLateCheckpoint() = runBlocking {
        val a = entry("a")
        val b = entry("b")
        prepareQueue(listOf(a, b), current = a)

        assertEquals(QueueEditResult.StopRequired(a.entryId), dao.replaceEntries("queue", 1, emptyList(), 2))
        assertEquals(listOf(a, b), dao.read("queue")?.entries)
        assertTrue(dao.saveCheckpoint(checkpoint(b)))
        assertEquals(
            QueueEditResult.StopRequired(b.entryId),
            dao.replaceEntries("queue", 1, emptyList(), 2, a.entryId),
        )
        assertUpdated(1, emptyList(), b.entryId)

        assertEquals(checkpoint(b).copy(queueRevision = 2, entryId = null, positionMs = null), dao.checkpoint())
        assertFalse(dao.saveCheckpoint(checkpoint(b)))
    }

    @Test
    fun removingTheStoppedLiveEntryClearsAnOlderCheckpointForARetainedEntry() = runBlocking {
        val a = entry("a")
        val b = entry("b")
        prepareQueue(listOf(a, b), current = a)

        assertUpdated(1, listOf(a), b.entryId)

        assertEquals(checkpoint(a).copy(queueRevision = 2, entryId = null, positionMs = null), dao.checkpoint())
    }

    @Test
    fun migratesTheExportedV1SchemaWithoutDroppingLibraryRows() = runBlocking {
        val name = "queue-migration.db"
        context.createLibraryDatabaseFromSchema(name, version = 1) { old ->
            old.execSQL("INSERT INTO tracks (track_id, title) VALUES ('song', 'Before migration')")
        }
        val upgraded = LibraryDatabase.open(context, name)
        try {
            assertEquals("Before migration", upgraded.libraryDao().track("song")?.title)
            upgraded.queueDao().create("queue", null, 1)
            assertEquals(emptyList<QueueEntry>(), upgraded.queueDao().read("queue")?.entries)
            assertNull(upgraded.queueDao().checkpoint())
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun prepareQueue(entries: List<QueueEntry>, current: QueueEntry) {
        db.libraryDao().save(
            LibraryBatch(
                sources = listOf(SourceEntity("source", SourceKind.LOCAL, updatedAt = 1)),
                tracks = listOf(TrackEntity("track")),
                variants = listOf(VariantEntity("variant", "track", "source")),
            ),
        )
        dao.create("queue", null, 1)
        assertUpdated(0, entries)
        assertTrue(dao.saveCheckpoint(checkpoint(current)))
    }

    private suspend fun assertUpdated(revision: Long, entries: List<QueueEntry>, stopped: QueueEntryId? = null) {
        assertEquals(
            QueueEditResult.Updated(revision + 1),
            dao.replaceEntries("queue", revision, entries, revision + 1, stopped),
        )
        assertEquals(entries, dao.read("queue")?.entries)
        assertEquals(revision + 1, dao.read("queue")?.queue?.revision)
    }

    private fun entry(id: String, variant: String = "variant") = QueueEntry(QueueEntryId(id), VariantId(variant))

    private fun checkpoint(current: QueueEntry) =
        PlaybackCheckpointEntity("queue", 1, current.entryId.value, 12_000, 2)
}
