package com.audipp.data.device

import android.content.Context
import androidx.room.withTransaction
import com.audipp.domain.device.AudioPreset
import com.audipp.domain.device.ComponentKind
import com.audipp.domain.device.ComponentRole
import com.audipp.domain.device.DeviceComponent
import com.audipp.domain.device.DeviceProfile
import com.audipp.domain.device.ProfileComponent
import com.audipp.domain.device.SharedDeviceComponent
import com.audipp.domain.device.SharedDeviceProfile
import com.audipp.domain.device.SharedMeasurementConditions
import com.audipp.domain.device.SharedRegistryReport
import com.audipp.domain.model.ModelJson
import java.io.Closeable

class DeviceProfileStore internal constructor(
    private val database: DeviceProfileDatabase,
) : Closeable {
    private val dao = database.profiles()

    suspend fun saveComponent(component: DeviceComponent) = database.withTransaction {
        dao.saveComponent(ComponentRow(
            component.id, component.kind.name,
            component.manufacturer?.let { ModelJson.encode(it) },
            component.product?.let { ModelJson.encode(it) },
            component.vendorId?.let { ModelJson.encode(it) },
            component.productId?.let { ModelJson.encode(it) },
            component.usbSerialHash, component.notes,
        ))
        dao.clearEvidence(component.id)
        dao.insertEvidence(component.evidence.mapIndexed { index, evidence ->
            EvidenceRow(component.id, index.toLong(), ModelJson.encode(evidence))
        })
    }

    suspend fun saveProfile(profile: DeviceProfile) = database.withTransaction {
        dao.saveProfile(ProfileRow(profile.id, profile.displayName, profile.createdAtEpochMs))
        dao.clearProfileComponents(profile.id)
        dao.insertProfileComponents(profile.components.mapIndexed { index, component ->
            ProfileComponentRow(profile.id, component.componentId, component.role.name, index.toLong())
        })
        dao.clearPresets(profile.id)
        dao.insertPresets(profile.presets.mapIndexed { index, preset ->
            PresetRow(
                profile.id, preset.id, index.toLong(), preset.displayName, preset.revision,
                ModelJson.encode(preset.settings),
                preset.defaultVolume?.let { ModelJson.encode(it) },
                preset.initialVolume?.let { ModelJson.encode(it) },
                preset.safeVolume?.let { ModelJson.encode(it) },
                preset.physicalGainNote, preset.impulseResponseUri,
            )
        })
    }

    suspend fun components(): List<DeviceComponent> = database.withTransaction {
        dao.components().map(ComponentRecord::toModel)
    }

    suspend fun profile(id: String): DeviceProfile? = database.withTransaction {
        dao.profile(id)?.toModel()
    }

    suspend fun profiles(): List<DeviceProfile> = database.withTransaction {
        dao.profiles().map(ProfileRecord::toModel)
    }

    suspend fun deleteProfile(id: String): Boolean = dao.deleteProfile(id) != 0

    suspend fun deleteComponent(id: String): Boolean = dao.deleteComponent(id) != 0

    /** Builds a local preview before any sharing operation. */
    suspend fun sharedCopy(id: String): SharedDeviceProfile? = database.withTransaction {
        val profile = dao.profile(id)?.toModel() ?: return@withTransaction null
        val components = dao.componentsForProfile(id).associate { it.component.id to it.toModel() }
        SharedDeviceProfile(
            components = profile.components.map { link ->
                val component = components.getValue(link.componentId)
                SharedDeviceComponent(
                    role = link.role,
                    kind = component.kind,
                    vendorId = component.vendorId?.value,
                    productId = component.productId?.value,
                    vendorIdEvidenceLevel = component.vendorId?.level,
                    productIdEvidenceLevel = component.productId?.level,
                    reports = component.evidence.map { evidence ->
                        SharedRegistryReport(
                            origin = evidence.origin,
                            evidenceLevel = evidence.claim.level,
                            claim = evidence.claim.value,
                            conditions = evidence.conditions?.let {
                                SharedMeasurementConditions(it.appVersionCode, it.androidApi, it.settings)
                            },
                        )
                    },
                )
            },
            presets = profile.presets.map { it.settings },
        )
    }

    override fun close() = database.close()

    companion object {
        fun open(context: Context, name: String = "device-profiles.db"): DeviceProfileStore =
            DeviceProfileStore(DeviceProfileDatabase.open(context, name))
    }
}

private fun ComponentRecord.toModel() = DeviceComponent(
    kind = ComponentKind.valueOf(component.kind),
    id = component.id,
    manufacturer = component.manufacturer?.let { ModelJson.decode(it) },
    product = component.product?.let { ModelJson.decode(it) },
    vendorId = component.vid?.let { ModelJson.decode(it) },
    productId = component.pid?.let { ModelJson.decode(it) },
    usbSerialHash = component.usbSerialHash,
    notes = component.notes,
    evidence = evidence.inOrdinalOrder("device_evidence", EvidenceRow::ordinal).map {
        ModelJson.decode(it.document)
    },
)

private fun ProfileRecord.toModel() = DeviceProfile(
    displayName = profile.displayName,
    createdAtEpochMs = profile.createdAtEpochMs,
    id = profile.id,
    components = components.inOrdinalOrder("profile_components", ProfileComponentRow::ordinal).map {
        ProfileComponent(it.componentId, ComponentRole.valueOf(it.role))
    },
    presets = presets.inOrdinalOrder("audio_presets", PresetRow::ordinal).map {
        AudioPreset(
            id = it.presetId,
            displayName = it.displayName,
            revision = it.revision,
            settings = ModelJson.decode(it.settings),
            defaultVolume = it.defaultVolume?.let { document -> ModelJson.decode(document) },
            initialVolume = it.initialVolume?.let { document -> ModelJson.decode(document) },
            safeVolume = it.safeVolume?.let { document -> ModelJson.decode(document) },
            physicalGainNote = it.physicalGainNote,
            impulseResponseUri = it.impulseResponseUri,
        )
    },
)

private fun <T> List<T>.inOrdinalOrder(table: String, ordinal: (T) -> Long): List<T> =
    sortedBy(ordinal).onEachIndexed { index, row ->
        val actual = ordinal(row)
        require(actual == index.toLong()) { "Invalid ordinal in $table: expected $index, got $actual" }
    }
