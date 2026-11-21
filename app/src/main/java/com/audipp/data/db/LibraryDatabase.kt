package com.audipp.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.audipp.domain.model.QueueEntry
import com.audipp.domain.model.QueueEntryId
import java.util.UUID

@Database(
    entities = [SourceEntity::class, TrackEntity::class, VariantEntity::class,
        AlbumEntity::class, ArtistEntity::class, TrackArtistEntity::class,
        QueueEntity::class, QueueItemEntity::class, PlaybackCheckpointEntity::class,
        PlaylistEntity::class, PlaylistItemEntity::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun queueDao(): QueueDao
    abstract fun playlistDao(): PlaylistDao

    suspend fun appendPlaylistToQueue(
        playlistId: String,
        playlistRevision: Long,
        queueId: String,
        queueRevision: Long,
        updatedAt: Long,
    ): PlaylistCopyResult = withTransaction {
        val playlist = playlistDao().read(playlistId)
        if (playlist?.playlist?.revision != playlistRevision) return@withTransaction PlaylistCopyResult.StalePlaylist
        val queues = queueDao()
        val queue = queues.read(queueId)
        if (queue?.queue?.revision != queueRevision) return@withTransaction PlaylistCopyResult.StaleQueue

        val copies = playlist.entries.map { QueueEntry(QueueEntryId(UUID.randomUUID().toString()), it.variantId) }
        val result = queues.replaceEntries(queueId, queueRevision, queue.entries + copies, updatedAt)
        // Both revisions are checked in the same transaction and appending keeps the current entry.
        check(result is QueueEditResult.Updated)
        PlaylistCopyResult.Copied(result.revision)
    }

    companion object {
        fun open(context: Context, name: String = "library.db"): LibraryDatabase = Room.databaseBuilder(
            context.applicationContext, LibraryDatabase::class.java, name,
        ).build()
    }
}
