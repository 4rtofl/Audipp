package com.audipp.data.local

import android.graphics.Bitmap
import com.audipp.data.db.ArtworkDao
import com.audipp.data.db.ArtworkOverrideEntity
import com.audipp.domain.model.ArtworkChange
import com.audipp.domain.model.ArtworkOrigin
import com.audipp.domain.model.ArtworkRejection
import com.audipp.domain.model.ArtworkSource
import com.audipp.domain.model.PlaceholderReason

sealed interface Artwork {
    data class Image(val bitmap: Bitmap, val origin: ArtworkOrigin) : Artwork
    data class Placeholder(val reason: PlaceholderReason, val rejection: ArtworkRejection? = null) : Artwork
}

/** 表示側・差替え UI が使う入口。使い方は docs/contracts/artwork-v1.md。 */
class ArtworkProvider internal constructor(private val dao: ArtworkDao, private val loader: ArtworkLoader) {
    val cacheBytes get() = loader.cache.sizeBytes

    /** 差替え画像があればそれを、なければ埋込画像を返す。読めない・許可がない場合は理由つきの placeholder。 */
    suspend fun load(variantId: String, targetEdgePx: Int): Artwork {
        val override = dao.override(variantId)
        val source: ArtworkSource = when {
            override?.expired == true -> return Artwork.Placeholder(PlaceholderReason.EXPIRED)
            override != null -> override.toSource()
            else -> {
                val row = dao.embedded(variantId)
                val uri = row?.documentUri
                val ref = row?.artworkRef
                if (uri == null || ref == null) return Artwork.Placeholder(PlaceholderReason.NO_ARTWORK)
                ArtworkSource.Embedded.parse(uri, ref) ?: return Artwork.Placeholder(PlaceholderReason.NO_ARTWORK)
            }
        }
        return when (val result = loader.load(source, targetEdgePx)) {
            is ArtworkLoad.Loaded -> Artwork.Image(result.bitmap, source.origin)
            is ArtworkLoad.Rejected ->
                if (result.reason == ArtworkRejection.DISPLAY_NOT_ALLOWED) Artwork.Placeholder(PlaceholderReason.DISPLAY_NOT_ALLOWED)
                else Artwork.Placeholder(PlaceholderReason.REJECTED, result.reason)
            ArtworkLoad.Unreadable -> if (override != null) {
                dao.markExpired(variantId)
                Artwork.Placeholder(PlaceholderReason.EXPIRED)
            } else {
                Artwork.Placeholder(PlaceholderReason.UNAVAILABLE)
            }
        }
    }

    /** 画像を検査してから差し替える。拒否・読み取り不能のときは何も保存しない。 */
    suspend fun replace(variantId: String, source: ArtworkSource.Replacement, nowEpochMs: Long): ArtworkChange {
        when (val check = loader.inspect(source)) {
            is ArtworkLoad.Rejected -> return ArtworkChange.Rejected(check.reason)
            ArtworkLoad.Unreadable -> return ArtworkChange.Unreadable
            else -> Unit
        }
        dao.override(variantId)?.let { loader.cache.remove(it.toSource().cacheKey()) }
        dao.save(ArtworkOverrideEntity.from(variantId, source, nowEpochMs))
        return ArtworkChange.Applied
    }

    /** 差替えを取り消して元の埋込画像に戻す。差替えがなければ false。 */
    suspend fun revert(variantId: String): Boolean {
        dao.override(variantId)?.let { loader.cache.remove(it.toSource().cacheKey()) }
        return dao.revert(variantId) > 0
    }
}
