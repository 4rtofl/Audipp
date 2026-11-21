package com.audipp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

data class StoredPlaylist(val playlist: PlaylistEntity, val entries: List<PlaylistEntry>)

enum class PlaylistEditResult { UPDATED, STALE_REVISION, NAME_CONFLICT }

sealed interface PlaylistCopyResult {
    data class Copied(val queueRevision: Long) : PlaylistCopyResult
    data object StalePlaylist : PlaylistCopyResult
    data object StaleQueue : PlaylistCopyResult
}

@Dao
abstract class PlaylistDao {
    @Transaction
    open suspend fun create(playlistId: String, name: String, createdAt: Long): PlaylistEditResult {
        val normalized = name.trim()
        require(playlistId.isNotBlank() && normalized.isNotEmpty() && createdAt >= 0)
        if (idForName(normalized) != null) return PlaylistEditResult.NAME_CONFLICT
        insertPlaylist(PlaylistEntity(playlistId, normalized, createdAt, createdAt))
        return PlaylistEditResult.UPDATED
    }

    @Query("SELECT * FROM playlists ORDER BY name")
    abstract suspend fun list(): List<PlaylistEntity>

    @Transaction
    open suspend fun read(playlistId: String): StoredPlaylist? {
        val playlist = playlist(playlistId) ?: return null
        return StoredPlaylist(playlist, items(playlistId).map(PlaylistItemEntity::toEntry))
    }

    @Transaction
    open suspend fun rename(
        playlistId: String, expectedRevision: Long, name: String, updatedAt: Long,
    ): PlaylistEditResult {
        val normalized = name.trim()
        require(normalized.isNotEmpty() && updatedAt >= 0)
        val playlist = playlist(playlistId)
        if (playlist == null || playlist.revision != expectedRevision) return PlaylistEditResult.STALE_REVISION
        if (playlist.name == normalized) return PlaylistEditResult.UPDATED
        if (idForName(normalized) != null) return PlaylistEditResult.NAME_CONFLICT
        revise(playlistId, normalized, expectedRevision + 1, updatedAt)
        return PlaylistEditResult.UPDATED
    }

    @Transaction
    open suspend fun replaceEntries(
        playlistId: String, expectedRevision: Long, entries: List<PlaylistEntry>, updatedAt: Long,
    ): PlaylistEditResult {
        require(updatedAt >= 0)
        require(entries.map { it.entryId }.toSet().size == entries.size)
        val playlist = playlist(playlistId)
        if (playlist == null || playlist.revision != expectedRevision) return PlaylistEditResult.STALE_REVISION
        val previous = items(playlistId)
        val existing = previous.associateBy { it.entryId }
        require(entries.all { entry ->
            existing[entry.entryId]?.variantId?.let { it == entry.variantId.value } ?: true
        })
        if (previous.map(PlaylistItemEntity::toEntry) == entries) return PlaylistEditResult.UPDATED

        // The unique index is checked row by row, including during swaps.
        parkOrdinals(playlistId)
        entries.forEachIndexed { ordinal, entry ->
            if (entry.entryId in existing) {
                assignOrdinal(playlistId, entry.entryId, ordinal)
            } else {
                insertItem(PlaylistItemEntity(entry.entryId, playlistId, ordinal, entry.variantId.value))
            }
        }
        deleteParkedItems(playlistId)
        revise(playlistId, playlist.name, expectedRevision + 1, updatedAt)
        return PlaylistEditResult.UPDATED
    }

    suspend fun delete(playlistId: String, expectedRevision: Long): PlaylistEditResult =
        if (deleteAtRevision(playlistId, expectedRevision) == 1) PlaylistEditResult.UPDATED
        else PlaylistEditResult.STALE_REVISION

    @Insert protected abstract suspend fun insertPlaylist(playlist: PlaylistEntity)
    @Insert protected abstract suspend fun insertItem(item: PlaylistItemEntity)

    @Query("SELECT * FROM playlists WHERE playlist_id = :id")
    protected abstract suspend fun playlist(id: String): PlaylistEntity?

    @Query("SELECT playlist_id FROM playlists WHERE name = :name")
    protected abstract suspend fun idForName(name: String): String?

    @Query("SELECT * FROM playlist_items WHERE playlist_id = :id ORDER BY ordinal")
    protected abstract suspend fun items(id: String): List<PlaylistItemEntity>

    @Query("UPDATE playlists SET name = :name, revision = :revision, updated_at = :updatedAt WHERE playlist_id = :id")
    protected abstract suspend fun revise(id: String, name: String, revision: Long, updatedAt: Long)

    @Query("UPDATE playlist_items SET ordinal = -ordinal - 1 WHERE playlist_id = :id")
    protected abstract suspend fun parkOrdinals(id: String)

    @Query("UPDATE playlist_items SET ordinal = :ordinal WHERE playlist_id = :id AND entry_id = :entryId")
    protected abstract suspend fun assignOrdinal(id: String, entryId: String, ordinal: Int)

    @Query("DELETE FROM playlist_items WHERE playlist_id = :id AND ordinal < 0")
    protected abstract suspend fun deleteParkedItems(id: String)

    @Query("DELETE FROM playlists WHERE playlist_id = :id AND revision = :revision")
    protected abstract suspend fun deleteAtRevision(id: String, revision: Long): Int
}
