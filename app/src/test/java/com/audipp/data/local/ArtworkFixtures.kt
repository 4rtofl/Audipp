package com.audipp.data.local

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

internal fun encode(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(0xFF336699.toInt())
    return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
}

/** IHDR だけが寸法を宣言する PNG。画素は持たないので、巨大寸法の宣言をメモリ確保なしで作れる。 */
internal fun pngDeclaring(width: Int, height: Int): ByteArray {
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

internal class FakeOpener(private val files: MutableMap<String, Pair<ByteArray, String?>> = mutableMapOf()) : ArtworkOpener {
    var opens = 0
    operator fun set(uri: String, bytes: ByteArray) { files[uri] = bytes to null }
    fun put(uri: String, bytes: ByteArray, mime: String?) { files[uri] = bytes to mime }
    fun remove(uri: String) = files.remove(uri)

    override fun open(uri: String): ArtworkStream? {
        opens++
        val (bytes, mime) = files[uri] ?: return null
        return ArtworkStream(bytes.inputStream(), mime)
    }
}

internal class ZeroStream(private var remaining: Long) : InputStream() {
    override fun read(): Int = if (remaining-- > 0) 0 else -1
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = minOf(len.toLong(), remaining).toInt()
        remaining -= n
        return n
    }
}
