package com.audipp.playback

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.PowerManager
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
import com.audipp.domain.model.ErrorDomain
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.PlaybackQueueRef
import com.audipp.domain.model.PlaybackSnapshot
import com.audipp.domain.model.QueueEntry
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.VariantId
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real PlaybackService, its MediaSession, the app's Room database and a content provider in
 * another process. An emulator pass does not show that any audio reached an output.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class QueuePlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database = context.libraryDatabase
    private val queues = database.queueDao()
    private val run = UUID.randomUUID().toString().take(8)
    private val queueId = "device-queue-$run"
    private lateinit var connection: ListenableFuture<MediaController>
    private lateinit var controller: MediaController

    @Before
    fun connect() {
        controller = connectController()
    }

    @After
    fun cleanUp() {
        runBlocking(Dispatchers.Main) {
            if (controller.isConnected) controller.stop()
            MediaController.releaseFuture(connection)
        }
        shell("input keyevent KEYCODE_WAKEUP")
        // In one transaction, so the stop's own checkpoint write cannot land between the deletes.
        database.runInTransaction {
            val sql = database.openHelper.writableDatabase
            sql.execSQL("DELETE FROM playback_checkpoint WHERE queue_id = ?", arrayOf(queueId))
            sql.execSQL("DELETE FROM queue_items WHERE queue_id = ?", arrayOf(queueId))
            sql.execSQL("DELETE FROM queues WHERE queue_id = ?", arrayOf(queueId))
        }
    }

    @Test
    fun theNextEntryStartsWithTheScreenOffAndTheSessionShowsTheSameItem() {
        val a = entry("a")
        val b = entry("b")
        seed(a to 1_500, b to 4_000)
        assertEquals(SessionResult.RESULT_SUCCESS, play(a).resultCode)
        awaitSnapshot { it.phase == PlaybackPhase.PLAYING && it.currentEntryId == a.entryId }

        shell("input keyevent KEYCODE_SLEEP")
        val power = context.getSystemService(PowerManager::class.java)
        awaitCondition { !power.isInteractive }
        val next = awaitSnapshot { it.phase == PlaybackPhase.PLAYING && it.currentEntryId == b.entryId }
        assertFalse(power.isInteractive)
        assertEquals(PlaybackQueueRef(queueId, 1), next.queue)
        assertSessionAgrees()
        val earlier = awaitSnapshot { (it.positionMs ?: 0) > 0 }.positionMs!!
        val later = awaitSnapshot { (it.positionMs ?: 0) > earlier + 500 }
        assertEquals(b.entryId, later.currentEntryId)
        assertFalse(power.isInteractive)

        awaitSnapshot(15_000) { it.phase == PlaybackPhase.IDLE }
        assertNull(snapshot().currentEntryId)
    }

    @Test
    fun notificationPauseAndPlayActionsDriveTheSameItemTheSnapshotShows() {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
                "android.permission.POST_NOTIFICATIONS")
        }
        val a = entry("a")
        seed(a to 20_000)
        assertEquals(SessionResult.RESULT_SUCCESS, play(a).resultCode)
        awaitSnapshot { it.phase == PlaybackPhase.PLAYING }

        notificationAction(androidx.media3.session.R.string.media3_controls_pause_description).send()
        val paused = awaitSnapshot { it.phase == PlaybackPhase.PAUSED }
        assertEquals(a.entryId, paused.currentEntryId)
        assertFalse(onMain { controller.playWhenReady })
        assertSessionAgrees()

        notificationAction(androidx.media3.session.R.string.media3_controls_play_description).send()
        val resumed = awaitSnapshot { it.phase == PlaybackPhase.PLAYING }
        assertEquals(paused.executionPlan?.transitionId, resumed.executionPlan?.transitionId)
        assertSessionAgrees()
    }

    @Test
    fun removingTheCurrentEntryRequiresStopAndThenNothingAdvances() {
        val a = entry("a")
        val b = entry("b")
        seed(a to 10_000, b to 2_000)
        assertEquals(SessionResult.RESULT_SUCCESS, play(a).resultCode)
        awaitSnapshot { it.phase == PlaybackPhase.PLAYING }
        awaitCondition { checkpoint()?.entryId == a.entryId.value }

        assertEquals(QueueEditResult.StopRequired(a.entryId), edit(1, listOf(b)))
        onMain { controller.stop() }
        awaitSnapshot { it.phase == PlaybackPhase.IDLE }
        assertEquals(QueueEditResult.Updated(2), edit(1, listOf(b), stopped = a.entryId))

        SystemClock.sleep(3_000)
        val after = snapshot()
        assertEquals(PlaybackPhase.IDLE, after.phase)
        assertNull(after.currentEntryId)
        assertNull(checkpoint()?.entryId)
        assertNull(onMain { controller.currentMediaItem })
    }

    @Test
    fun aFailedCheckpointWriteKeepsTheLastCommitAndPlaybackGoesOn() {
        val a = entry("a")
        seed(a to 20_000)
        assertEquals(SessionResult.RESULT_SUCCESS, play(a).resultCode)
        awaitSnapshot { it.phase == PlaybackPhase.PLAYING && (it.positionMs ?: 0) >= 500 }
        onMain { controller.pause() }
        awaitPauseCheckpoint()
        val committed = checkpoint()

        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TEMP TRIGGER fail_insert BEFORE INSERT ON playback_checkpoint " +
            "BEGIN SELECT RAISE(ABORT, 'injected'); END")
        sql.execSQL("CREATE TEMP TRIGGER fail_update BEFORE UPDATE ON playback_checkpoint " +
            "BEGIN SELECT RAISE(ABORT, 'injected'); END")
        try {
            onMain { controller.play() }
            awaitSnapshot { it.phase == PlaybackPhase.PLAYING && (it.positionMs ?: 0) > committed!!.positionMs!! + 500 }
            onMain { controller.pause() }
            awaitSnapshot { it.phase == PlaybackPhase.PAUSED }
            // Only a write made after the triggers were installed can report this.
            awaitCondition { "checkpointWrite=STORAGE_ERROR" in
                shell("dumpsys activity service ${context.packageName}/${PlaybackService::class.java.name}") }
            assertEquals(committed, checkpoint())
            onMain { controller.play() }
            awaitSnapshot { it.phase == PlaybackPhase.PLAYING }
        } finally {
            sql.execSQL("DROP TRIGGER fail_insert")
            sql.execSQL("DROP TRIGGER fail_update")
        }
    }

    @Test
    fun aRevokedGrantAtTheNextEntryStopsThereWithoutSkippingAhead() {
        val a = entry("a")
        val b = entry("b")
        val c = entry("c")
        seed(a to 1_000, b to null, c to 2_000)
        assertEquals(SessionResult.RESULT_SUCCESS, play(a).resultCode)

        val failed = awaitSnapshot { it.phase == PlaybackPhase.ERROR }
        assertEquals(b.entryId, failed.currentEntryId)
        assertEquals(ErrorDomain.PERMISSION_DENIED, failed.error?.domain)
        SystemClock.sleep(1_000)
        assertEquals(PlaybackPhase.ERROR, snapshot().phase)
        assertNull(onMain { controller.currentMediaItem })
    }

    @Test
    fun rapidQueueCommandsLeaveOnlyTheLastRequestPlaying() {
        val a = entry("a")
        val b = entry("b")
        val c = entry("c")
        seed(a to 10_000, b to 10_000, c to 10_000)
        val results = onMain {
            listOf(a, b, c).map { PlaybackConnection.playQueue(controller, queueId, it.entryId, 1) }
        }.map { it.get(15, TimeUnit.SECONDS).resultCode }

        assertEquals(SessionResult.RESULT_SUCCESS, results.last())
        val playing = awaitSnapshot { it.phase == PlaybackPhase.PLAYING }
        assertEquals(c.entryId, playing.currentEntryId)
        assertSessionAgrees()
    }

    @Test
    fun seekingToTheEndWhilePausedStartsNothing() {
        val a = entry("a")
        val b = entry("b")
        seed(a to 3_000, b to 3_000)
        assertEquals(SessionResult.RESULT_SUCCESS, play(a).resultCode)
        awaitSnapshot { it.phase == PlaybackPhase.PLAYING && (it.positionMs ?: 0) >= 500 }
        onMain { controller.pause() }
        val paused = awaitSnapshot { it.phase == PlaybackPhase.PAUSED && it.durationMs != null }

        onMain { controller.seekTo(paused.durationMs!!) }
        SystemClock.sleep(4_000)

        val after = snapshot()
        assertEquals("$after", PlaybackPhase.PAUSED, after.phase)
        assertEquals("$after", a.entryId, after.currentEntryId)
        assertFalse(onMain { controller.playWhenReady })
    }

    private fun entry(name: String) = QueueEntry(QueueEntryId("$run-$name"), VariantId("$run-$name"))

    /** A null duration serves the entry from a path whose provider throws SecurityException. */
    private fun seed(vararg items: Pair<QueueEntry, Int?>) = runBlocking {
        database.libraryDao().save(LibraryBatch(
            sources = items.map { (entry, ms) ->
                val id = entry.variantId.value
                val path = if (ms == null) "denied/$id" else "tone/$id/$ms"
                SourceEntity("source-$id", SourceKind.LOCAL, localUri = "content://com.audipp.test.fixture/$path",
                    availability = Availability.PLAYABLE, updatedAt = 1)
            },
            tracks = listOf(TrackEntity("track-$run")),
            variants = items.map { (entry, ms) ->
                VariantEntity(entry.variantId.value, "track-$run", "source-${entry.variantId.value}",
                    durationMs = ms?.toLong())
            },
        ))
        queues.create(queueId, null, 1)
        assertEquals(QueueEditResult.Updated(1), queues.replaceEntries(queueId, 0, items.map { it.first }, 2))
    }

    private fun play(entry: QueueEntry): SessionResult =
        onMain { PlaybackConnection.playQueue(controller, queueId, entry.entryId, 1) }.get(15, TimeUnit.SECONDS)

    private fun edit(revision: Long, entries: List<QueueEntry>, stopped: QueueEntryId? = null) =
        runBlocking { queues.replaceEntries(queueId, revision, entries, 3, stopped) }

    private fun checkpoint() = runBlocking { queues.checkpoint() }?.takeIf { it.queueId == queueId }

    private fun snapshot(): PlaybackSnapshot = onMain { PlaybackConnection.snapshot(controller.sessionExtras)!! }

    // The notification and the mini player are built from this same session player.
    private fun assertSessionAgrees() = onMain {
        val snapshot = PlaybackConnection.snapshot(controller.sessionExtras)!!
        assertEquals(snapshot.currentEntryId?.value, controller.currentMediaItem?.mediaId)
        assertEquals(snapshot.phase == PlaybackPhase.PLAYING, controller.isPlaying)
        val position = controller.currentPosition
        assertTrue("$position vs ${snapshot.positionMs}", kotlin.math.abs(position - (snapshot.positionMs ?: 0)) < 2_000)
    }

    private fun notificationAction(label: Int): android.app.PendingIntent {
        val title = context.getString(label)
        val manager = context.getSystemService(NotificationManager::class.java)
        var action: Notification.Action? = null
        awaitCondition {
            action = manager.activeNotifications.filter { it.packageName == context.packageName }
                .flatMap { it.notification.actions.orEmpty().toList() }.firstOrNull { it.title == title }
            action != null
        }
        return action!!.actionIntent
    }

    /** The item's start is saved at position 0 first; wait for the write made at the pause. */
    private fun awaitPauseCheckpoint(): Long {
        val paused = awaitSnapshot { it.phase == PlaybackPhase.PAUSED && (it.positionMs ?: 0) > 0 }.positionMs!!
        awaitCondition { checkpoint()?.positionMs?.let { kotlin.math.abs(it - paused) <= 300 && it > 0 } == true }
        return checkpoint()!!.positionMs!!
    }

    private fun connectController(): MediaController {
        connection = onMain { PlaybackConnection.connect(context, object : MediaController.Listener {}) }
        return connection.get(15, TimeUnit.SECONDS)
    }

    private fun awaitSnapshot(timeoutMs: Long = 15_000, condition: (PlaybackSnapshot) -> Boolean): PlaybackSnapshot {
        var last: PlaybackSnapshot? = null
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            last = snapshot()
            if (condition(last)) return last
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

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { String(it.readBytes()) }
}
