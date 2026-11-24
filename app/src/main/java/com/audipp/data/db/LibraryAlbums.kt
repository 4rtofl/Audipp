package com.audipp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

enum class AlbumEditKind { MERGE, SPLIT }

/** One user merge or split. Deleting it (undo) lets older edits or automatic grouping apply again. */
@Entity(tableName = "album_edits")
data class AlbumEditEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "edit_id") val editId: Long = 0,
    val kind: AlbumEditKind,
    val title: String?,
    @ColumnInfo(name = "album_artist") val albumArtist: String?,
    @ColumnInfo(name = "release_namespace") val releaseNamespace: String?,
    @ColumnInfo(name = "release_id") val releaseId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "album_edit_variants",
    primaryKeys = ["edit_id", "variant_id"],
    foreignKeys = [
        ForeignKey(entity = AlbumEditEntity::class, parentColumns = ["edit_id"], childColumns = ["edit_id"]),
        ForeignKey(entity = VariantEntity::class, parentColumns = ["variant_id"], childColumns = ["variant_id"]),
    ],
    indices = [Index("variant_id")],
)
data class AlbumEditVariantEntity(
    @ColumnInfo(name = "edit_id") val editId: Long,
    @ColumnInfo(name = "variant_id") val variantId: String,
)

data class AlbumTrack(
    @ColumnInfo(name = "variant_id") val variantId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "disc_no") val discNo: Int?,
    @ColumnInfo(name = "track_no") val trackNo: Int?,
    val title: String?,
)

data class AlbumEdit(
    @ColumnInfo(name = "edit_id") val editId: Long,
    val kind: AlbumEditKind,
    @ColumnInfo(name = "album_id") val albumId: String,
    val title: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val variants: Int,
)

sealed interface AlbumEditResult {
    data class Applied(val editId: Long, val albumId: String) : AlbumEditResult

    enum class Rejected : AlbumEditResult {
        /** Empty selection, or a Variant that is absent or was not indexed by a local scan. */
        UNKNOWN_VARIANT,

        /** A merge whose Variants already share one album. */
        ALREADY_TOGETHER,

        /** A split that is not a proper subset of one album. */
        NOT_PART_OF_ONE_ALBUM,
    }
}

internal data class CurrentAlbum(
    @ColumnInfo(name = "variant_id") val variantId: String,
    @ColumnInfo(name = "album_id") val albumId: String?,
    val title: String?,
    @ColumnInfo(name = "album_artist") val albumArtist: String?,
)

@Dao
internal interface AlbumDao {
    @Query("""
        SELECT v.variant_id, v.album_id, v.disc_no, v.track_no, v.local_album AS title,
               v.local_album_artist AS album_artist, v.local_release_namespace AS release_namespace,
               v.local_release_id AS release_id, ls.root_id, v.local_folder AS folder
        FROM track_variants v LEFT JOIN local_scan_sources ls ON ls.source_id = v.source_id
        WHERE v.local_folder IS NOT NULL ORDER BY v.variant_id
    """)
    suspend fun inputs(): List<AlbumInput>

    @Query("SELECT * FROM album_edits")
    suspend fun edits(): List<AlbumEditEntity>

    @Query("SELECT * FROM album_edit_variants")
    suspend fun editVariants(): List<AlbumEditVariantEntity>

    @Query("""
        SELECT v.variant_id, v.album_id, COALESCE(a.title, v.local_album) AS title,
               COALESCE(a.album_artist, v.local_album_artist) AS album_artist
        FROM track_variants v LEFT JOIN albums a ON a.album_id = v.album_id
        WHERE v.variant_id IN (:variantIds) AND v.local_folder IS NOT NULL
    """)
    suspend fun current(variantIds: List<String>): List<CurrentAlbum>

    @Query("SELECT COUNT(*) FROM track_variants WHERE album_id = :albumId")
    suspend fun albumSize(albumId: String): Int

    @Insert
    suspend fun insertEdit(edit: AlbumEditEntity): Long

    @Insert
    suspend fun insertEditVariants(rows: List<AlbumEditVariantEntity>)

    @Query("DELETE FROM album_edit_variants WHERE edit_id = :editId")
    suspend fun deleteEditVariants(editId: Long)

    @Query("DELETE FROM album_edits WHERE edit_id = :editId")
    suspend fun deleteEdit(editId: Long): Int

    @Upsert
    suspend fun upsertAlbums(rows: List<AlbumEntity>)

    @Query("UPDATE track_variants SET album_id = :albumId WHERE variant_id = :variantId")
    suspend fun assign(variantId: String, albumId: String?)

    // Only albums this classifier creates are removed; albums saved by other callers stay.
    @Query("""
        DELETE FROM albums
        WHERE (album_id LIKE 'auto-album:%' OR album_id LIKE 'user-album:%' OR album_id LIKE 'saf-album:%')
          AND album_id NOT IN (SELECT album_id FROM track_variants WHERE album_id IS NOT NULL)
    """)
    suspend fun deleteUnusedAlbums()

    @Query("""
        SELECT v.variant_id, v.track_id, v.disc_no, v.track_no,
               COALESCE(NULLIF(TRIM(v.local_title), ''), NULLIF(TRIM(t.title), '')) AS title
        FROM track_variants v JOIN tracks t ON t.track_id = v.track_id
        WHERE v.album_id = :albumId
        ORDER BY v.disc_no IS NULL, v.disc_no, v.track_no IS NULL, v.track_no, v.variant_id
    """)
    fun observeTracks(albumId: String): Flow<List<AlbumTrack>>

