package com.audipp.data.local

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import android.util.LruCache
import com.audipp.domain.model.ArtworkLimits
import com.audipp.domain.model.ArtworkRejection
import com.audipp.domain.model.ArtworkSource
import com.audipp.domain.model.PermissionState
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal class ArtworkStream(val input: InputStream, val mimeType: String?)

internal fun interface ArtworkOpener {
    /** 開けない（失効・権限喪失・削除）場合は null か IOException。 */
    fun open(uri: String): ArtworkStream?
}

internal class ContentResolverArtworkOpener(private val resolver: ContentResolver) : ArtworkOpener {
    override fun open(uri: String): ArtworkStream? {
        val parsed = uri.toUri()
        val input = try {
            resolver.openInputStream(parsed)
        } catch (_: SecurityException) {
            null
        } ?: return null
        return ArtworkStream(input, resolver.getType(parsed))
    }
}

/** 表示用 Bitmap の LRU。上限は確保バイト数で数え、既定は 64 MiB。 */
internal class ArtworkCache(maxBytes: Int = ArtworkLimits.CACHE_BYTES) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    val sizeBytes get() = cache.size()
    operator fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }
    fun clear() = cache.evictAll()

    /** 同じ画像の全表示サイズのエントリを落とす。 */
    fun remove(sourceKey: String) {
        for (key in cache.snapshot().keys) if (key.startsWith("$sourceKey#")) cache.remove(key)
    }
}

internal sealed interface ArtworkLoad {
    class Loaded(val bitmap: Bitmap) : ArtworkLoad
    data class Rejected(val reason: ArtworkRejection) : ArtworkLoad
    data object Unreadable : ArtworkLoad
}

