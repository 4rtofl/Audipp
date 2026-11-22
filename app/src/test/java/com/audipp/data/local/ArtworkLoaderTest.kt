package com.audipp.data.local

import android.graphics.Bitmap
import com.audipp.domain.model.ArtworkLimits
import com.audipp.domain.model.ArtworkRejection
import com.audipp.domain.model.ArtworkSource
import com.audipp.domain.model.PermissionState
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkLoaderTest {
    private val opener = FakeOpener()
    private val loader = ArtworkLoader(opener, ArtworkCache())
    private val user = ArtworkSource.UserSelected("content://picked/1")

    private fun bitmap(result: ArtworkLoad) = (result as ArtworkLoad.Loaded).bitmap
    private fun reason(result: ArtworkLoad) = (result as ArtworkLoad.Rejected).reason

    @Test
    fun downsamplesToLongEdgeAtMost2048AndFurtherToTheDisplaySize() = runBlocking {
        opener["content://picked/1"] = encode(4100, 64)
        val full = bitmap(loader.load(user, targetEdgePx = 4096))
        assertEquals(1025, full.width)
        val thumb = bitmap(loader.load(user, targetEdgePx = 300))
        assertEquals(512, thumb.width) // 4100/8: 目標 300 px を下回らない最大の縮小
        assertTrue(thumb.width >= 300)
    }

    @Test
    fun keepsAnImageThatAlreadyFitsWithoutDownsampling() = runBlocking {
        opener["content://picked/1"] = encode(2048, 16)
        assertEquals(2048, bitmap(loader.load(user, targetEdgePx = 4096)).width)
    }

    @Test
    fun enforcesThePixelLimitFromTheHeaderBeforeDecoding() = runBlocking {
        opener["content://picked/1"] = pngDeclaring(8000, 5001)
        assertEquals(ArtworkRejection.TOO_MANY_PIXELS, reason(loader.load(user, 512)))
        opener["content://picked/1"] = pngDeclaring(8000, 5000)
        assertNull(loader.inspect(user)) // ちょうど 40 MP は受け入れる
    }

    @Test
    fun rejectsOversizedBytesWithoutReadingEmbeddedOrBeyondTheLimitForStreams() = runBlocking {
        val embedded = ArtworkSource.Embedded("content://song", 0, ArtworkLimits.MAX_BYTES + 1, "image/png")
        assertEquals(ArtworkRejection.TOO_LARGE, reason(loader.load(embedded, 512)))
        assertEquals(0, opener.opens)

        val big = ArtworkLoader({ ArtworkStream(ZeroStream(ArtworkLimits.MAX_BYTES + 1), null) })
        assertEquals(ArtworkRejection.TOO_LARGE, reason(big.load(user, 512)))
    }

    @Test
    fun rejectsCorruptAndUnrecognisedImagesWithReasons() = runBlocking {
        opener["content://picked/1"] = pngDeclaring(8, 8).copyOf(40) // IHDR の途中で切れる
        assertEquals(ArtworkRejection.CORRUPT, reason(loader.load(user, 512)))
        opener["content://picked/1"] = ByteArray(64) { it.toByte() }
        assertEquals(ArtworkRejection.UNSUPPORTED_TYPE, reason(loader.load(user, 512)))
        opener["content://picked/1"] = encode(8, 8).copyOf(10)
        assertEquals(ArtworkRejection.CORRUPT, reason(loader.load(user, 512)))
    }

    @Test
    fun rejectsEmbeddedRangeThatTheFileCannotSatisfy() = runBlocking {
        val png = encode(8, 8)
        opener["content://song"] = png
        val beyond = ArtworkSource.Embedded("content://song", 0, png.size + 100L, "image/png")
        assertEquals(ArtworkRejection.CORRUPT, reason(loader.load(beyond, 64)))
        val past = ArtworkSource.Embedded("content://song", png.size + 10L, 10, "image/png")
        assertEquals(ArtworkRejection.CORRUPT, reason(loader.load(past, 64)))
    }

    @Test
    fun rejectsContentThatDisagreesWithItsDeclaredType() = runBlocking {
        val jpeg = encode(8, 8, Bitmap.CompressFormat.JPEG)
        val png = encode(8, 8)
        opener.put("content://picked/1", jpeg, "image/png")
        assertEquals(ArtworkRejection.TYPE_MISMATCH, reason(loader.load(user, 64)))
        opener.put("content://picked/1", png, "audio/mpeg")
        assertEquals(ArtworkRejection.TYPE_MISMATCH, reason(loader.load(user, 64)))
        val embedded = ArtworkSource.Embedded("content://song", 0, jpeg.size.toLong(), "image/png")
        opener["content://song"] = jpeg
        assertEquals(ArtworkRejection.TYPE_MISMATCH, reason(loader.load(embedded, 64)))
        opener.put("content://picked/1", png, "application/octet-stream")
        assertEquals(8, bitmap(loader.load(user, 8)).width)
    }

    @Test
    fun readsTheEmbeddedByteRangeOnly() = runBlocking {
        val png = encode(8, 8)
        opener["content://song"] = ByteArray(100) { 7 } + png + ByteArray(50)
        val embedded = ArtworkSource.Embedded("content://song", 100, png.size.toLong(), "image/png")
        assertEquals(8, bitmap(loader.load(embedded, 8)).width)
    }

    @Test
    fun doesNotOpenAnExternalImageWithoutDisplayPermission() = runBlocking {
        opener["content://cache/1"] = encode(8, 8)
        val unreviewed = ArtworkSource.External("content://cache/1", "caa")
        val denied = unreviewed.copy(display = PermissionState.DENIED, redistribution = PermissionState.ALLOWED)
        assertEquals(ArtworkRejection.DISPLAY_NOT_ALLOWED, reason(loader.load(unreviewed, 64)))
        assertEquals(ArtworkRejection.DISPLAY_NOT_ALLOWED, reason(loader.load(denied, 64)))
        assertEquals(0, opener.opens)
        assertEquals(8, bitmap(loader.load(unreviewed.copy(display = PermissionState.ALLOWED), 8)).width)
    }

    @Test
    fun reportsUnreadableWhenTheDocumentIsGoneOrFails() = runBlocking {
        assertTrue(loader.load(user, 64) is ArtworkLoad.Unreadable)
        val failing = ArtworkLoader({ throw IOException("revoked") })
        assertTrue(failing.load(user, 64) is ArtworkLoad.Unreadable)
    }

    @Test
    fun cacheHitDoesNotReadTheImageAgainAndLosesNothingOnOtherSizes() = runBlocking {
        opener["content://picked/1"] = encode(64, 64)
        val first = bitmap(loader.load(user, 64))
        assertSame(first, bitmap(loader.load(user, 64)))
        assertEquals(1, opener.opens)
        loader.load(user, 16)
        assertEquals(2, opener.opens)
        loader.cache.remove(user.cacheKey())
        assertEquals(0, loader.cache.sizeBytes)
    }

    @Test
    fun cacheStaysWithinItsByteBudgetAndEvictsTheLeastRecentlyUsed() = runBlocking {
        val one = 64 * 64 * 4
        val small = ArtworkLoader(opener, ArtworkCache(maxBytes = one * 2 + 100))
        for (i in 1..3) opener["content://picked/$i"] = encode(64, 64)
        for (i in 1..3) small.load(ArtworkSource.UserSelected("content://picked/$i"), 64)
        assertTrue(small.cache.sizeBytes <= one * 2 + 100)
        assertEquals(one * 2, small.cache.sizeBytes)
        val opensBefore = opener.opens
        small.load(ArtworkSource.UserSelected("content://picked/3"), 64) // 残っている
        assertEquals(opensBefore, opener.opens)
        small.load(ArtworkSource.UserSelected("content://picked/1"), 64) // 追い出し済み
        assertEquals(opensBefore + 1, opener.opens)
    }

    @Test
    fun defaultBudgetsMatchTheSpecifiedLimits() {
        assertEquals(20L * 1024 * 1024, ArtworkLimits.MAX_BYTES)
        assertEquals(40_000_000L, ArtworkLimits.MAX_PIXELS)
        assertEquals(2048, ArtworkLimits.MAX_DECODED_EDGE)
        assertEquals(64 * 1024 * 1024, ArtworkLimits.CACHE_BYTES)
    }
}
