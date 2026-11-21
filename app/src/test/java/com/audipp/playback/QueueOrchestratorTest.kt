package com.audipp.playback

import androidx.room.Room
import com.audipp.data.db.LibraryBatch
import com.audipp.data.db.LibraryDatabase
import com.audipp.data.db.PlaybackCheckpointEntity
import com.audipp.data.db.QueueEditResult
import com.audipp.data.db.QueueRepeatMode
import com.audipp.data.db.SourceEntity
import com.audipp.data.db.TrackEntity
import com.audipp.data.db.VariantEntity
import com.audipp.domain.model.Availability
import com.audipp.domain.model.PlaybackCapabilities
import com.audipp.domain.model.QueueEntry
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.VariantId
import com.audipp.playback.source.AdapterKey
import com.audipp.playback.source.AdapterResolution
import com.audipp.playback.source.PlaybackTarget
import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.ResolutionFailure
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceAdapter
import com.audipp.playback.source.SourceDescriptor
import com.audipp.playback.source.SourceResolver
import com.audipp.playback.source.TargetKind
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
class QueueOrchestratorTest {
    private val queueId = "queue"
    private val a = QueueEntry(QueueEntryId("entry-a"), VariantId("a"))
    private val b = QueueEntry(QueueEntryId("entry-b"), VariantId("b"))
    private val c = QueueEntry(QueueEntryId("entry-c"), VariantId("c"))
    private val order = listOf(a, b, c)
    private val context = ResolutionContext(
        PlaybackTarget("device", TargetKind.DEVICE), routeRevision = 0, TransitionId("advance"),
    )
    private lateinit var db: LibraryDatabase
    private val queues get() = db.queueDao()
    private var currentContext = context
    private var onQuery: (String) -> Unit = {}

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), LibraryDatabase::class.java,
        ).setQueryCallback({ sql, _ -> onQuery(sql) }, Executor { it.run() }).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun repeatAllVisitsMissingAndAuthRequiredEntriesOnlyOnce() = runBlocking {
        seedQueue(listOf(Availability.MISSING, Availability.AUTH_REQUIRED, Availability.MISSING))
        queues.setPlaybackOrder(queueId, 1, false, QueueRepeatMode.ALL, 3)

        val result = withTimeout(5_000) { advance(orchestrator(), a.entryId) }

        assertEquals(
            QueueAdvanceResult.NoPlayableItem(
                listOf(
                    UnavailableQueueEntry(b.entryId, ResolutionFailure.AUTH_REQUIRED),
                    UnavailableQueueEntry(c.entryId, ResolutionFailure.SOURCE_MISSING),
                    UnavailableQueueEntry(a.entryId, ResolutionFailure.SOURCE_MISSING),
                ),
            ),
            result,
        )
    }

    @Test
    fun repeatOneDistinguishesNaturalEndAndExplicitNext() = runBlocking {
        seedQueue()
        queues.setPlaybackOrder(queueId, 1, false, QueueRepeatMode.ONE, 3)
        val orchestrator = orchestrator()

        assertEquals(a, (advance(orchestrator, a.entryId) as QueueAdvanceResult.Ready).entry)
        val skipped = advance(orchestrator, a.entryId, trigger = QueueAdvanceTrigger.SKIP_NEXT)
        assertEquals(b, (skipped as QueueAdvanceResult.Ready).entry)
        assertEquals(
            QueueAdvanceResult.QueueEnded,
            advance(orchestrator, c.entryId, trigger = QueueAdvanceTrigger.SKIP_NEXT),
        )
    }

    @Test
    fun askStopsAtFirstUnavailableEntryAndSkipCanSelectTheNextOne() = runBlocking {
        seedQueue(listOf(Availability.PLAYABLE, Availability.MISSING, Availability.PLAYABLE))
        val orchestrator = orchestrator()
        val failure = UnavailableQueueEntry(b.entryId, ResolutionFailure.SOURCE_MISSING)

        assertEquals(
            QueueAdvanceResult.RequiresUserAction(failure),
            advance(orchestrator, a.entryId, policy = UnplayableItemPolicy.ASK),
        )
        val next = advance(orchestrator, a.entryId) as QueueAdvanceResult.Ready
        assertEquals(c, next.entry)
        assertEquals(listOf(failure), next.skipped)
    }

    @Test
    fun queueEditDuringResolutionDiscardsTheResolvedCandidate() = runBlocking {
        seedQueue()
        val adapter = SourceAdapter { request ->
            assertEquals(QueueEditResult.Updated(2), queues.replaceEntries(queueId, 1, listOf(a, c, b), 3))
            available(request)
        }

        assertEquals(QueueAdvanceResult.QueueChanged, advance(orchestrator(adapter = adapter), a.entryId))
    }

    @Test
    fun supersededTransitionDiscardsTheResolvedCandidate() = runBlocking {
        seedQueue()
        val adapter = SourceAdapter { request ->
            currentContext = context.copy(transitionId = TransitionId("stop"))
            available(request)
        }

        assertEquals(QueueAdvanceResult.Cancelled, advance(orchestrator(adapter = adapter), a.entryId))
    }

    @Test
    fun supersededTransitionDuringPostResolutionRevisionCheckDiscardsAskResult() = runBlocking {
        seedQueue(listOf(Availability.PLAYABLE, Availability.AUTH_REQUIRED, Availability.PLAYABLE))
        var revisionQueries = 0
        onQuery = { sql ->
            if (sql == "SELECT revision FROM queues WHERE queue_id = ?" && ++revisionQueries == 2) {
                currentContext = context.copy(transitionId = TransitionId("select-another-entry"))
            }
        }

        val result = advance(orchestrator(), a.entryId, policy = UnplayableItemPolicy.ASK)

        assertEquals(2, revisionQueries)
        assertEquals(QueueAdvanceResult.Cancelled, result)
    }

    @Test
    fun timeoutEndsTheAdvanceEvenWhenSkipIsAllowed() = runBlocking {
        seedQueue()
        val orchestrator = orchestrator(timeoutMs = 100, adapter = SourceAdapter { awaitCancellation() })

        assertEquals(QueueAdvanceResult.TimedOut(b.entryId), advance(orchestrator, a.entryId))
    }

    @Test
    fun cancellingAdvanceStopsPendingResolution() = runBlocking {
        seedQueue()
        val started = CompletableDeferred<Unit>()
        val orchestrator = orchestrator(adapter = SourceAdapter {
            started.complete(Unit)
            awaitCancellation()
        })
        val pending = async { advance(orchestrator, a.entryId) }

        withTimeout(5_000) { started.await() }
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
    }

    @Test
    fun playbackOrderSettingsPreserveSeedAndCheckpointAndFenceOldRevisions() = runBlocking {
        seedQueue()
        val checkpoint = PlaybackCheckpointEntity(queueId, 1, a.entryId.value, 30_000, 3)
        assertTrue(queues.saveCheckpoint(checkpoint))

        assertEquals(QueueEditResult.Updated(2), queues.setPlaybackOrder(queueId, 1, true, QueueRepeatMode.OFF, 4))
        val shuffled = requireNotNull(queues.read(queueId))
        val effective = shuffled.effectivePlaybackOrder()
        assertNotNull(shuffled.queue.shuffleSeed)
        assertEquals(order.toSet(), effective.toSet())
        assertEquals(checkpoint.copy(queueRevision = 2), queues.checkpoint())
        assertEquals(QueueEditResult.StaleRevision, queues.setPlaybackOrder(queueId, 1, false, QueueRepeatMode.OFF, 5))
        assertEquals(QueueEditResult.Updated(2), queues.setPlaybackOrder(queueId, 2, true, QueueRepeatMode.OFF, 5))
        assertEquals(effective.first(), (advance(orchestrator(), null) as QueueAdvanceResult.Ready).entry)

        assertEquals(QueueEditResult.Updated(3), queues.replaceEntries(queueId, 2, order.reversed(), 6))
        assertEquals(effective, queues.read(queueId)?.effectivePlaybackOrder())
        assertEquals(QueueEditResult.Updated(4), queues.setPlaybackOrder(queueId, 3, false, QueueRepeatMode.OFF, 7))
        assertEquals(order.reversed(), queues.read(queueId)?.effectivePlaybackOrder())
        assertEquals(QueueEditResult.Updated(5), queues.setPlaybackOrder(queueId, 4, true, QueueRepeatMode.OFF, 8))
        assertEquals(shuffled.queue.shuffleSeed, queues.read(queueId)?.queue?.shuffleSeed)
        assertEquals(effective, queues.read(queueId)?.effectivePlaybackOrder())
        assertEquals(checkpoint.copy(queueRevision = 5), queues.checkpoint())
    }

    private suspend fun seedQueue(availability: List<Availability> = List(3) { Availability.PLAYABLE }) {
        db.libraryDao().save(
            LibraryBatch(
                sources = order.zip(availability).map { (entry, state) ->
                    val id = entry.variantId.value
                    SourceEntity("source-$id", SourceKind.LOCAL, localUri = "content://audio/$id",
                        availability = state, updatedAt = 1)
                },
                tracks = listOf(TrackEntity("track")),
                variants = order.map { VariantEntity(it.variantId.value, "track", "source-${it.variantId.value}") },
            ),
        )
        queues.create(queueId, null, 1)
        assertEquals(QueueEditResult.Updated(1), queues.replaceEntries(queueId, 0, order, 2))
    }

    private fun orchestrator(
        timeoutMs: Long = 10_000,
        adapter: SourceAdapter = SourceAdapter { available(it) },
    ): QueueOrchestrator {
        val resolver = SourceResolver(
            mapOf(AdapterKey(SourceKind.LOCAL, TargetKind.DEVICE) to adapter),
            { currentContext },
            timeoutMs = timeoutMs,
        )
        return QueueOrchestrator(queues, db.libraryDao(), resolver)
    }

    private fun available(request: ResolutionRequest): AdapterResolution.Available {
        val variant = request.variant
        return AdapterResolution.Available(
            variant.variantId, variant.source.sourceId,
            SourceDescriptor.LocalDocument(checkNotNull(variant.source.localDocumentUri)),
            PlaybackCapabilities(canPlay = true),
        )
    }

    private suspend fun advance(
        orchestrator: QueueOrchestrator,
        current: QueueEntryId?,
        trigger: QueueAdvanceTrigger = QueueAdvanceTrigger.NATURAL_END,
        policy: UnplayableItemPolicy = UnplayableItemPolicy.SKIP,
    ) = orchestrator.advance(queueId, current, trigger, policy, context)
}
