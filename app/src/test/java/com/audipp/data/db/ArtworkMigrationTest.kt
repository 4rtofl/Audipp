package com.audipp.data.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArtworkMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun migratesV6KeepingLibraryRowsAndEmbeddedRefsAndStartsWithoutOverrides() = runBlocking {
        val name = "artwork-migration-v6.db"
        context.createLibraryDatabaseFromSchema(name, version = 6) { old ->
            old.execSQL("INSERT INTO sources (source_id, kind, local_uri, availability, updated_at) VALUES ('s', 'LOCAL', 'content://song', 'PLAYABLE', 1)")
            old.execSQL("INSERT INTO tracks (track_id) VALUES ('t')")
            old.execSQL("INSERT INTO track_variants (variant_id, track_id, source_id, local_artwork_ref) VALUES ('v', 't', 's', 'embedded:8:16:image/png')")
        }
        val upgraded = LibraryDatabase.open(context, name)
        try {
            assertEquals("embedded:8:16:image/png", upgraded.libraryDao().variant("v")?.localMetadata?.artworkRef)
            val row = upgraded.artworkDao().embedded("v")
            assertEquals("content://song", row?.documentUri)
            assertNull(upgraded.artworkDao().override("v"))
            assertEquals(7, upgraded.openHelper.writableDatabase.version)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
