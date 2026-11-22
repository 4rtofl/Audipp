package com.audipp.data.preferences

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.domain.model.AppPreferences
import com.audipp.domain.model.ConsentDecision
import com.audipp.domain.model.ConsentPurpose
import com.audipp.domain.model.SharingScope
import com.audipp.domain.model.ThemeMode
import com.audipp.domain.model.VisualizerPreferences
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One instrumentation run cannot restart its own process, so each phase is a separate
 * `am instrument` run selected by the `preferencesPhase` argument, in the order of [Phase].
 */
@RunWith(AndroidJUnit4::class)
class PreferenceStoreRestartTest {
    private enum class Phase { FRESH, DEFAULTS_AFTER_RESTART, WRITE, PERSISTED_AFTER_RESTART, CORRUPT, CORRUPT_AFTER_RESTART }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.filesDir, "datastore/settings.preferences_pb")
    private val corruptBytes = byteArrayOf(0x7f, 0x00, 0x13, 0x37, 0x42)

    @Test
    fun phase() = runBlocking<Unit> {
        val name = InstrumentationRegistry.getArguments().getString("preferencesPhase")
        assumeTrue("Run through the documented phase sequence", name != null)
        val store = context.preferenceStore
        when (Phase.valueOf(name!!)) {
            Phase.FRESH -> {
                file.delete()
                assertDefaults(store.data.first())
            }
            Phase.DEFAULTS_AFTER_RESTART -> assertDefaults(store.data.first())
            Phase.WRITE -> {
                store.setTheme(ThemeMode.DARK)
                store.setVisualizer(VisualizerPreferences(enabled = true, framesPerSecond = 60))
                store.setSharingScope(SharingScope.FRIENDS)
                store.setConsent(ConsentPurpose.SCROBBLING, ConsentDecision(version = 2, granted = true))
                store.setConsent(ConsentPurpose.CLOUD_SYNC, ConsentDecision(version = 1, granted = false))
            }
            Phase.PERSISTED_AFTER_RESTART -> {
                val saved = store.data.first()
                assertEquals(ThemeMode.DARK, saved.theme)
                assertEquals(VisualizerPreferences(enabled = true, framesPerSecond = 60), saved.visualizer)
                assertEquals(SharingScope.FRIENDS, saved.sharingScope)
                assertTrue(saved.permitsExternalTransmission(ConsentPurpose.SCROBBLING, 2))
                assertFalse(saved.permitsExternalTransmission(ConsentPurpose.SCROBBLING, 1))
                assertFalse(saved.permitsExternalTransmission(ConsentPurpose.CLOUD_SYNC, 1))
            }
            Phase.CORRUPT -> {
                file.parentFile!!.mkdirs()
                file.writeBytes(corruptBytes)
            }
            Phase.CORRUPT_AFTER_RESTART -> {
                expectFailure(PreferenceOperation.READ) { store.data.first() }
                expectFailure(PreferenceOperation.WRITE) { store.setTheme(ThemeMode.LIGHT) }
                assertArrayEquals(corruptBytes, file.readBytes())
                file.delete()
            }
        }
    }

    private fun assertDefaults(preferences: AppPreferences) {
        assertEquals(AppPreferences(), preferences)
        for (purpose in ConsentPurpose.entries) {
            assertFalse(preferences.permitsExternalTransmission(purpose, 1))
        }
    }

    private suspend fun expectFailure(operation: PreferenceOperation, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $operation to fail on corrupt settings")
        } catch (error: PreferenceStorageException) {
            assertEquals(operation, error.operation)
            assertEquals(PreferenceFailure.CORRUPT, error.failure)
        }
    }
}
