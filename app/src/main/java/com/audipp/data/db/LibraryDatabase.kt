package com.audipp.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.audipp.AudippApplication
import com.audipp.domain.model.QueueEntry
import com.audipp.domain.model.QueueEntryId
import java.util.UUID

@Database(
    entities = [SourceEntity::class, TrackEntity::class, VariantEntity::class,
        AlbumEntity::class, ArtistEntity::class, TrackArtistEntity::class,
        QueueEntity::class, QueueItemEntity::class, PlaybackCheckpointEntity::class,
        PlaylistEntity::class, PlaylistItemEntity::class, LocalScanRoot::class, LocalScanSource::class,
        PlayEventEntity::class, PlaySegmentEntity::class, ArtworkOverrideEntity::class,
        AlbumEditEntity::class, AlbumEditVariantEntity::class],
    version = 8,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
    ],
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun libraryQueryDao(): LibraryQueryDao
    abstract fun queueDao(): QueueDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun artworkDao(): ArtworkDao
    internal abstract fun albumDao(): AlbumDao
    internal abstract fun safScanDao(): SafScanDao
    internal abstract fun playbackHistoryDao(): PlaybackHistoryDao

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

internal val Context.libraryDatabase: LibraryDatabase
    get() = (applicationContext as AudippApplication).libraryDatabase
