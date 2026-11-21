package com.audipp.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.audipp.domain.model.VariantId

data class PlaylistEntry(val entryId: String, val variantId: VariantId) {
    init { require(entryId.isNotBlank()) }
}

@Entity(tableName = "playlists", indices = [Index(value = ["name"], unique = true)])
data class PlaylistEntity(
    @PrimaryKey @ColumnInfo(name = "playlist_id") val playlistId: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val revision: Long = 0,
)

@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class, parentColumns = ["playlist_id"], childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(entity = VariantEntity::class, parentColumns = ["variant_id"], childColumns = ["variant_id"]),
    ],
    indices = [Index(value = ["playlist_id", "ordinal"], unique = true), Index("variant_id")],
)
data class PlaylistItemEntity(
    @PrimaryKey @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val ordinal: Int,
    @ColumnInfo(name = "variant_id") val variantId: String,
) {
    fun toEntry() = PlaylistEntry(entryId, VariantId(variantId))
}
