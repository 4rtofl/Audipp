package com.audipp.data.local

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.audipp.domain.model.ArtworkRejection
import com.audipp.domain.model.ArtworkSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 実 Bitmap デコーダー（API 23 / 36）での上限・downsample・拒否・キャッシュ予算。 */
@RunWith(AndroidJUnit4::class)
class ArtworkDecodeTest {
    private val files = mutableMapOf<String, Pair<ByteArray, String?>>()
    private var opens = 0
    private val opener = ArtworkOpener { uri ->
        opens++
        files[uri]?.let { (bytes, mime) -> ArtworkStream(bytes.inputStream(), mime) }
    }
    private val picked = ArtworkSource.UserSelected("picked")

    private fun encode(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
    }

    private fun pngDeclaring(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            val body = type.toByteArray(Charsets.US_ASCII) + data
            out.write(body)
            out.write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(body) }.value.toInt()).array())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array())
        chunk("IDAT", byteArrayOf(0x78, 0x9c.toByte(), 0x63, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun bitmap(result: ArtworkLoad) = (result as ArtworkLoad.Loaded).bitmap
    private fun reason(result: ArtworkLoad) = (result as ArtworkLoad.Rejected).reason

    @Test
    fun downsamplesPngAndJpegToTheLongEdgeLimit() = runBlocking {
        val loader = ArtworkLoader(opener)
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG)) {
            files["picked"] = encode(4100, 64, format) to null
            val result = bitmap(loader.load(picked, 4096))
            assertEquals(1025, result.width)
            assertTrue(result.width <= 2048)
        }
        files["picked"] = encode(4100, 64) to null
        assertEquals(512, bitmap(loader.load(picked, 300)).width)
    }

    @Test
    fun rejectsOversizedDimensionsFromTheHeaderAndDisguisedOrCorruptContent() = runBlocking {
        val loader = ArtworkLoader(opener)
        files["picked"] = pngDeclaring(8000, 5001) to null
        assertEquals(ArtworkRejection.TOO_MANY_PIXELS, reason(loader.load(picked, 512)))
        files["picked"] = encode(8, 8, Bitmap.CompressFormat.JPEG) to "image/png"
        assertEquals(ArtworkRejection.TYPE_MISMATCH, reason(loader.load(picked, 64)))
        files["picked"] = ByteArray(64) { it.toByte() } to null
        assertEquals(ArtworkRejection.UNSUPPORTED_TYPE, reason(loader.load(picked, 64)))
        files["picked"] = encode(8, 8).copyOf(10) to null
        assertEquals(ArtworkRejection.CORRUPT, reason(loader.load(picked, 64)))
    }

    @Test
    fun cacheNeverExceedsItsBudgetOnTheRealBitmapSize() = runBlocking {
        val one = 64 * 64 * 4
        val loader = ArtworkLoader(opener, ArtworkCache(maxBytes = one * 2))
        for (i in 1..4) {
            files["p$i"] = encode(64, 64) to null
            loader.load(ArtworkSource.UserSelected("p$i"), 64)
            assertTrue(loader.cache.sizeBytes <= one * 2)
        }
        val before = opens
        loader.load(ArtworkSource.UserSelected("p4"), 64)
        assertEquals(before, opens)
    }
}
