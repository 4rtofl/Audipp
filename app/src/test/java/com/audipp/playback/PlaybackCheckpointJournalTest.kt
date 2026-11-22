package com.audipp.playback

import androidx.room.Room
import com.audipp.data.db.LibraryBatch
import com.audipp.data.db.LibraryDatabase
import com.audipp.data.db.QueueEditResult
import com.audipp.data.db.SourceEntity
import com.audipp.data.db.TrackEntity
import com.audipp.data.db.VariantEntity
import com.audipp.domain.model.Availability
import com.audipp.domain.model.ExecutionPlan
import com.audipp.domain.model.PermissionState
import com.audipp.domain.model.PlaybackCapabilities
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.PlaybackSnapshot
import com.audipp.domain.model.QueueEntry
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.VariantId
import com.audipp.playback.source.AdapterKey
import com.audipp.playback.source.AdapterResolution
import com.audipp.playback.source.LocalDocumentSourceAdapter
import com.audipp.playback.source.PlaybackTarget
import com.audipp.playback.source.PlaybackPermissionChecker
import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.ResolutionFailure
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceAdapter
import com.audipp.playback.source.SourceDescriptor
import com.audipp.playback.source.SourceResolution
import com.audipp.playback.source.SourceResolver
import com.audipp.playback.source.TargetKind
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class PlaybackCheckpointJournalTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val databaseName = "checkpoint-journal-test.db"
    private val entry = QueueEntry(QueueEntryId("current"), VariantId("variant"))
    private val queueId = "queue"
    private lateinit var db: LibraryDatabase
    private val queues get() = db.queueDao()
    private val route = ResolutionContext(PlaybackTarget("local", TargetKind.DEVICE), 0, TransitionId("restore"))
    private var availability = Availability.PLAYABLE
    private var currentRoute = route
    private var canReadPosition = true
    private var onResolve: suspend () -> Unit = {}
    private val resolver = SourceResolver(
        mapOf(
            AdapterKey(SourceKind.LOCAL, TargetKind.DEVICE) to SourceAdapter { request ->
                onResolve()
                LocalDocumentSourceAdapter(
                    PlaybackCapabilities(canPlay = true, canReadPosition = canReadPosition),
                ) { availability }.resolve(request)
            },
            AdapterKey(SourceKind.PROVIDER, TargetKind.EXTERNAL_APP, "provider") to SourceAdapter { request ->
                AdapterResolution.Available(
                    request.variant.variantId, request.variant.source.sourceId,
                    SourceDescriptor.Handoff("https://example.invalid/item"),
                    PlaybackCapabilities(canPlay = true, canReadPosition = true),
                )
            },
        ),
        { currentRoute },
        PlaybackPermissionChecker { _, _ -> PermissionState.ALLOWED },
    )

    @Before
    fun setUp() {
        context.deleteDatabase(databaseName)
        db = LibraryDatabase.open(context, databaseName)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun periodicSaveSurvivesReopenWithoutAnExitSave() = runBlocking {
        seed()
        withJournal {
            assertEquals(5_000L, intervals.receive())
            sample = frame(5_000)
            now = 6_000
            val enqueuedAt = System.nanoTime()
            ticks.send(Unit)
            val write = awaitWrite(now)
            val delayMs = (System.nanoTime() - enqueuedAt).nanoseconds.inWholeMilliseconds
            assertEquals(CheckpointWriteOutcome.SAVED, write.outcome)
            assertEquals(5_000L, write.checkpoint.positionMs)
            assertEquals(5_000L, intervals.receive())
            println("checkpoint interval=5000ms, host tick-to-commit observation=${delayMs}ms")
        }

        db.close()
        db = LibraryDatabase.open(context, databaseName)
        val restored = requireNotNull(restore())
        assertEquals(entry, restored.entry)
        assertEquals(5_000L, restored.positionMs)
    }

    @Test
    fun confirmedSeekPauseAndStopDoNotWaitForThePeriodicTick() = runBlocking {
        seed()
        withJournal {
            assertEquals(5_000L, intervals.receive())
            journal.saveConfirmed(frame(30_000))
            assertEquals(30_000L, awaitWrite(now).checkpoint.positionMs)
            now++
            journal.saveConfirmed(frame(30_500, PlaybackPhase.PAUSED))
            assertEquals(30_500L, awaitWrite(now).checkpoint.positionMs)
            now++
            journal.saveConfirmed(frame(30_800, PlaybackPhase.PAUSED))
            assertEquals(30_800L, awaitWrite(now).checkpoint.positionMs)
            now++
            journal.saveConfirmed(PlaybackCheckpointSample(queueId, 1, PlaybackSnapshot(0, PlaybackPhase.IDLE)))
            assertEquals(CheckpointWriteOutcome.SAVED, awaitWrite(now).outcome)
            val restored = requireNotNull(restore())
            assertEquals(entry, restored.entry)
            assertEquals(30_800L, restored.positionMs)
        }
    }

    @Test
    fun timerSkipsPausedAndUnknownPlaybackPosition() = runBlocking {
        seed()
        withJournal {
            intervals.receive()
            sample = frame(10_000, PlaybackPhase.PAUSED)
            ticks.send(Unit)
            intervals.receive()
            sample = frame(null)
            ticks.send(Unit)
            intervals.receive()
            assertNull(queues.checkpoint())
            journal.saveConfirmed(frame(null, PlaybackPhase.PAUSED))
            assertNull(awaitWrite(now).checkpoint.positionMs)
            assertNull(restore()?.positionMs)
        }
    }

    @Test
    fun staleRevisionCannotRestoreAnEntryRemovedFromTheQueue() = runBlocking {
        seed()
        withJournal {
            journal.saveConfirmed(frame(12_000))
            assertEquals(CheckpointWriteOutcome.SAVED, awaitWrite(now).outcome)
            assertEquals(
                QueueEditResult.Updated(2),
                queues.replaceEntries(queueId, 1, emptyList(), 3, stoppedEntryId = entry.entryId),
            )
            now++
            journal.saveConfirmed(frame(30_000))
            assertEquals(CheckpointWriteOutcome.STALE_QUEUE, awaitWrite(now).outcome)
            val restored = requireNotNull(restore())
            assertEquals(emptyList<QueueEntry>(), restored.queue.entries)
            assertNull(restored.entry)
            assertNull(restored.positionMs)
        }
    }

    @Test
    fun failedWriteLeavesTheLatestCommitAndWriterRecovers() = runBlocking {
        seed()
        withJournal {
            journal.saveConfirmed(frame(12_000))
            assertEquals(CheckpointWriteOutcome.SAVED, awaitWrite(now).outcome)
            withContext(Dispatchers.IO) {
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_checkpoint BEFORE UPDATE ON playback_checkpoint " +
                        "BEGIN SELECT RAISE(ABORT, 'forced failure'); END",
                )
            }
            now++
            journal.saveConfirmed(frame(20_000))
            assertEquals(CheckpointWriteOutcome.STORAGE_ERROR, awaitWrite(now).outcome)
            assertEquals(12_000L, restore()?.positionMs)

            withContext(Dispatchers.IO) {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_checkpoint")
            }
            now++
            journal.saveConfirmed(frame(22_000))
            assertEquals(CheckpointWriteOutcome.SAVED, awaitWrite(now).outcome)
            assertEquals(22_000L, restore()?.positionMs)
        }
    }

    @Test
    fun restoreClampsToTheSelectedVariantAndClassifiesResolutionFailures() = runBlocking {
        seed()
        withJournal {
            journal.saveConfirmed(frame(70_000))
            awaitWrite(now)
            assertEquals(60_000L, restore()?.positionMs)
            availability = Availability.AUTH_REQUIRED
            val retryable = requireNotNull(restore())
            assertEquals(entry, retryable.entry)
            assertEquals(60_000L, retryable.positionMs)
            assertEquals(ResolutionFailure.AUTH_REQUIRED, retryable.failure)
            availability = Availability.PLAYABLE
            currentRoute = route.copy(target = PlaybackTarget("external", TargetKind.EXTERNAL_APP))
            assertQueueOnly(ResolutionFailure.PLAYBACK_UNSUPPORTED)
            currentRoute = route
            db.libraryDao().markSourceMissing("source", 4)
            assertQueueOnly(ResolutionFailure.SOURCE_MISSING)
        }
    }

    @Test
    fun restoreRetriesStaleRouteOnceAndReturnsTheSecondFailure() = runBlocking {
        seed()
        withJournal {
            journal.saveConfirmed(frame(12_000))
            awaitWrite(now)
            var attempts = 0
            onResolve = {
                attempts++
                if (attempts == 1) currentRoute = route.copy(routeRevision = 1)
            }
            assertEquals(12_000L, restore()?.positionMs)
            assertEquals(2, attempts)

            attempts = 0
            onResolve = {
                attempts++
                currentRoute = currentRoute.copy(transitionId = TransitionId("transition-$attempts"))
            }
            val restored = requireNotNull(restore())
            assertEquals(2, attempts)
            assertEquals(entry, restored.entry)
            assertEquals(12_000L, restored.positionMs)
            assertEquals(ResolutionFailure.STALE_TRANSITION, restored.failure)
        }
    }

    @Test
    fun saveDoesNotStorePositionForHandoffOrWithoutPositionCapability() = runBlocking {
        seed()
        canReadPosition = false
        withJournal {
            journal.saveConfirmed(frame(12_000, plan = resolvePlan()))
            assertEquals(CheckpointWriteOutcome.SAVED, awaitWrite(now).outcome)
            val checkpoint = requireNotNull(queues.checkpoint())
            assertEquals(entry.entryId.value, checkpoint.entryId)
            assertNull(checkpoint.positionMs)
        }

        seedProvider()
        withJournal {
            journal.saveConfirmed(frame(12_000, plan = resolvePlan()))
            assertEquals(CheckpointWriteOutcome.SAVED, awaitWrite(now).outcome)
            val checkpoint = requireNotNull(queues.checkpoint())
            assertEquals(entry.entryId.value, checkpoint.entryId)
            assertNull(checkpoint.positionMs)
        }
    }

    @Test
    fun restoreDoesNotReturnPositionForHandoffOrWithoutPositionCapability() = runBlocking {
        seed()
        withJournal {
            journal.saveConfirmed(frame(12_000))
            awaitWrite(now)
            canReadPosition = false
            assertRestoredWithoutPosition()

            seedProvider()
            assertRestoredWithoutPosition()
        }
    }

    @Test
    fun confirmedSavesReturnWhileWriterIsBlockedAndOnlyLatestCommits() = runBlocking {
        seed()
        withContext(Dispatchers.IO) {
            db.openHelper.writableDatabase.execSQL(
                "CREATE TABLE checkpoint_writes (position_ms INTEGER)",
            )
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER record_checkpoint AFTER INSERT ON playback_checkpoint " +
                    "BEGIN INSERT INTO checkpoint_writes VALUES (NEW.position_ms); END",
            )
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER record_checkpoint_update AFTER UPDATE ON playback_checkpoint " +
                    "BEGIN INSERT INTO checkpoint_writes VALUES (NEW.position_ms); END",
            )
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            withTimeout(5_000) {
                val writerScope = CoroutineScope(coroutineContext)
                val journal = withContext(dispatcher) {
                    // The writer cannot start until this block yields the only dispatcher thread.
                    PlaybackCheckpointJournal(writerScope, queues, { null }, writerDispatcher = dispatcher).also {
                        it.saveConfirmed(frame(10_000))
                        it.saveConfirmed(frame(20_000))
                        it.saveConfirmed(frame(30_000))
                    }
                }
                val result = journal.lastWrite.filterNotNull().first()
                writerScope.coroutineContext[Job]?.children?.forEach { it.cancelAndJoin() }
                assertEquals(CheckpointWriteOutcome.SAVED, result.outcome)
                withContext(Dispatchers.IO) {
                    db.openHelper.writableDatabase.query("SELECT position_ms FROM checkpoint_writes").use {
                        assertEquals(1, it.count)
                        it.moveToFirst()
                        assertEquals(30_000L, it.getLong(0))
                    }
                }
            }
        }
    }

    @Test
    fun restoreDiscardsAResultWhenCheckpointChangesDuringResolution() = runBlocking {
        seed()
        withJournal {
            journal.saveConfirmed(frame(12_000))
            awaitWrite(now)
            onResolve = {
                now++
                journal.saveConfirmed(frame(20_000))
                awaitWrite(now)
            }
            assertNull(restore())
            onResolve = {}
            assertEquals(20_000L, restore()?.positionMs)
        }
    }

    @Test
    fun unknownVariantLengthKeepsPositionUnknown() = runBlocking {
        seed(durationMs = null)
        withJournal {
            journal.saveConfirmed(frame(12_000))
            awaitWrite(now)
            assertEquals(entry, restore()?.entry)
            assertNull(restore()?.positionMs)
        }
    }

    private suspend fun assertQueueOnly(failure: ResolutionFailure) {
        val restored = requireNotNull(restore())
        assertEquals(listOf(entry), restored.queue.entries)
        assertNull(restored.entry)
        assertNull(restored.positionMs)
        assertEquals(failure, restored.failure)
    }

    private suspend fun assertRestoredWithoutPosition() {
        val restored = requireNotNull(restore())
        assertEquals(entry, restored.entry)
        assertNull(restored.positionMs)
        assertNull(restored.failure)
    }

    private suspend fun restore() = restorePlaybackCheckpoint(db, resolver) { currentRoute }

    private suspend fun resolvePlan(): ExecutionPlan {
        val variant = requireNotNull(db.libraryDao().trackVariant(entry.variantId))
        return (resolver.resolve(ResolutionRequest(variant, currentRoute)) as SourceResolution.Resolved).plan
    }

    private suspend fun seedProvider() {
        db.libraryDao().save(
            LibraryBatch(
                sources = listOf(SourceEntity("provider-source", SourceKind.PROVIDER, providerKey = "provider",
                    providerItemId = "item", availability = Availability.PLAYABLE, updatedAt = 2)),
                variants = listOf(VariantEntity(entry.variantId.value, "track", "provider-source", durationMs = 60_000)),
            ),
        )
        currentRoute = route.copy(target = PlaybackTarget("external", TargetKind.EXTERNAL_APP))
    }

    private suspend fun seed(durationMs: Long? = 60_000) {
        db.libraryDao().save(
            LibraryBatch(
                sources = listOf(
                    SourceEntity("source", SourceKind.LOCAL, localUri = "content://audio/item",
                        availability = Availability.PLAYABLE, updatedAt = 1),
                ),
                tracks = listOf(TrackEntity("track")),
                variants = listOf(VariantEntity(entry.variantId.value, "track", "source", durationMs = durationMs)),
            ),
        )
        queues.create(queueId, null, 1)
        assertEquals(QueueEditResult.Updated(1), queues.replaceEntries(queueId, 0, listOf(entry), 2))
    }

    private fun frame(
        position: Long?,
        phase: PlaybackPhase = PlaybackPhase.PLAYING,
        plan: ExecutionPlan? = null,
    ) = PlaybackCheckpointSample(
        queueId, 1, PlaybackSnapshot(1, phase, entry.entryId, positionMs = position,
            effectiveSource = plan?.source, executionPlan = plan),
    )

    private suspend fun CoroutineScope.withJournal(test: suspend JournalFixture.() -> Unit) {
        val job = Job()
        try {
            withTimeout(5_000) { JournalFixture(CoroutineScope(coroutineContext + job)).test() }
        } finally {
            job.cancelAndJoin()
        }
    }

    private inner class JournalFixture(scope: CoroutineScope) {
        var now = 1_000L
        var sample: PlaybackCheckpointSample? = null
        val intervals = Channel<Long>()
        val ticks = Channel<Unit>()
        val journal = PlaybackCheckpointJournal(scope, queues, { sample }, { now }) { interval ->
            intervals.send(interval)
            ticks.receive()
        }

        suspend fun awaitWrite(at: Long) =
            journal.lastWrite.filterNotNull().first { it.checkpoint.savedAt == at }
    }
}
