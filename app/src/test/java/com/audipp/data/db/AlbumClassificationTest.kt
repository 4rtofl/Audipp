package com.audipp.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumClassificationTest {
    @Test
    fun oneReleaseIdAcrossDiscFoldersFormsOneAlbumEvenWithAMissingDiscNumber() {
        val result = classifyAlbums(listOf(
            row("d2t1", "CD2", disc = 2, track = 1, release = RELEASE),
            row("d1t1", "CD1", disc = 1, track = 1, release = RELEASE),
            row("d1t2", "CD1", disc = 1, track = 2, release = RELEASE),
            row("unknown-disc", "CD2", disc = null, track = 2, release = RELEASE),
        ))
        val album = result.albums.single()
        assertEquals(RELEASE_NAMESPACE to RELEASE, album.releaseNamespace to album.releaseId)
        assertEquals(setOf(album.albumId), result.assignments.values.toSet())
        assertTrue(result.candidates.isEmpty())
        // The ID is derived from the release alone, so a rescan or another folder layout keeps it.
        assertEquals(album.albumId, classifyAlbums(listOf(row("x", "elsewhere", release = RELEASE))).albums.single().albumId)
    }

    @Test
    fun differentReleasesWithTheSameNameStaySeparateAndAreNotOffered() {
        val result = classifyAlbums(listOf(
            row("a", "same", release = RELEASE),
            row("b", "same", release = OTHER_RELEASE),
        ))
        assertEquals(2, result.albums.size)
        assertNotEquals(result.assignments["a"], result.assignments["b"])
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun aReleaseWhoseTagsDisagreeIsOfferedInsteadOfJoined() {
        val result = classifyAlbums(listOf(
            row("a", "f", title = "First", release = RELEASE),
            row("b", "f", title = "Second", release = RELEASE),
        ))
        assertTrue(result.albums.isEmpty())
        assertEquals(mapOf("a" to null, "b" to null), result.assignments)
        assertEquals(AlbumCandidateReason.CONFLICTING_TAGS, result.candidates.single().reason)
        assertEquals(listOf("a", "b"), result.candidates.single().variantIds)
    }

    @Test
    fun oneFolderWithConsistentTagsAndDiscsFormsAnAlbumAndNormalizesTitleSpelling() {
        val result = classifyAlbums(listOf(
            row("a", "f", title = "Ａｌｂｕｍ  One", disc = 1, track = 1),
            row("b", "f", title = "album one", disc = 1, track = 2, albumArtist = null),
            row("c", "f", title = "ALBUM ONE ", disc = 2, track = 1),
        ))
        assertEquals("Ａｌｂｕｍ  One", result.albums.single().title)
        assertEquals("Artist", result.albums.single().albumArtist)
        assertEquals(1, result.assignments.values.toSet().size)
        assertTrue(result.candidates.isEmpty())
        assertNotNull(classifyAlbums(listOf(row("a", "f"), row("b", "f"))).albums.singleOrNull())
    }

    @Test
    fun aFolderWithAmbiguousDiscStructureIsOfferedInsteadOfJoined() {
        for (rows in listOf(
            listOf(row("a", "f", disc = 1, track = 1), row("b", "f", disc = null, track = 2)),
            listOf(row("a", "f", disc = 1, track = 1), row("b", "f", disc = 1, track = 1)),
            listOf(row("a", "f", track = 1), row("b", "f", track = 1)),
        )) {
            val result = classifyAlbums(rows)
            assertTrue(result.albums.isEmpty())
            assertEquals(AlbumCandidateReason.DISC_CONFLICT, result.candidates.single().reason)
        }
    }

    @Test
    fun conflictingAlbumArtistsInOneFolderAreOfferedInsteadOfJoined() {
        val result = classifyAlbums(listOf(row("a", "f", albumArtist = "A"), row("b", "f", albumArtist = "B")))
        assertTrue(result.albums.isEmpty())
        assertEquals(AlbumCandidateReason.CONFLICTING_TAGS, result.candidates.single().reason)
    }

    @Test
    fun theSameTitleInAnotherFolderWithoutReleaseIdsIsOfferedAndNotJoined() {
        val result = classifyAlbums(listOf(
            row("d1", "CD1", disc = 1, track = 1),
            row("d2", "CD2", disc = 2, track = 1),
            row("tagged", "CD3", release = RELEASE),
            row("other-artist", "CD4", albumArtist = "Someone else"),
        ))
        assertEquals(4, result.albums.size)
        val candidate = result.candidates.single()
        assertEquals(AlbumCandidateReason.SAME_TITLE, candidate.reason)
        assertEquals(listOf("d1", "d2"), candidate.variantIds)
        assertEquals(listOf(result.assignments["d1"], result.assignments["d2"]), candidate.albumIds)
        // The same folder name under another root is another album.
        val roots = classifyAlbums(listOf(row("a", "f", root = "one"), row("b", "f", root = "two")))
        assertEquals(2, roots.albums.size)
        assertEquals(AlbumCandidateReason.SAME_TITLE, roots.candidates.single().reason)
    }

    @Test
    fun untaggedVariantsStayUnclassifiedWithoutACandidate() {
        val result = classifyAlbums(listOf(row("a", "f", title = null), row("b", "f", title = "  ")))
        assertTrue(result.albums.isEmpty())
        assertNull(result.assignments.getValue("a"))
        assertTrue(result.candidates.isEmpty())
    }

    private fun row(
        id: String,
        folder: String,
        title: String? = "Album",
        albumArtist: String? = "Artist",
        disc: Int? = null,
        track: Int? = null,
        release: String? = null,
        root: String = "root",
    ) = AlbumInput(id, null, disc, track, title, albumArtist, release?.let { RELEASE_NAMESPACE }, release, root, folder)

    private companion object {
        const val RELEASE_NAMESPACE = "musicbrainz:release"
        const val RELEASE = "0e7a9c1b-2f4d-4c3b-9a8e-1d2c3b4a5f60"
        const val OTHER_RELEASE = "11111111-2f4d-4c3b-9a8e-1d2c3b4a5f60"
    }
}
