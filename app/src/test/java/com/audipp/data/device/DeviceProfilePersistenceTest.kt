package com.audipp.data.device

import com.audipp.domain.device.AudioPreset
import com.audipp.domain.device.AudioPresetSettings
import com.audipp.domain.device.CapabilityClaim
import com.audipp.domain.device.ComponentKind
import com.audipp.domain.device.ComponentRole
import com.audipp.domain.device.DeviceComponent
import com.audipp.domain.device.DeviceProfile
import com.audipp.domain.device.MeasurementConditions
import com.audipp.domain.device.ProfileComponent
import com.audipp.domain.device.RegistryEvidence
import com.audipp.domain.device.RegistryOrigin
import com.audipp.domain.device.SharedDeviceProfile
import com.audipp.domain.model.EvidenceLevel
import com.audipp.domain.model.Measured
import com.audipp.domain.model.ModelJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceProfilePersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: DeviceProfileDatabase
    private lateinit var store: DeviceProfileStore

    @Before
    fun open() {
        database = DeviceProfileDatabase.open(context, "device-profile-review.db")
        store = DeviceProfileStore(database)
    }

    @After
    fun close() {
        store.close()
        context.deleteDatabase("device-profile-review.db")
    }

    @Test
    fun distinguishesMeasurementLevelsAfterReopenAndInSharedJson() = runBlocking {
        val claim = CapabilityClaim(supported = true)
        val conditions = MeasurementConditions(1, 35, AudioPresetSettings(), "private-procedure")
        val observed = RegistryEvidence(
            RegistryOrigin.MEASUREMENT,
            Measured(claim, EvidenceLevel.APP_OBSERVED, "private-source", 1),
            conditions,
        )
        val verified = observed.copy(
            claim = observed.claim.copy(level = EvidenceLevel.EXTERNAL_VERIFIED, testEvidenceId = "private-id"),
        )
        val component = DeviceComponent(ComponentKind.DAC, evidence = listOf(observed, verified))
        val profile = DeviceProfile(
            "DAC", 1, components = listOf(ProfileComponent(component.id, ComponentRole.DAC)),
        )
        store.saveComponent(component)
        store.saveProfile(profile)
        store.close()
        open()

        assertEquals(listOf(observed, verified), store.components().single().evidence)
        val sharedJson = ModelJson.encode(requireNotNull(store.sharedCopy(profile.id)))
        val reports = ModelJson.format.parseToJsonElement(sharedJson).jsonObject
            .getValue("value").jsonObject.getValue("components").jsonArray.single().jsonObject
            .getValue("reports").jsonArray
        assertEquals(
            listOf("APP_OBSERVED", "EXTERNAL_VERIFIED"),
            reports.map { it.jsonObject.getValue("evidenceLevel").jsonPrimitive.content },
        )
        assertTrue("private fields leaked", "private-" !in sharedJson)
    }

    @Test
    fun preservesAttributeProvenanceForMatchingOsAndManualValues() = runBlocking {
        val os = DeviceComponent(
            kind = ComponentKind.DAC,
            manufacturer = Measured("Vendor", EvidenceLevel.OS_REPORTED, "private-os-vendor", 1),
            product = Measured("Model", EvidenceLevel.OS_REPORTED, "private-os-model", 2),
            vendorId = Measured(0x0123, EvidenceLevel.OS_REPORTED, "private-os-vid", 3),
            productId = Measured(0x4567, EvidenceLevel.OS_REPORTED, "private-os-pid", 4),
        )
        val manual = DeviceComponent(
            kind = ComponentKind.DAC,
            manufacturer = Measured("Vendor", EvidenceLevel.SOURCE, "private-user-vendor", 5),
            product = Measured("Model", EvidenceLevel.SOURCE, "private-user-model", 6),
            vendorId = Measured(0x0123, EvidenceLevel.SOURCE, "private-user-vid", 7),
            productId = Measured(0x4567, EvidenceLevel.SOURCE, "private-user-pid", 8),
        )
        val unknown = DeviceComponent(
            kind = ComponentKind.DAC,
            productId = Measured(null, EvidenceLevel.UNKNOWN, "private-os-pid", 9),
        )
        val components = listOf(os, manual, unknown)
        val profile = DeviceProfile(
            "DACs", 1, components = components.map { ProfileComponent(it.id, ComponentRole.DAC) },
        )
        components.forEach { store.saveComponent(it) }
        store.saveProfile(profile)
        store.close()
        open()

        assertEquals(components.toSet(), store.components().toSet())
        val sharedJson = ModelJson.encode(requireNotNull(store.sharedCopy(profile.id)))
        val (osReport, manualReport, unknownReport) = ModelJson.decode<SharedDeviceProfile>(sharedJson).components
        assertEquals(0x0123, osReport.vendorId)
        assertEquals(0x4567, osReport.productId)
        assertEquals(osReport.vendorId, manualReport.vendorId)
        assertEquals(osReport.productId, manualReport.productId)
        assertEquals(EvidenceLevel.OS_REPORTED, osReport.vendorIdEvidenceLevel)
        assertEquals(EvidenceLevel.OS_REPORTED, osReport.productIdEvidenceLevel)
        assertEquals(EvidenceLevel.SOURCE, manualReport.vendorIdEvidenceLevel)
        assertEquals(EvidenceLevel.SOURCE, manualReport.productIdEvidenceLevel)
        assertNull(unknownReport.vendorId)
        assertNull(unknownReport.productId)
        assertNull(unknownReport.vendorIdEvidenceLevel)
        assertEquals(EvidenceLevel.UNKNOWN, unknownReport.productIdEvidenceLevel)
        assertTrue("private fields leaked", "private-" !in sharedJson)
    }

    @Test
    fun rejectsInvalidOrdinalsInEveryStoredList() = runBlocking {
        val evidence = RegistryEvidence(
            RegistryOrigin.OS_REPORT, Measured(null, EvidenceLevel.UNKNOWN, "OS", 1),
        )
        val dac = DeviceComponent(ComponentKind.DAC, evidence = listOf(evidence, evidence))
        val amp = DeviceComponent(ComponentKind.AMP)
        val secondPreset = AudioPreset("Second")
        val profile = DeviceProfile(
            "DAC and amp", 1,
            components = listOf(
                ProfileComponent(dac.id, ComponentRole.DAC),
                ProfileComponent(amp.id, ComponentRole.AMPLIFIER),
            ),
            presets = listOf(AudioPreset("First"), secondPreset),
        )
        store.saveComponent(dac)
        store.saveComponent(amp)
        store.saveProfile(profile)

        val tables = listOf("device_evidence", "profile_components", "audio_presets")
        val sql = database.openHelper.writableDatabase
        for (table in tables) {
            val invalidOrdinals = if (table == "device_evidence") listOf(-1L, 2L) else listOf(-1L, 0L, 2L)
            for (ordinal in invalidOrdinals) {
                sql.execSQL("UPDATE $table SET ordinal = $ordinal WHERE ordinal = 1")
                val result = runCatching {
                    if (table == "device_evidence") store.components() else store.profile(profile.id)
                }
                val error = result.exceptionOrNull()
                assertTrue("$table accepted ordinal $ordinal", error is IllegalArgumentException)
                assertTrue(error?.message.orEmpty().startsWith("Invalid ordinal in $table:"))
                store.saveComponent(dac)
                store.saveProfile(profile)
            }
        }
    }
}
