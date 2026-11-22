package com.audipp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.audipp.domain.model.ArtworkOrigin
import com.audipp.domain.model.ArtworkSource
import com.audipp.domain.model.PermissionState

/** Variant ごとの差替え画像。元の埋込画像は音源ファイルを参照するので複製しない。行を消せば元に戻る。 */
@Entity(
    tableName = "artwork_overrides",
    foreignKeys = [ForeignKey(entity = VariantEntity::class, parentColumns = ["variant_id"], childColumns = ["variant_id"])],
)
data class ArtworkOverrideEntity(
    @PrimaryKey @ColumnInfo(name = "variant_id") val variantId: String,
    val origin: ArtworkOrigin,
    val location: String,
    @ColumnInfo(name = "provider_key") val providerKey: String? = null,
    @ColumnInfo(name = "display_permission") val displayPermission: PermissionState,
    @ColumnInfo(name = "redistribution_permission") val redistributionPermission: PermissionState,
    val expired: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    fun toSource(): ArtworkSource.Replacement = when (origin) {
        ArtworkOrigin.USER_SELECTED -> ArtworkSource.UserSelected(location)
        ArtworkOrigin.EXTERNAL -> ArtworkSource.External(location, requireNotNull(providerKey), displayPermission, redistributionPermission)
        ArtworkOrigin.EMBEDDED -> error("embedded artwork is not stored as an override")
    }

    companion object {
        fun from(variantId: String, source: ArtworkSource.Replacement, updatedAt: Long) = ArtworkOverrideEntity(
            variantId, source.origin,
            when (source) {
                is ArtworkSource.UserSelected -> source.documentUri
                is ArtworkSource.External -> source.localUri
            },
            (source as? ArtworkSource.External)?.providerKey,
            source.display, source.redistribution, updatedAt = updatedAt,
        )
    }
}

data class EmbeddedArtworkRow(
    @ColumnInfo(name = "local_uri") val documentUri: String?,
    @ColumnInfo(name = "local_artwork_ref") val artworkRef: String?,
)

@Dao
interface ArtworkDao {
    @Query(
        "SELECT s.local_uri, v.local_artwork_ref FROM track_variants v " +
            "JOIN sources s ON s.source_id = v.source_id WHERE v.variant_id = :variantId",
    )
    suspend fun embedded(variantId: String): EmbeddedArtworkRow?

    @Query("SELECT * FROM artwork_overrides WHERE variant_id = :variantId")
    suspend fun override(variantId: String): ArtworkOverrideEntity?

    @Upsert
    suspend fun save(entity: ArtworkOverrideEntity)

    @Query("DELETE FROM artwork_overrides WHERE variant_id = :variantId")
    suspend fun revert(variantId: String): Int

    @Query("UPDATE artwork_overrides SET expired = 1 WHERE variant_id = :variantId")
    suspend fun markExpired(variantId: String): Int
}
