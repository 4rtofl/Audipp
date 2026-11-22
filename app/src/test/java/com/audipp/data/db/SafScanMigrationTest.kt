package com.audipp.data.db

import com.audipp.data.local.LibraryScanStatus
import com.audipp.domain.model.Availability
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.VariantId
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
class SafScanMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun migratesV1ToLatestWithoutChangingIdsAndStartsWithoutScanRoots() = runBlocking {
        val name = "saf-scan-migration-v1.db"
        context.createLibraryDatabaseFromSchema(name, version = 1) { old ->
            old.execSQL(
                "INSERT INTO sources (source_id, kind, availability, updated_at) VALUES ('source', 'LOCAL', 'MISSING', 1)",
            )
            old.execSQL("INSERT INTO tracks (track_id, title) VALUES ('track', 'Existing')")
            old.execSQL("INSERT INTO track_variants (variant_id, track_id, source_id) VALUES ('variant', 'track', 'source')")
        }
        val upgraded = LibraryDatabase.open(context, name)
        try {
            val variant = requireNotNull(upgraded.libraryDao().trackVariant(VariantId("variant")))
            assertEquals(SourceId("source"), variant.source.sourceId)
            assertEquals(TrackId("track"), variant.trackId)
            assertEquals(Availability.MISSING, variant.source.availability)
            assertEquals("Existing", upgraded.libraryDao().track("track")?.title)
            assertNull(upgraded.libraryDao().variant("variant")?.localMetadata)

            val scans = upgraded.safScanDao()
            assertEquals(emptyList<String>(), scans.roots())
            scans.register(LocalScanRoot("root", "content://fixture/tree/root"))
            assertNull(scans.lastScanStatus("root"))
            assertEquals(1, scans.beginScan("root", "scan"))
            assertEquals(0, scans.recordStatus("root", "older-scan", LibraryScanStatus.COMPLETE))
            assertEquals(1, scans.recordStatus("root", "scan", LibraryScanStatus.ACCESS_LOST))
            assertEquals(LibraryScanStatus.ACCESS_LOST, scans.lastScanStatus("root"))
            assertEquals(7, upgraded.openHelper.writableDatabase.version)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migratesV4WithoutDroppingPlaylistsQueuesOrScanState() = runBlocking {
        val name = "saf-scan-migration-v4.db"
        context.createLibraryDatabaseFromSchema(name, version = 4) { old ->
            old.execSQL(
                "INSERT INTO sources (source_id, kind, availability, updated_at) VALUES ('source', 'LOCAL', 'PLAYABLE', 1)",
            )
            old.execSQL("INSERT INTO tracks (track_id) VALUES ('track')")
            old.execSQL("INSERT INTO track_variants (variant_id, track_id, source_id, bit_depth) VALUES ('variant', 'track', 'source', 24)")
            old.execSQL("INSERT INTO local_scan_roots (root_id, tree_uri, scan_id, last_scan_status) " +
                "VALUES ('root', 'content://fixture/tree/root', 'scan', 'COMPLETE')")
            old.execSQL("INSERT INTO local_scan_sources (source_id, root_id, last_seen_scan_id) VALUES ('source', 'root', 'scan')")
            old.execSQL(
                "INSERT INTO playlists (playlist_id, name, created_at, updated_at, revision) VALUES ('list', 'List', 1, 2, 3)",
            )
            old.execSQL(
                "INSERT INTO playlist_items (entry_id, playlist_id, ordinal, variant_id) " +
                    "VALUES ('b', 'list', 1, 'variant'), ('a', 'list', 0, 'variant')",
            )
            old.execSQL(
                "INSERT INTO queues (queue_id, created_at, updated_at, revision, shuffle_enabled, repeat_mode) " +
                    "VALUES ('queue', 1, 2, 7, 0, 'OFF')",
            )
            old.execSQL(
                "INSERT INTO queue_items (entry_id, queue_id, ordinal, variant_id, added_at) " +
                    "VALUES ('q', 'queue', 0, 'variant', 2)",
            )
        }
        val upgraded = LibraryDatabase.open(context, name)
        try {
            val playlist = requireNotNull(upgraded.playlistDao().read("list"))
            assertEquals(3L, playlist.playlist.revision)
            assertEquals(
                listOf(PlaylistEntry("a", VariantId("variant")), PlaylistEntry("b", VariantId("variant"))),
                playlist.entries,
            )
            val queue = requireNotNull(upgraded.queueDao().read("queue"))
            assertEquals(7L, queue.queue.revision)
            assertEquals(listOf("q"), queue.entries.map { it.entryId.value })
            val variant = requireNotNull(upgraded.libraryDao().variant("variant"))
            assertEquals(24, variant.bitDepth)
            assertNull(variant.localMetadata)
            val scans = upgraded.safScanDao()
            assertEquals(listOf("root"), scans.roots())
            assertEquals("scan", scans.currentScanId("root"))
            assertEquals(LibraryScanStatus.COMPLETE, scans.lastScanStatus("root"))
            assertEquals(0, scans.markUnvisitedMissing("root", "scan", 2))
            assertEquals(1, scans.beginScan("root", "next"))
            assertEquals(1, scans.markUnvisitedMissing("root", "next", 3))
            assertEquals(7, upgraded.openHelper.writableDatabase.version)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