internal class ArtworkLoader(
    private val opener: ArtworkOpener,
    val cache: ArtworkCache = ArtworkCache(),
    maxConcurrentReads: Int = MAX_CONCURRENT_READS,
) {
    // 1 枚が最大 20 MiB のバッファになるので、読込とデコードの同時実行数を絞ってヒープを守る。
    private val permits = Semaphore(maxConcurrentReads)

    /** バイト数・種別・ヘッダー寸法だけを検査する。デコードしない。 */
    suspend fun inspect(source: ArtworkSource): ArtworkLoad? = permits.withPermit {
        withContext(Dispatchers.IO) {
            when (val read = read(source)) {
                is Read.Failed -> read.result
                is Read.Bytes -> bounds(read.bytes).let { if (it is Bounds.Rejected) ArtworkLoad.Rejected(it.reason) else null }
            }
        }
    }

    /**
     * `targetEdgePx` 以上を保てる最大の縮小率で、長辺 2048 px 以下にデコードする。
     * キャッシュは表示サイズ（`targetEdgePx`）ごとに持ち、ヒット時は画像を読み直さない。
     */
    suspend fun load(source: ArtworkSource, targetEdgePx: Int): ArtworkLoad = withContext(Dispatchers.IO) {
        require(targetEdgePx > 0)
        if (source.display != PermissionState.ALLOWED) {
            return@withContext ArtworkLoad.Rejected(ArtworkRejection.DISPLAY_NOT_ALLOWED)
        }
        val key = "${source.cacheKey()}#$targetEdgePx"
        cache[key]?.let { return@withContext ArtworkLoad.Loaded(it) }
        permits.withPermit {
            val bytes = when (val read = read(source)) {
                is Read.Failed -> return@withContext read.result
                is Read.Bytes -> read.bytes
            }
            val bounds = when (val b = bounds(bytes)) {
                is Bounds.Rejected -> return@withContext ArtworkLoad.Rejected(b.reason)
                is Bounds.Ok -> b
            }
            var sample = 1
            val longEdge = maxOf(bounds.width, bounds.height)
            while (longEdge / sample > ArtworkLimits.MAX_DECODED_EDGE) sample *= 2
            while (longEdge / (sample * 2) >= targetEdgePx) sample *= 2
            currentCoroutineContext().ensureActive()
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@withContext ArtworkLoad.Rejected(ArtworkRejection.CORRUPT)
            cache.put(key, bitmap)
            ArtworkLoad.Loaded(bitmap)
        }
    }

    private sealed interface Read {
        class Bytes(val bytes: ByteArray) : Read
        class Failed(val result: ArtworkLoad) : Read
    }

    private suspend fun read(source: ArtworkSource): Read {
        if (source.display != PermissionState.ALLOWED) return Read.Failed(ArtworkLoad.Rejected(ArtworkRejection.DISPLAY_NOT_ALLOWED))
        val embedded = source as? ArtworkSource.Embedded
        if (embedded != null && embedded.length > ArtworkLimits.MAX_BYTES) {
            return Read.Failed(ArtworkLoad.Rejected(ArtworkRejection.TOO_LARGE))
        }
        val stream = try {
            opener.open(source.location())
        } catch (_: IOException) {
            null
        } ?: return Read.Failed(ArtworkLoad.Unreadable)
        val bytes = try {
            stream.input.use { input ->
                if (embedded != null) {
                    var skipped = 0L
                    while (skipped < embedded.offset) {
                        val n = input.skip(embedded.offset - skipped)
                        if (n <= 0) return Read.Failed(ArtworkLoad.Rejected(ArtworkRejection.CORRUPT))
                        skipped += n
                    }
                }
                val limit = embedded?.length ?: (ArtworkLimits.MAX_BYTES + 1)
                input.readUpTo(limit)
            }
        } catch (_: IOException) {
            return Read.Failed(ArtworkLoad.Unreadable)
        }
        currentCoroutineContext().ensureActive()
        if (bytes.size > ArtworkLimits.MAX_BYTES) return Read.Failed(ArtworkLoad.Rejected(ArtworkRejection.TOO_LARGE))
        if (embedded != null && bytes.size.toLong() != embedded.length) {
            return Read.Failed(ArtworkLoad.Rejected(ArtworkRejection.CORRUPT))
        }
        val sniffed = sniff(bytes) ?: return Read.Failed(ArtworkLoad.Rejected(ArtworkRejection.UNSUPPORTED_TYPE))
        val declared = (embedded?.mimeType ?: stream.mimeType)?.lowercase()?.takeIf { it != "application/octet-stream" }
        if (declared != null && declared.replace("image/jpg", "image/jpeg") != sniffed) {
            return Read.Failed(ArtworkLoad.Rejected(ArtworkRejection.TYPE_MISMATCH))
        }
        return Read.Bytes(bytes)
    }

    // InputStream.readNBytes は API 33 以降のため使えない。
    private fun InputStream.readUpTo(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val n = read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private sealed interface Bounds {
        class Ok(val width: Int, val height: Int) : Bounds
        class Rejected(val reason: ArtworkRejection) : Bounds
    }

    private fun bounds(bytes: ByteArray): Bounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return Bounds.Rejected(ArtworkRejection.CORRUPT)
        if (options.outWidth.toLong() * options.outHeight > ArtworkLimits.MAX_PIXELS) {
            return Bounds.Rejected(ArtworkRejection.TOO_MANY_PIXELS)
        }
        return Bounds.Ok(options.outWidth, options.outHeight)
    }

    private fun sniff(b: ByteArray): String? = when {
        b.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        b.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
        b.startsWith('G'.code, 'I'.code, 'F'.code, '8'.code) -> "image/gif"
        b.size >= 12 && b.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte() -> "image/webp"
        else -> null
    }

    private fun ByteArray.startsWith(vararg prefix: Int) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}

internal const val MAX_CONCURRENT_READS = 2

internal fun ArtworkSource.location(): String = when (this) {
    is ArtworkSource.Embedded -> documentUri
    is ArtworkSource.UserSelected -> documentUri
    is ArtworkSource.External -> localUri
}

internal fun ArtworkSource.cacheKey(): String = when (this) {
    is ArtworkSource.Embedded -> "e:$documentUri:$offset:$length"
    is ArtworkSource.UserSelected -> "u:$documentUri"
    is ArtworkSource.External -> "x:$localUri"
}
