package com.audipp.data.local

import com.audipp.data.db.LibraryBatch
import com.audipp.data.db.LibraryDatabase
import com.audipp.data.db.SourceEntity
import com.audipp.data.db.TrackEntity
import com.audipp.data.db.VariantEntity
import com.audipp.data.db.VariantLocalMetadata
import com.audipp.domain.model.ArtworkChange
import com.audipp.domain.model.ArtworkOrigin
import com.audipp.domain.model.ArtworkRejection
import com.audipp.domain.model.ArtworkSource
import com.audipp.domain.model.Availability
import com.audipp.domain.model.PermissionState
import com.audipp.domain.model.PlaceholderReason
import com.audipp.domain.model.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArtworkProviderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: LibraryDatabase
    private val opener = FakeOpener()
    private lateinit var provider: ArtworkProvider
    private val embeddedPng = encode(16, 16)
    private val song = "content://music/song.flac"

    @Before
    fun setUp() = runBlocking {
        db = LibraryDatabase.open(context, "artwork-test.db")
        provider = ArtworkProvider(db.artworkDao(), ArtworkLoader(opener))
        opener[song] = ByteArray(100) + embeddedPng
        db.libraryDao().save(LibraryBatch(
            sources = listOf(
                SourceEntity("s1", SourceKind.LOCAL, localUri = song, availability = Availability.PLAYABLE, updatedAt = 1),
                SourceEntity("s2", SourceKind.LOCAL, localUri = "content://music/none.flac", availability = Availability.PLAYABLE, updatedAt = 1),
            ),
            tracks = listOf(TrackEntity("t1")),
            variants = listOf(
                VariantEntity("with-art", "t1", "s1",
                    localMetadata = VariantLocalMetadata(artworkRef = "embedded:100:${embeddedPng.size}:image/png")),
                VariantEntity("no-art", "t1", "s2"),
            ),
        ))
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("artwork-test.db")
    }

    private suspend fun load(id: String = "with-art", edge: Int = 64) = provider.load(id, edge)
    private fun image(art: Artwork) = art as Artwork.Image
    private fun placeholder(art: Artwork) = art as Artwork.Placeholder

    @Test
    fun loadsTheEmbeddedImageAndFallsBackToAPlaceholderWithoutOne() = runBlocking {
        assertEquals(ArtworkOrigin.EMBEDDED, image(load()).origin)
        assertEquals(PlaceholderReason.NO_ARTWORK, placeholder(load("no-art")).reason)
        assertEquals(PlaceholderReason.NO_ARTWORK, placeholder(load("missing")).reason)
    }

    @Test
    fun replacingAndRevertingSwitchesBetweenTheOverrideAndTheEmbeddedImage() = runBlocking {
        opener["content://picked/1"] = encode(32, 32)
        val picked = ArtworkSource.UserSelected("content://picked/1")
        assertEquals(ArtworkChange.Applied, provider.replace("with-art", picked, 10))
        val replaced = image(load(edge = 32))
        assertEquals(ArtworkOrigin.USER_SELECTED, replaced.origin)
        assertEquals(32, replaced.bitmap.width)

        assertTrue(provider.revert("with-art"))
        val restored = image(load(edge = 16))
        assertEquals(ArtworkOrigin.EMBEDDED, restored.origin)
        assertEquals(16, restored.bitmap.width)
        assertFalse(provider.revert("with-art"))
    }

    @Test
    fun replacingAgainDropsTheStaleCachedImage() = runBlocking {
        opener["content://picked/1"] = encode(32, 32)
        provider.replace("with-art", ArtworkSource.UserSelected("content://picked/1"), 10)
        load(edge = 32)
        opener["content://picked/2"] = encode(24, 24)
        provider.replace("with-art", ArtworkSource.UserSelected("content://picked/2"), 11)
        assertEquals(24, image(load(edge = 24)).bitmap.width)
        assertEquals(24 * 24 * 4, provider.cacheBytes)
    }

    @Test
    fun anUnreadableOverrideExpiresToAPlaceholderUntilRevertedOrReplaced() = runBlocking {
        opener["content://picked/1"] = encode(32, 32)
        provider.replace("with-art", ArtworkSource.UserSelected("content://picked/1"), 10)
        opener.remove("content://picked/1")
        provider.load("with-art", 8) // キャッシュに載せない別サイズ
        assertEquals(PlaceholderReason.EXPIRED, placeholder(load()).reason)
        assertTrue(db.artworkDao().override("with-art")!!.expired)

        opener["content://picked/1"] = encode(32, 32) // 戻ってきても自動では復活しない
        assertEquals(PlaceholderReason.EXPIRED, placeholder(load()).reason)

        assertEquals(ArtworkChange.Applied, provider.replace("with-art", ArtworkSource.UserSelected("content://picked/1"), 12))
        assertEquals(ArtworkOrigin.USER_SELECTED, image(load()).origin)
        opener.remove("content://picked/1")
        provider.revert("with-art")
        assertEquals(ArtworkOrigin.EMBEDDED, image(load()).origin)
    }

    @Test
    fun aMissingEmbeddedSourceIsUnavailableNotExpired() = runBlocking {
        opener.remove(song)
        assertEquals(PlaceholderReason.UNAVAILABLE, placeholder(load()).reason)
        assertNull(db.artworkDao().override("with-art"))
    }

    @Test
    fun aRejectedReplacementKeepsTheCurrentOverride() = runBlocking {
        opener["content://picked/1"] = encode(32, 32)
        val picked = ArtworkSource.UserSelected("content://picked/1")
        provider.replace("with-art", picked, 10)
        opener.put("content://picked/bad", encode(8, 8), "audio/mpeg")
        opener["content://picked/huge"] = pngDeclaring(8000, 5001)

        assertEquals(ArtworkChange.Rejected(ArtworkRejection.TYPE_MISMATCH),
            provider.replace("with-art", ArtworkSource.UserSelected("content://picked/bad"), 11))
        assertEquals(ArtworkChange.Rejected(ArtworkRejection.TOO_MANY_PIXELS),
            provider.replace("with-art", ArtworkSource.UserSelected("content://picked/huge"), 11))
        assertEquals(ArtworkChange.Unreadable,
            provider.replace("with-art", ArtworkSource.UserSelected("content://picked/gone"), 11))
        assertEquals(picked, db.artworkDao().override("with-art")!!.toSource())
        assertEquals(10, db.artworkDao().override("with-art")!!.updatedAt)
    }

    @Test
    fun externalImagesKeepTheirPermissionsPerSourceAndDisplayDoesNotImplyRedistribution() = runBlocking {
        opener["content://cache/cover"] = encode(32, 32)
        val unreviewed = ArtworkSource.External("content://cache/cover", "caa")
        assertEquals(ArtworkChange.Rejected(ArtworkRejection.DISPLAY_NOT_ALLOWED), provider.replace("with-art", unreviewed, 10))
        assertNull(db.artworkDao().override("with-art"))

        val displayOnly = unreviewed.copy(display = PermissionState.ALLOWED)
        assertEquals(ArtworkChange.Applied, provider.replace("with-art", displayOnly, 11))
        val stored = db.artworkDao().override("with-art")!!.toSource() as ArtworkSource.External
        assertEquals(PermissionState.ALLOWED, stored.display)
        assertEquals(PermissionState.UNREVIEWED, stored.redistribution)
        assertEquals(ArtworkOrigin.EXTERNAL, image(load()).origin)
        assertFalse(stored.mayAttachToSharedPayload)

        val redistributable = displayOnly.copy(redistribution = PermissionState.ALLOWED)
        provider.replace("with-art", redistributable, 12)
        assertTrue((db.artworkDao().override("with-art")!!.toSource()).mayAttachToSharedPayload)
    }

    @Test
    fun nothingButAnExplicitRedistributionGrantMayEnterASharedPayload() {
        val embedded = ArtworkSource.Embedded("content://song", 0, 10, "image/png")
        assertFalse(embedded.mayAttachToSharedPayload)
        assertFalse(ArtworkSource.UserSelected("content://picked").mayAttachToSharedPayload)
        for (display in PermissionState.entries) {
            assertFalse(ArtworkSource.External("content://x", "p", display = display).mayAttachToSharedPayload)
        }
        assertTrue(ArtworkSource.External("content://x", "p", redistribution = PermissionState.ALLOWED).mayAttachToSharedPayload)
    }

    @Test
    fun parsesLocalArtworkRefsAndRedactsLocationsFromToString() {
        val parsed = ArtworkSource.Embedded.parse("content://song", "embedded:512:4096:image/jpeg")
        assertEquals(ArtworkSource.Embedded("content://song", 512, 4096, "image/jpeg"), parsed)
        assertNull(ArtworkSource.Embedded.parse("content://song", "embedded:512:0:image/jpeg"))
        assertNull(ArtworkSource.Embedded.parse("content://song", "http://example.com/a.jpg"))
        assertFalse(parsed.toString().contains("content://"))
        assertFalse(ArtworkSource.UserSelected("content://secret").toString().contains("secret"))
        assertFalse(ArtworkSource.External("content://secret", "caa").toString().contains("secret"))
    }
}