    @Query("""
        SELECT e.edit_id, e.kind, 'user-album:' || e.edit_id AS album_id, e.title, e.created_at,
               COUNT(ev.variant_id) AS variants
        FROM album_edits e LEFT JOIN album_edit_variants ev ON ev.edit_id = e.edit_id
        GROUP BY e.edit_id ORDER BY e.edit_id DESC
    """)
    fun observeEdits(): Flow<List<AlbumEdit>>
}

/**
 * Groups locally scanned Variants into albums and keeps user merges and splits across rescans.
 * Every call runs in one Room transaction; callers use a worker dispatcher as for other DAOs.
 */
class LibraryAlbums(private val db: LibraryDatabase) {
    private val dao get() = db.albumDao()

    /** Re-derives album membership from stored tags and user edits. A local scan calls this when it finishes. */
    suspend fun organize() {
        db.withTransaction { apply() }
    }

    suspend fun candidates(): List<AlbumCandidate> = db.withTransaction {
        val edited = latestEdits(dao.edits(), dao.editVariants()).keys
        classifyAlbums(dao.inputs().filter { it.variantId !in edited }).candidates
    }

    fun observeTracks(albumId: String): Flow<List<AlbumTrack>> = dao.observeTracks(albumId)

    fun observeEdits(): Flow<List<AlbumEdit>> = dao.observeEdits()

    /** Puts [variantIds] into one new album named after the first Variant's current album. */
    suspend fun merge(variantIds: List<String>, now: Long = System.currentTimeMillis()): AlbumEditResult =
        edit(AlbumEditKind.MERGE, variantIds, now)

    /** Moves [variantIds], a proper subset of one album, into a new album with the same name. */
    suspend fun split(variantIds: List<String>, now: Long = System.currentTimeMillis()): AlbumEditResult =
        edit(AlbumEditKind.SPLIT, variantIds, now)

    /** Removes one edit. Returns false when it does not exist. */
    suspend fun undo(editId: Long): Boolean = db.withTransaction {
        dao.deleteEditVariants(editId)
        (dao.deleteEdit(editId) == 1).also { if (it) apply() }
    }

    private suspend fun edit(kind: AlbumEditKind, variantIds: List<String>, now: Long): AlbumEditResult {
        val selected = variantIds.distinct()
        if (selected.isEmpty()) return AlbumEditResult.Rejected.UNKNOWN_VARIANT
        return db.withTransaction {
            val current = dao.current(selected).associateBy { it.variantId }
            if (current.size != selected.size) return@withTransaction AlbumEditResult.Rejected.UNKNOWN_VARIANT
            val albums = current.values.map { it.albumId }.toSet()
            val sole = albums.singleOrNull()
            when (kind) {
                AlbumEditKind.MERGE -> if (sole != null) return@withTransaction AlbumEditResult.Rejected.ALREADY_TOGETHER
                AlbumEditKind.SPLIT -> if (sole == null || dao.albumSize(sole) <= selected.size) {
                    return@withTransaction AlbumEditResult.Rejected.NOT_PART_OF_ONE_ALBUM
                }
            }
            val first = current.getValue(selected.first())
            val releases = dao.inputs().filter { it.variantId in current }
                .map { it.releaseNamespace to it.releaseId }.toSet()
            val release = releases.singleOrNull()?.takeIf { it.second != null }
            val editId = dao.insertEdit(AlbumEditEntity(
                kind = kind, title = first.title, albumArtist = first.albumArtist,
                releaseNamespace = release?.first, releaseId = release?.second, createdAt = now,
            ))
            dao.insertEditVariants(selected.map { AlbumEditVariantEntity(editId, it) })
            apply()
            AlbumEditResult.Applied(editId, userAlbumId(editId))
        }
    }

    private suspend fun apply() {
        val inputs = dao.inputs()
        val edits = latestEdits(dao.edits(), dao.editVariants())
        val automatic = classifyAlbums(inputs.filter { it.variantId !in edits })
        val target = automatic.assignments + edits.mapValues { userAlbumId(it.value.editId) }
        val used = target.values.toSet()
        dao.upsertAlbums(
            automatic.albums.filter { it.albumId in used } +
                edits.values.distinct().map { AlbumEntity(userAlbumId(it.editId), it.title, it.albumArtist, it.releaseNamespace, it.releaseId) },
        )
        for (input in inputs) {
            val albumId = target.getValue(input.variantId)
            if (albumId != input.albumId) dao.assign(input.variantId, albumId)
        }
        dao.deleteUnusedAlbums()
    }

    private fun userAlbumId(editId: Long) = "user-album:$editId"

    /** The newest edit wins for each Variant, so undoing it falls back to the previous one. */
    private fun latestEdits(
        edits: List<AlbumEditEntity>,
        rows: List<AlbumEditVariantEntity>,
    ): Map<String, AlbumEditEntity> {
        val byId = edits.associateBy { it.editId }
        return rows.groupBy { it.variantId }.mapValues { (_, entries) -> byId.getValue(entries.maxOf { it.editId }) }
    }
}
