package com.audipp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

@Entity(tableName = "local_scan_roots")
internal data class LocalScanRoot(
    @PrimaryKey @ColumnInfo(name = "root_id") val id: String,
    @ColumnInfo(name = "tree_uri") val treeUri: String,
    @ColumnInfo(name = "scan_id") val scanId: String? = null,
)

@Entity(
    tableName = "local_scan_sources",
    foreignKeys = [
        ForeignKey(entity = LocalScanRoot::class, parentColumns = ["root_id"], childColumns = ["root_id"]),
        ForeignKey(entity = SourceEntity::class, parentColumns = ["source_id"], childColumns = ["source_id"]),
    ],
    indices = [Index(value = ["root_id", "last_seen_scan_id"])],
)
internal data class LocalScanSource(
    @PrimaryKey @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "root_id") val rootId: String,
    @ColumnInfo(name = "last_seen_scan_id") val lastSeenScanId: String,
)

@Dao
internal interface SafScanDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun register(root: LocalScanRoot)

    @Query("SELECT root_id FROM local_scan_roots ORDER BY root_id")
    suspend fun roots(): List<String>

    @Query("SELECT * FROM local_scan_roots WHERE root_id = :id")
    suspend fun root(id: String): LocalScanRoot?

    @Query("UPDATE local_scan_roots SET scan_id = :scanId WHERE root_id = :rootId")
    suspend fun beginScan(rootId: String, scanId: String): Int

    @Query("SELECT scan_id FROM local_scan_roots WHERE root_id = :rootId")
    suspend fun currentScanId(rootId: String): String?

    @Upsert
    suspend fun recordVisit(source: LocalScanSource)

    @Query("""
        UPDATE sources SET availability = 'MISSING', updated_at = :updatedAt
        WHERE availability != 'MISSING' AND source_id IN (
            SELECT source_id FROM local_scan_sources
            WHERE root_id = :rootId AND last_seen_scan_id != :scanId
        ) AND EXISTS (SELECT 1 FROM local_scan_roots WHERE root_id = :rootId AND scan_id = :scanId)
    """)
    suspend fun markUnvisitedMissing(rootId: String, scanId: String, updatedAt: Long): Int
}
