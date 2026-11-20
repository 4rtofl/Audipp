package com.audipp.ui.navigation

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.MainActivity
import com.audipp.domain.model.AudioFormat
import com.audipp.domain.model.Availability
import com.audipp.domain.model.ErrorDomain
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.VariantId
import com.audipp.playback.PlaybackConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
@androidx.annotation.OptIn(UnstableApi::class)
class NavigationPlaybackIntegrationTest {
    @Test
    fun playbackBarPausesAndResumesTheServiceItemAcrossActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "Audipp navigation test.wav")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        ))
        try {
            writeSilentTrack(context, uri)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                runBlocking {
                    val model = scenario.navigationModel()
                    val connection = withContext(Dispatchers.Main) {
                        PlaybackConnection.connect(context, object : MediaController.Listener {})
                    }
                    var controller: MediaController? = null
                    try {
                        val session = connection.get(10, TimeUnit.SECONDS).also { controller = it }
                        val entry = QueueEntryId("navigation-fixture-entry")
                        val variant = TrackVariant(
                            VariantId("navigation-fixture-variant"), TrackId("navigation-fixture-track"),
                            SourceRef(SourceId("navigation-fixture-source"), SourceKind.LOCAL,
                                localDocumentUri = uri.toString(), availability = Availability.PLAYABLE),
                            format = AudioFormat(codec = "WAV"),
                        )
                        val missing = variant.copy(source = variant.source.copy(availability = Availability.MISSING))
                        val rejected = withContext(Dispatchers.Main) {
                            PlaybackConnection.playLocal(session, entry, missing)
                        }.get(10, TimeUnit.SECONDS)
                        assertEquals(ErrorDomain.SOURCE_IO, PlaybackConnection.error(rejected)?.domain)
                        withTimeout(10_000) {
                            model.playback.first { it == NavigationPlaybackState.Failed(ErrorDomain.SOURCE_IO) }
                        }

                        val result = withContext(Dispatchers.Main) {
                            PlaybackConnection.playLocal(session, entry, variant)
                        }.get(10, TimeUnit.SECONDS)
                        assertEquals(PlaybackConnection.error(result)?.code, SessionResult.RESULT_SUCCESS, result.resultCode)
                        val pause = model.controlForPhase(PlaybackPhase.PLAYING)
                        assertEquals(entry, pause.entryId)
                        withContext(Dispatchers.Main) { model.togglePlayback(pause) }
                        val resume = model.controlForPhase(PlaybackPhase.PAUSED)

                        scenario.recreate()
                        assertSame(model, scenario.navigationModel())
                        assertEquals(resume, model.controlForPhase(PlaybackPhase.PAUSED))
                        withContext(Dispatchers.Main) { model.togglePlayback(resume) }
                        assertEquals(pause, model.controlForPhase(PlaybackPhase.PLAYING))
                    } finally {
                        withContext(Dispatchers.Main) {
                            controller?.takeIf { it.isConnected }?.stop()
                            MediaController.releaseFuture(connection)
                        }
                    }
                }
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun ActivityScenario<MainActivity>.navigationModel(): NavigationViewModel {
        lateinit var model: NavigationViewModel
        onActivity { model = ViewModelProvider(it)[NavigationViewModel::class.java] }
        return model
    }

    private suspend fun NavigationViewModel.controlForPhase(phase: PlaybackPhase) = withTimeout(10_000) {
        val playback = playback.filterIsInstance<NavigationPlaybackState.Current>()
            .first { it.phase == phase && it.control != null }
        requireNotNull(playback.control)
    }

    private fun writeSilentTrack(context: Context, uri: Uri) {
        val sampleRate = 8_000
        val dataBytes = 30 * sampleRate * 2
        val wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataBytes)
        }
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(wav.array()) }
    }
}
