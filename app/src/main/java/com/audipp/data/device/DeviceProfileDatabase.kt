package com.audipp.data.device

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

@Database(
    entities = [ComponentRow::class, EvidenceRow::class, ProfileRow::class,
        ProfileComponentRow::class, PresetRow::class],
    version = 1,
    exportSchema = true,
)
internal abstract class DeviceProfileDatabase : RoomDatabase() {
    abstract fun profiles(): DeviceProfileDao

    companion object {
        fun open(context: Context, name: String): DeviceProfileDatabase = Room.databaseBuilder(
            context.applicationContext, DeviceProfileDatabase::class.java, name,
        ).build()
    }
}

@Entity(tableName = "device_components")
internal data class ComponentRow(
    @PrimaryKey @ColumnInfo(name = "component_id") val id: String,
    val kind: String,
    val manufacturer: String?,
    val product: String?,
    val vid: Int?,
    val pid: Int?,
    @ColumnInfo(name = "usb_serial_hash") val usbSerialHash: String?,
    val notes: String?,
)

@Entity(
    tableName = "device_evidence",
    primaryKeys = ["component_id", "ordinal"],
    foreignKeys = [ForeignKey(ComponentRow::class, ["component_id"], ["component_id"],
        onDelete = ForeignKey.CASCADE)],
)
internal data class EvidenceRow(
    @ColumnInfo(name = "component_id") val componentId: String,
    val ordinal: Int,
    @ColumnInfo(name = "evidence_json") val document: String,
)

@Entity(tableName = "device_profiles")
internal data class ProfileRow(
    @PrimaryKey @ColumnInfo(name = "profile_id") val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "created_at") val createdAtEpochMs: Long,
)

@Entity(
    tableName = "profile_components",
    primaryKeys = ["profile_id", "component_id", "role"],
    foreignKeys = [
        ForeignKey(ProfileRow::class, ["profile_id"], ["profile_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ComponentRow::class, ["component_id"], ["component_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("component_id")],
)
internal data class ProfileComponentRow(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "component_id") val componentId: String,
    val role: String,
    val ordinal: Int,
)

@Entity(
    tableName = "audio_presets",
    primaryKeys = ["profile_id", "preset_id"],
    foreignKeys = [ForeignKey(ProfileRow::class, ["profile_id"], ["profile_id"],
        onDelete = ForeignKey.CASCADE)],
)
internal data class PresetRow(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "preset_id") val presetId: String,
    val ordinal: Int,
    @ColumnInfo(name = "display_name") val displayName: String,
    val revision: Long,
    @ColumnInfo(name = "settings_json") val settings: String,
    @ColumnInfo(name = "default_volume_json") val defaultVolume: String?,
    @ColumnInfo(name = "initial_volume_json") val initialVolume: String?,
    @ColumnInfo(name = "safe_volume_json") val safeVolume: String?,
    @ColumnInfo(name = "physical_gain_note") val physicalGainNote: String?,
    @ColumnInfo(name = "impulse_response_uri") val impulseResponseUri: String?,
)

internal data class ComponentRecord(
    @Embedded val component: ComponentRow,
    @Relation(parentColumn = "component_id", entityColumn = "component_id")
    val evidence: List<EvidenceRow>,
)

internal data class ProfileRecord(
    @Embedded val profile: ProfileRow,
    @Relation(parentColumn = "profile_id", entityColumn = "profile_id")
    val components: List<ProfileComponentRow>,
    @Relation(parentColumn = "profile_id", entityColumn = "profile_id")
    val presets: List<PresetRow>,
)

@Dao
internal interface DeviceProfileDao {
    @Upsert suspend fun saveComponent(component: ComponentRow)
    @Query("DELETE FROM device_evidence WHERE component_id = :id")
    suspend fun clearEvidence(id: String)
    @Insert suspend fun insertEvidence(rows: List<EvidenceRow>)

    @Upsert suspend fun saveProfile(profile: ProfileRow)
    @Query("DELETE FROM profile_components WHERE profile_id = :id")
    suspend fun clearProfileComponents(id: String)
    @Insert suspend fun insertProfileComponents(rows: List<ProfileComponentRow>)
    @Query("DELETE FROM audio_presets WHERE profile_id = :id")
    suspend fun clearPresets(id: String)
    @Insert suspend fun insertPresets(rows: List<PresetRow>)

    @Transaction
    @Query("SELECT * FROM device_components ORDER BY component_id")
    suspend fun components(): List<ComponentRecord>

    @Transaction
    @Query("""
        SELECT * FROM device_components WHERE component_id IN (
            SELECT component_id FROM profile_components WHERE profile_id = :id
        )
    """)
    suspend fun componentsForProfile(id: String): List<ComponentRecord>

    @Transaction
    @Query("SELECT * FROM device_profiles WHERE profile_id = :id")
    suspend fun profile(id: String): ProfileRecord?

    @Transaction
    @Query("SELECT * FROM device_profiles ORDER BY created_at, profile_id")
    suspend fun profiles(): List<ProfileRecord>

    @Query("DELETE FROM device_profiles WHERE profile_id = :id")
    suspend fun deleteProfile(id: String): Int

    @Query("DELETE FROM device_components WHERE component_id = :id")
    suspend fun deleteComponent(id: String): Int
}
