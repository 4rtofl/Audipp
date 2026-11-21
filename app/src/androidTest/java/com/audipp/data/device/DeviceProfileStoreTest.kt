package com.audipp.data.device

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.domain.device.AudioPreset
import com.audipp.domain.device.AudioPresetSettings
import com.audipp.domain.device.BufferSettings
import com.audipp.domain.device.CapabilityClaim
import com.audipp.domain.device.ChannelMode
import com.audipp.domain.device.ComponentKind
import com.audipp.domain.device.ComponentRole
import com.audipp.domain.device.DeviceComponent
import com.audipp.domain.device.DeviceProfile
import com.audipp.domain.device.DsdMode
import com.audipp.domain.device.DsdRate
import com.audipp.domain.device.DspSettings
import com.audipp.domain.device.EqBandType
import com.audipp.domain.device.MeasurementConditions
import com.audipp.domain.device.NormalizationMode
import com.audipp.domain.device.OutputBackend
import com.audipp.domain.device.OutputMode
import com.audipp.domain.device.OutputTarget
import com.audipp.domain.device.ParametricBand
import com.audipp.domain.device.ParametricEq
import com.audipp.domain.device.PcmFormat
import com.audipp.domain.device.ProfileComponent
import com.audipp.domain.device.RegistryEvidence
import com.audipp.domain.device.RegistryOrigin
import com.audipp.domain.device.ResamplerEngine
import com.audipp.domain.device.ResamplerFilter
import com.audipp.domain.device.ResamplerSettings
import com.audipp.domain.device.SharedDeviceProfile
import com.audipp.domain.device.VolumeSettings
import com.audipp.domain.model.EvidenceLevel
import com.audipp.domain.model.Measured
import com.audipp.domain.model.ModelJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceProfileStoreTest {
    private lateinit var context: Context
    private lateinit var store: DeviceProfileStore

    @Before
    fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)
        store = DeviceProfileStore.open(context, TEST_DB)
    }

    @After
    fun close() {
        store.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun reopensCombinationAndAllSettingsWithoutInferringUnknowns() = runBlocking {
        val first = dac()
        val second = dac().copy(usbSerialHash = null, evidence = emptyList())
        val amp = DeviceComponent(ComponentKind.AMP)
        val headphone = DeviceComponent(ComponentKind.HEADPHONE)
        val dap = DeviceComponent(ComponentKind.DAP)
        val components = listOf(first, second, amp, headphone, dap)
        components.forEach { store.saveComponent(it) }

        val profile = DeviceProfile(
            "Home", 10,
            components = listOf(
                ProfileComponent(dap.id, ComponentRole.PLAYER),
                ProfileComponent(first.id, ComponentRole.DAC),
                ProfileComponent(amp.id, ComponentRole.AMPLIFIER),
                ProfileComponent(headphone.id, ComponentRole.TRANSDUCER),
            ),
            presets = listOf(preset(), AudioPreset("Unknown")),
        )
        val otherProfile = DeviceProfile(
            "Away", 20, components = listOf(ProfileComponent(second.id, ComponentRole.DAC)),
        )
        store.saveProfile(profile)
        store.saveProfile(otherProfile)
        store.close()
        store = DeviceProfileStore.open(context, TEST_DB)

        assertNotEquals(first.id, second.id)
        assertEquals(components.toSet(), store.components().toSet())
        assertEquals(profile, store.profile(profile.id))
        assertEquals(listOf(profile, otherProfile), store.profiles())
        assertNull(store.profile("missing"))
    }

    @Test
    fun failedReplacementRollsBackAndCanBeRetried() = runBlocking {
        val first = dac()
        store.saveComponent(first)
        val profile = DeviceProfile(
            "Original", 10,
            components = listOf(ProfileComponent(first.id, ComponentRole.DAC)),
            presets = listOf(preset()),
        )
        store.saveProfile(profile)
        val replacementComponent = DeviceComponent(ComponentKind.DAC)
        val replacement = profile.copy(
            displayName = "Updated",
            components = listOf(ProfileComponent(replacementComponent.id, ComponentRole.DAC)),
            presets = listOf(profile.presets.single().copy(revision = 8)),
        )

        try {
            store.saveProfile(replacement)
            fail("Expected a foreign key constraint failure")
        } catch (_: SQLiteConstraintException) {
            assertEquals(profile, store.profile(profile.id))
        }

        store.saveComponent(replacementComponent)
        store.saveProfile(replacement)
        assertEquals(replacement, store.profile(profile.id))
        assertEquals(replacementComponent, store.components().single { it.id == replacementComponent.id })

        assertTrue(store.deleteProfile(profile.id))
        assertNull(store.profile(profile.id))
        assertTrue(store.deleteComponent(replacementComponent.id))
        assertEquals(listOf(first), store.components())
    }

    @Test
    fun cancellationBeforeCommitRestoresThePreviousVersion() = runBlocking {
        store.close()
        val database = DeviceProfileDatabase.open(context, TEST_DB)
        store = DeviceProfileStore(database)
        val preset = preset()
        val original = DeviceProfile("Original", 10, presets = listOf(preset))
        val edited = original.copy(displayName = "Edited", presets = listOf(preset.copy(revision = 8)))
        store.saveProfile(original)

        val writeFinished = CompletableDeferred<Unit>()
        val edit = launch {
            database.withTransaction {
                store.saveProfile(edited)
                writeFinished.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            withTimeout(15_000) { writeFinished.await() }
        } finally {
            edit.cancelAndJoin()
        }

        assertEquals(original, store.profile(original.id))
        store.saveProfile(edited)
        assertEquals(edited, store.profile(original.id))
    }

    @Test
    fun sharedCopyKeepsStructuredReportsAndDoesNotSerializePrivateFields() = runBlocking {
        val dac = dac()
        val preset = preset()
        store.saveComponent(dac)
        val profile = DeviceProfile(
            displayName = "private-profile-label",
            createdAtEpochMs = 10,
            components = listOf(ProfileComponent(dac.id, ComponentRole.DAC)),
            presets = listOf(preset),
        )
        store.saveProfile(profile)
        val preview = requireNotNull(store.sharedCopy(profile.id))
        val report = preview.components.single()
        val serialized = ModelJson.encode(preview)

        assertEquals(dac.vendorId?.value, report.vendorId)
        assertEquals(dac.productId?.value, report.productId)
        assertEquals(dac.vendorId?.level, report.vendorIdEvidenceLevel)
        assertEquals(dac.productId?.level, report.productIdEvidenceLevel)
        assertEquals(dac.evidence.map { it.origin }, report.reports.map { it.origin })
        assertEquals(dac.evidence.map { it.claim.level }, report.reports.map { it.evidenceLevel })
        assertEquals(dac.evidence.map { it.claim.value }, report.reports.map { it.claim })
        assertEquals(settings, report.reports.last().conditions?.settings)
        assertEquals(36, report.reports.last().conditions?.androidApi)
        assertEquals(listOf(settings), preview.presets)
        assertEquals(preview, ModelJson.decode<SharedDeviceProfile>(serialized))
        assertEquals(profile, store.profile(profile.id))
        assertNull(store.sharedCopy("missing"))

        val wireComponent = ModelJson.format.parseToJsonElement(serialized).jsonObject
            .getValue("value").jsonObject.getValue("components").jsonArray.single().jsonObject
        assertEquals(
            setOf("role", "kind", "vendorId", "productId", "vendorIdEvidenceLevel", "productIdEvidenceLevel", "reports"),
            wireComponent.keys,
        )
        listOf(
            profile.id, dac.id, preset.id, "private-", "Bearer", "content://",
            "defaultVolume", "initialVolume", "safeVolume", "hardwareVolumeUnit",
        ).forEach { assertFalse(it, serialized.contains(it)) }
    }

    private fun dac() = DeviceComponent(
        kind = ComponentKind.DAC,
        manufacturer = Measured("private-manufacturer", EvidenceLevel.SOURCE, "private-user", 1),
        product = Measured("private-model", EvidenceLevel.SOURCE, "private-user", 2),
        vendorId = Measured(0x0123, EvidenceLevel.OS_REPORTED, "private-vid-source", 3),
        productId = Measured(0x4567, EvidenceLevel.OS_REPORTED, "private-pid-source", 4),
        usbSerialHash = "private-serial-hash",
        notes = "Bearer private-token",
        evidence = listOf(
            RegistryEvidence(
                RegistryOrigin.MANUFACTURER,
                Measured(CapabilityClaim(pcm = PcmFormat(96_000, 24, 2)), EvidenceLevel.SOURCE,
                    "private-datasheet", 1),
            ),
            RegistryEvidence(RegistryOrigin.USER_REPORT, Measured(null, EvidenceLevel.UNKNOWN, "private-user", 2)),
            RegistryEvidence(
                RegistryOrigin.OS_REPORT,
                Measured(CapabilityClaim(pcm = PcmFormat(channels = 2)), EvidenceLevel.OS_REPORTED, "OS", 3),
            ),
            RegistryEvidence(
                RegistryOrigin.MEASUREMENT,
                Measured(
                    CapabilityClaim(
                        dsdMode = DsdMode.DOP, dsdRateMultiplier = 256,
                        dopCarrier = PcmFormat(705_600, 24, 2), supported = false,
                    ),
                    EvidenceLevel.EXTERNAL_VERIFIED, "private-lab", 4, "private-evidence-id",
                ),
                MeasurementConditions(1, 36, settings, "private-procedure", "private-firmware"),
                notes = "content://private-evidence",
            ),
        ),
    )

    private fun preset() = AudioPreset(
        displayName = "private-preset-name",
        revision = 7,
        settings = settings,
        defaultVolume = VolumeSettings(digitalGainDb = -12.0),
        initialVolume = VolumeSettings(digitalGainDb = -34.5, androidVolumeStep = 1, muted = true),
        safeVolume = VolumeSettings(hardwareVolume = -60.0, hardwareVolumeUnit = "private-unit"),
        physicalGainNote = "private-gain-note",
        impulseResponseUri = "content://private-impulse-response",
    )

    private companion object {
        const val TEST_DB = "device-profiles-test.db"
        val settings = AudioPresetSettings(
            outputTarget = OutputTarget.USB,
            outputMode = OutputMode.PROCESSED,
            outputBackend = OutputBackend.CUSTOM_USB,
            pcm = PcmFormat(192_000, 32, 2),
            dsdMode = DsdMode.DOP,
            dsdRate = DsdRate.DSD256,
            resampler = ResamplerSettings(
                engine = ResamplerEngine.LIBSOXR, quality = 2, filter = ResamplerFilter.APODIZING,
                upsampleFactor = 4, nosBypass = false,
            ),
            dsp = DspSettings(
                enabled = true, channelMode = ChannelMode.DOWNMIX_5_1, includeLfe = false,
                leftGainDb = -1.0, rightGainDb = -2.0, normalization = NormalizationMode.REPLAY_GAIN_ALBUM,
                replayGainPreampDb = -2.5, replayGainPreventClipping = true, r128TargetLufs = -23.0,
                preampDb = -5.0,
                eq = ParametricEq(48_000, listOf(ParametricBand(EqBandType.PEAK, 300.0, 1.5, 1.0, true)),
                    preampDb = -1.0, enabled = true),
                convolutionEnabled = true, crossfeedEnabled = true, crossfeedLevelDb = -4.5,
                crossfeedCutoffHz = 700, headroomDb = 6.0, clipGuardEnabled = true, ditherEnabled = true,
            ),
            buffer = BufferSettings(160, 500, 32),
        )
    }
}
