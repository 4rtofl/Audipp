package com.audipp.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SourceEntity::class, TrackEntity::class, VariantEntity::class,
        AlbumEntity::class, ArtistEntity::class, TrackArtistEntity::class,
        LocalScanRoot::class, LocalScanSource::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    internal abstract fun safScanDao(): SafScanDao

    companion object {
        fun open(context: Context, name: String = "library.db"): LibraryDatabase = Room.databaseBuilder(
            context.applicationContext, LibraryDatabase::class.java, name,
        ).build()
    }
}
