package com.audipp.playback

import android.content.pm.ProviderInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.audipp.data.db.LibraryBatch
import com.audipp.data.db.LibraryDatabase
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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers

/** The service with its Room queue, a content provider and the Media3 session, connected for real. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], shadows = [PlaybackServiceTest.ClockedAudioTrack::class])
class PlaybackQueueServiceTest {
    private val queueId = "queue"
    private val a = QueueEntry(QueueEntryId("entry-a"), VariantId("a"))
    private val b = QueueEntry(QueueEntryId("entry-b"), VariantId("b"))
    private val c = QueueEntry(QueueEntryId("entry-c"), VariantId("c"))
    private lateinit var lifecycle: ServiceController<PlaybackService>
    private lateinit var service: PlaybackService
    private lateinit var controller: MediaController
    private val controllers = mutableListOf<MediaController>()
    private val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val denied = mutableSetOf<String>()
    private var beforeOpen: (String) -> Unit = {}
    private val database: LibraryDatabase get() = service.libraryDatabase
    private val queues get() = database.queueDao()

    @Before
    fun createService() {
        // An unobserved route pauses playback for safety, so the AudioTrack reports a built-in speaker.
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        val port = ReflectionHelpers.getField<Any>(speaker, "mPort")
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(port, "mHandle"), "mId", 1)
        ReflectionHelpers.setField(port, "mRole", 2) // AudioPort.ROLE_SINK
        ReflectionHelpers.setField(port, "mName", "Fixture")
        ReflectionHelpers.setField(port, "mSamplingRates", intArrayOf(44_100))
        ReflectionHelpers.setField(port, "mChannelMasks", intArrayOf(AudioFormat.CHANNEL_OUT_MONO))
        ReflectionHelpers.setField(port, "mChannelIndexMasks", intArrayOf())
        ReflectionHelpers.setField(port, "mFormats", intArrayOf(AudioFormat.ENCODING_PCM_16BIT))
        ShadowAudioTrack.setRoutedDevice(speaker)
        PlaybackServiceTest.ClockedAudioTrack.routedOutput = speaker
        startService()
    }

    @After
    fun destroyService() {
        controllers.forEach { it.release() }
        lifecycle.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun naturalEndPlaysTheNextEntryInQueueOrderAndStopsAtTheQueueEnd() {
        seedQueue(mapOf(a to 300, b to 1_000))

        assertEquals(SessionResult.RESULT_SUCCESS, await(play(a, 1)).resultCode)
        advanceUntil { snapshot().currentEntryId == b.entryId && snapshot().phase == PlaybackPhase.PLAYING }
        assertEquals(PlaybackQueueRef(queueId, 1), snapshot().queue)
        assertSessionAgreesWithSnapshot()

        advanceUntil { snapshot().phase == PlaybackPhase.IDLE }
        assertNull(snapshot().currentEntryId)
        assertEquals(0, rawPlayer.mediaItemCount)
        assertEquals(listOf("a", "b"), opened.distinct())
    }

    @Test
    fun staleRevisionOrUnknownEntryIsRejectedWithoutTouchingPlayback() {
        seedQueue(mapOf(a to 1_000, b to 1_000))
        assertEquals(SessionResult.RESULT_SUCCESS, await(play(a, 1)).resultCode)
        advanceUntil { snapshot().phase == PlaybackPhase.PLAYING }
        val playing = snapshot()

        val stale = await(play(b, 0))
        val unknown = await(PlaybackConnection.playQueue(controller, queueId, QueueEntryId("gone"), 1))

        assertEquals(SessionError.ERROR_INVALID_STATE, stale.resultCode)
        assertEquals("STALE_QUEUE_REVISION", PlaybackConnection.error(stale)?.code)
        assertEquals(SessionError.ERROR_INVALID_STATE, unknown.resultCode)
        assertEquals(playing.executionPlan?.transitionId, snapshot().executionPlan?.transitionId)
        assertEquals(a.entryId, snapshot().currentEntryId)
        assertEquals(listOf("a"), opened.distinct())
    }

    @Test
    fun editWhilePlayingMovesTheSnapshotRevisionAndTheNextEntryFollowsTheNewOrder() {
        seedQueue(mapOf(a to 1_000, b to 1_000, c to 1_000))
        assertEquals(SessionResult.RESULT_SUCCESS, await(play(a, 1)).resultCode)
        advanceUntil { snapshot().phase == PlaybackPhase.PLAYING }

        assertEquals(QueueEditResult.Updated(2), edit(1, listOf(a, c, b)))
        advanceUntil { snapshot().queue == PlaybackQueueRef(queueId, 2) }
        assertEquals(PlaybackPhase.PLAYING, snapshot().phase)

        advanceUntil { snapshot().currentEntryId == c.entryId && snapshot().phase == PlaybackPhase.PLAYING }
        assertEquals(listOf("a", "c"), opened.distinct())
    }

    @Test
    fun revisionChangeDuringTheNextSearchDiscardsTheCandidateInsteadOfStartingIt() {
        seedQueue(mapOf(a to 300, b to 1_000, c to 1_000))
        beforeOpen = { name ->
            if (name == "b") {
                beforeOpen = {}
                runBlocking { assertEquals(QueueEditResult.Updated(2), queues.replaceEntries(queueId, 1, listOf(a, c, b), 3)) }
            }
        }
        assertEquals(SessionResult.RESULT_SUCCESS, await(play(a, 1)).resultCode)

        advanceUntil { "b" in opened && snapshot().phase == PlaybackPhase.IDLE }
        idle(500)
        assertEquals(PlaybackPhase.IDLE, snapshot().phase)
        assertNull(snapshot().currentEntryId)
        assertEquals(0, rawPlayer.mediaItemCount)
        assertFalse("c" in opened)
    }

    @Test
    fun removingTheCurrentEntryRequiresStopAndThenDoesNotAdvance() {
        seedQueue(mapOf(a to 1_000, b to 1_000))
        assertEquals(SessionResult.RESULT_SUCCESS, await(play(a, 1)).resultCode)
        advanceUntil { snapshot().phase == PlaybackPhase.PLAYING && checkpoint()?.entryId == a.entryId.value }

        assertEquals(QueueEditResult.StopRequired(a.entryId), edit(1, listOf(b)))
        controller.stop()
        advanceUntil { snapshot().phase == PlaybackPhase.IDLE }
        assertEquals(QueueEditResult.Updated(2), edit(1, listOf(b), stopped = a.entryId))

        idle(1_500)
        assertEquals(PlaybackPhase.IDLE, snapshot().phase)
        assertNull(snapshot().currentEntryId)
        assertNull(checkpoint()?.entryId)
        assertFalse("b" in opened)
    }

    @Test
    fun pausedCheckpointIsRestoredByTheNextServiceWithoutPlayingAndPlayResumesThere() {
        seedQueue(mapOf(a to 3_000, b to 1_000))
        assertEquals(SessionResult.RESULT_SUCCESS, await(play(a, 1)).resultCode)
        advanceUntil { snapshot().phase == PlaybackPhase.PLAYING && (snapshot().positionMs ?: 0) >= 500 }
        controller.pause()
        advanceUntil { snapshot().phase == PlaybackPhase.PAUSED && snapshot().positionMs != null }
        val paused = snapshot().positionMs!!
        advanceUntil { checkpoint()?.positionMs?.let { kotlin.math.abs(it - paused) <= 100 } == true }
        // The pause is saved from the first paused observation; the engine may refine the position later.
        val committed = checkpoint()!!.positionMs!!
        assertTrue("$committed vs $paused", kotlin.math.abs(committed - paused) <= 100)

        restartService()
        advanceUntil { snapshot().currentEntryId == a.entryId }
        val restored = snapshot()
        assertEquals(PlaybackPhase.IDLE, restored.phase)
        assertEquals(committed, restored.positionMs)
        assertEquals(PlaybackQueueRef(queueId, 1), restored.queue)
        idle(500)
        assertEquals(0, rawPlayer.mediaItemCount)
        assertFalse(rawPlayer.playWhenReady)

        controller.play()
        advanceUntil { snapshot().phase == PlaybackPhase.PLAYING && snapshot().currentEntryId == a.entryId }
        assertTrue((snapshot().positionMs ?: 0) >= committed)
        assertSessionAgreesWithSnapshot()
    }

    @Test
    fun storageFailureKeepsTheLastCommitWhilePlaybackContinues() {
        seedQueue(mapOf(a to 3_000, b to 1_000))
        assertEquals(SessionResult.RESULT_SUCCESS, await(play(a, 1)).resultCode)
        advanceUntil { snapshot().phase == PlaybackPhase.PLAYING && (snapshot().positionMs ?: 0) >= 300 }
        controller.pause()
        advanceUntil { snapshot().phase == PlaybackPhase.PAUSED && snapshot().positionMs != null }
        val paused = snapshot().positionMs!!
        advanceUntil { checkpoint()?.positionMs?.let { kotlin.math.abs(it - paused) <= 100 } == true }
        val committed = checkpoint()!!.positionMs

        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TEMP TRIGGER fail_insert BEFORE INSERT ON playback_checkpoint BEGIN SELECT RAISE(ABORT, 'injected'); END")
        sql.execSQL("CREATE TEMP TRIGGER fail_update BEFORE UPDATE ON playback_checkpoint BEGIN SELECT RAISE(ABORT, 'injected'); END")
        try {
            controller.play()
            advanceUntil { snapshot().phase == PlaybackPhase.PLAYING && (snapshot().positionMs ?: 0) > committed!! }
            controller.pause()
            advanceUntil { journal.lastWrite.value?.outcome == CheckpointWriteOutcome.STORAGE_ERROR }
            assertEquals(committed, checkpoint()?.positionMs)
            assertEquals(PlaybackPhase.PAUSED, snapshot().phase)
            controller.play()
            advanceUntil { snapshot().phase == PlaybackPhase.PLAYING }
        } finally {
            sql.execSQL("DROP TRIGGER fail_insert")
            sql.execSQL("DROP TRIGGER fail_update")
        }
    }

    @Test
    fun permissionLossAtTheNextEntryStopsThereWithoutSkippingAhead() {
        seedQueue(mapOf(a to 300, b to 1_000, c to 1_000))
        denied += "b"
        assertEquals(SessionResult.RESULT_SUCCESS, await(play(a, 1)).resultCode)

        advanceUntil { snapshot().phase == PlaybackPhase.ERROR }
        assertEquals(b.entryId, snapshot().currentEntryId)
        assertEquals(ErrorDomain.PERMISSION_DENIED, snapshot().error?.domain)
        assertEquals(0, rawPlayer.mediaItemCount)
        assertFalse("c" in opened)
    }

    @Test
    fun rapidCommandsLeaveOnlyTheLastRequestPlaying() {
        seedQueue(mapOf(a to 1_000, b to 1_000, c to 1_000))
        // Media3 does not order player commands such as stop against custom commands, so only these are sent.
        val first = play(a, 1)
        val second = play(b, 1)
        val last = play(c, 1)

        assertEquals(SessionResult.RESULT_SUCCESS, await(last).resultCode)
        listOf(first, second).map { await(it).resultCode }.forEach {
            assertTrue("$it", it == SessionResult.RESULT_SUCCESS || it == SessionError.INFO_CANCELLED)
        }
        advanceUntil { snapshot().phase == PlaybackPhase.PLAYING }
        assertEquals(c.entryId, snapshot().currentEntryId)
        assertSessionAgreesWithSnapshot()
    }

    private fun startService() {
        lifecycle = Robolectric.buildService(PlaybackService::class.java).create()
        service = lifecycle.get()
        val info = MediaSession.ControllerInfo.createTestOnlyControllerInfo("com.audipp", Process.myPid(),
            Process.myUid(), 0, 0, true, Bundle.EMPTY, true)
        controller = await(MediaController.Builder(service, service.onGetSession(info)!!.token).buildAsync())
        controllers += controller
    }

    private fun restartService() {
        controllers.forEach { it.release() }
        controllers.clear()
        lifecycle.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        startService()
    }

    private fun seedQueue(durations: Map<QueueEntry, Int>) {
        durations.forEach { (entry, ms) -> writeWav(entry.variantId.value, ms) }
        val provider = object : PlaybackServiceTest.DeniedProvider() {
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                val name = uri.lastPathSegment!!
                beforeOpen(name)
                opened += name
                if (name in denied) throw SecurityException("grant revoked")
                return ParcelFileDescriptor.open(File(service.cacheDir, "$name.wav"), ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        provider.attachInfo(service, ProviderInfo().apply { authority = "fixture" })
        ShadowContentResolver.registerProviderInternal("fixture", provider)
        runBlocking {
            database.libraryDao().save(LibraryBatch(
                sources = durations.keys.map {
                    val id = it.variantId.value
                    SourceEntity("source-$id", SourceKind.LOCAL, localUri = "content://fixture/$id",
                        availability = Availability.PLAYABLE, updatedAt = 1)
                },
                tracks = listOf(TrackEntity("track")),
                variants = durations.map { (entry, ms) ->
                    VariantEntity(entry.variantId.value, "track", "source-${entry.variantId.value}", durationMs = ms.toLong())
                },
            ))
            queues.create(queueId, null, 1)
            assertEquals(QueueEditResult.Updated(1), queues.replaceEntries(queueId, 0, durations.keys.toList(), 2))
        }
    }

    private fun writeWav(name: String, durationMs: Int) {
        val sampleRate = 8_000
        val pcmBytes = durationMs * sampleRate / 1_000 * Short.SIZE_BYTES
        val wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * Short.SIZE_BYTES)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcmBytes).array()
        File(service.cacheDir, "$name.wav").writeBytes(wav)
    }

    private fun play(entry: QueueEntry, revision: Long) =
        PlaybackConnection.playQueue(controller, queueId, entry.entryId, revision)

    private fun edit(revision: Long, entries: List<QueueEntry>, stopped: QueueEntryId? = null) =
        runBlocking { queues.replaceEntries(queueId, revision, entries, 3, stopped) }

    private fun checkpoint() = runBlocking { queues.checkpoint() }

    private fun snapshot(): PlaybackSnapshot = PlaybackConnection.snapshot(controller.sessionExtras)!!

    // The notification and the mini player read this same session player.
    private fun assertSessionAgreesWithSnapshot() {
        val snapshot = snapshot()
        assertEquals(snapshot.currentEntryId?.value, controller.currentMediaItem?.mediaId)
        assertEquals(snapshot.phase == PlaybackPhase.PLAYING, controller.isPlaying)
        assertEquals(Player.STATE_READY, controller.playbackState)
    }

    private val rawPlayer: ExoPlayer get() = ReflectionHelpers.getField(service, "player")
    private val journal: PlaybackCheckpointJournal get() = ReflectionHelpers.getField(service, "checkpoints")

    private fun idle(ms: Long) {
        val until = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < until) step()
    }

    private fun step() {
        shadowOf(rawPlayer.playbackLooper).idleFor(20, TimeUnit.MILLISECONDS)
        shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
        Thread.sleep(5)
    }

    private fun advanceUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) step()
        assertTrue("condition timed out: ${snapshot()} write=${journal.lastWrite.value} cp=${checkpoint()}", condition())
    }

    private fun <T> await(future: ListenableFuture<T>): T {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!future.isDone && System.nanoTime() < deadline) step()
        assertTrue("Media3 response timed out", future.isDone)
        return future.get()
    }
}
