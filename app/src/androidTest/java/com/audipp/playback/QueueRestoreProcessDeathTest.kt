package com.audipp.playback

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.data.db.LibraryBatch
import com.audipp.data.db.QueueEditResult
import com.audipp.data.db.SourceEntity
import com.audipp.data.db.TrackEntity
import com.audipp.data.db.VariantEntity
import com.audipp.data.db.libraryDatabase
import com.audipp.domain.model.Availability
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.PlaybackQueueRef
import com.audipp.domain.model.PlaybackSnapshot
import com.audipp.domain.model.QueueEntry
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.VariantId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two instrumentation runs: `-e restorePhase 1` saves a paused point and ends, after which Android
 * force-stops the app without any end callback; `-e restorePhase 2` checks the new process.
 * Both are skipped in an ordinary run.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class QueueRestoreProcessDeathTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = context.libraryDatabase
    private val queues = database.queueDao()
    private val phase = InstrumentationRegistry.getArguments().getString("restorePhase")

    @Test
    fun phase1SavesAPausedPointAndLeavesTheProcessRunning() {
        assumeTrue(phase == "1")
        val run = UUID.randomUUID().toString().take(8)
        val queueId = "$QUEUE_PREFIX$run"
        val entry = QueueEntry(QueueEntryId("$run-a"), VariantId("$run-a"))
        runBlocking {
            database.libraryDao().save(LibraryBatch(
                sources = listOf(SourceEntity("source-$run-a", SourceKind.LOCAL,
                    localUri = "content://com.audipp.test.fixture/tone/$run-a/20000",
                    availability = Availability.PLAYABLE, updatedAt = 1)),
                tracks = listOf(TrackEntity("track-$run")),
                variants = listOf(VariantEntity("$run-a", "track-$run", "source-$run-a", durationMs = 20_000)),
            ))
            queues.create(queueId, null, 1)
            assertEquals(QueueEditResult.Updated(1), queues.replaceEntries(queueId, 0, listOf(entry), 2))
        }
        val controller = connect()
        val result = onMain { PlaybackConnection.playQueue(controller, queueId, entry.entryId, 1) }
            .get(15, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        awaitSnapshot(controller) { it.phase == PlaybackPhase.PLAYING && (it.positionMs ?: 0) >= 1_000 }
        onMain { controller.pause() }
        val paused = awaitSnapshot(controller) { it.phase == PlaybackPhase.PAUSED && (it.positionMs ?: 0) > 0 }
        awaitCondition {
            checkpoint()?.let { it.queueId == queueId && kotlin.math.abs(it.positionMs!! - paused.positionMs!!) <= 300 } == true
        }
        // No stop, release or cleanup: the run ends with the service paused in this process.
    }

    @Test
    fun phase2RestoresThatPointInTheNewProcessWithoutPlayingAndPlayResumesThere() {
        assumeTrue(phase == "2")
        val saved = checkpoint()!!
        assertTrue(saved.queueId.startsWith(QUEUE_PREFIX))
        val controller = connect()
        try {
            val restored = awaitSnapshot(controller) { it.currentEntryId?.value == saved.entryId }
            assertEquals(PlaybackPhase.IDLE, restored.phase)
            assertEquals(saved.positionMs, restored.positionMs)
            assertEquals(PlaybackQueueRef(saved.queueId, saved.queueRevision), restored.queue)
            SystemClock.sleep(1_000)
            assertFalse(onMain { controller.isPlaying })
            assertNull(onMain { controller.currentMediaItem })

            onMain { controller.play() }
            val resumed = awaitSnapshot(controller) { it.phase == PlaybackPhase.PLAYING }
            assertEquals(saved.entryId, resumed.currentEntryId?.value)
            assertTrue("${resumed.positionMs} < ${saved.positionMs}", (resumed.positionMs ?: 0) >= saved.positionMs!!)
            onMain { assertEquals(saved.entryId, controller.currentMediaItem?.mediaId) }
        } finally {
            onMain { controller.stop() }
            val sql = database.openHelper.writableDatabase
            sql.execSQL("DELETE FROM playback_checkpoint WHERE queue_id = ?", arrayOf(saved.queueId))
            sql.execSQL("DELETE FROM queue_items WHERE queue_id = ?", arrayOf(saved.queueId))
            sql.execSQL("DELETE FROM queues WHERE queue_id = ?", arrayOf(saved.queueId))
        }
    }

    private fun checkpoint() = runBlocking { queues.checkpoint() }

    private fun connect(): MediaController =
        onMain { PlaybackConnection.connect(context, object : MediaController.Listener {}) }.get(15, TimeUnit.SECONDS)

    private fun awaitSnapshot(controller: MediaController, condition: (PlaybackSnapshot) -> Boolean): PlaybackSnapshot {
        var last: PlaybackSnapshot? = null
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            last = onMain { PlaybackConnection.snapshot(controller.sessionExtras) }
            if (last != null && condition(last)) return last
            SystemClock.sleep(100)
        }
        throw AssertionError("snapshot condition timed out: $last")
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue("condition timed out", condition())
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private companion object {
        const val QUEUE_PREFIX = "restore-queue-"
    }
}
