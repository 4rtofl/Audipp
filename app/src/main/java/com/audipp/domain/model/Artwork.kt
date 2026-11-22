package com.audipp.domain.model

enum class ArtworkOrigin { EMBEDDED, USER_SELECTED, EXTERNAL }

object ArtworkLimits {
    const val MAX_BYTES = 20L * 1024 * 1024
    const val MAX_PIXELS = 40_000_000L
    const val MAX_DECODED_EDGE = 2048
    const val CACHE_BYTES = 64 * 1024 * 1024
}

enum class ArtworkRejection {
    TOO_LARGE, TOO_MANY_PIXELS, CORRUPT, TYPE_MISMATCH, UNSUPPORTED_TYPE, DISPLAY_NOT_ALLOWED,
}

/**
 * 画像の出典。表示許可と再配布許可は別の状態で、表示許可を変えても再配布許可は変わらない。
 * 埋込と本人指定の画像は端末内の表示だけを前提にし、再配布は常に未確認とする。
 */
sealed interface ArtworkSource {
    val origin: ArtworkOrigin
    val display: PermissionState
    val redistribution: PermissionState

    /** 同期・共有・エクスポートの payload に画像本体を載せてよいのは、再配布が明示的に許可された出典だけ。 */
    val mayAttachToSharedPayload: Boolean get() = redistribution == PermissionState.ALLOWED

    /** 差替えとして保存できる出典（元の埋込画像は音源ファイル側に残るため含めない）。 */
    sealed interface Replacement : ArtworkSource

    data class Embedded(val documentUri: String, val offset: Long, val length: Long, val mimeType: String) : ArtworkSource {
        init { require(documentUri.isNotBlank() && offset >= 0 && length > 0 && mimeType.isNotBlank()) }

        override val origin get() = ArtworkOrigin.EMBEDDED
        override val display get() = PermissionState.ALLOWED
        override val redistribution get() = PermissionState.UNREVIEWED
        override fun toString() = "Embedded(redacted)"

        companion object {
            /** `local_artwork_ref` の `embedded:<offset>:<length>:<mime>` を解釈する。 */
            fun parse(documentUri: String, ref: String): Embedded? {
                val parts = ref.split(':', limit = 4)
                if (parts.size != 4 || parts[0] != "embedded") return null
                val offset = parts[1].toLongOrNull() ?: return null
                val length = parts[2].toLongOrNull() ?: return null
                if (offset < 0 || length <= 0) return null
                return Embedded(documentUri, offset, length, parts[3])
            }
        }
    }

    data class UserSelected(val documentUri: String) : Replacement {
        init { require(documentUri.isNotBlank()) }

        override val origin get() = ArtworkOrigin.USER_SELECTED
        override val display get() = PermissionState.ALLOWED
        override val redistribution get() = PermissionState.UNREVIEWED
        override fun toString() = "UserSelected(redacted)"
    }

    /** 取得済みで端末内に保存された外部画像。取得そのもの（通信）はこの型の外にある。 */
    data class External(
        val localUri: String,
        val providerKey: String,
        override val display: PermissionState = PermissionState.UNREVIEWED,
        override val redistribution: PermissionState = PermissionState.UNREVIEWED,
    ) : Replacement {
        init { require(localUri.isNotBlank() && providerKey.isNotBlank()) }

        override val origin get() = ArtworkOrigin.EXTERNAL
        override fun toString() = "External(provider=$providerKey, display=$display, redistribution=$redistribution)"
    }
}

enum class PlaceholderReason { NO_ARTWORK, EXPIRED, UNAVAILABLE, DISPLAY_NOT_ALLOWED, REJECTED }

sealed interface ArtworkChange {
    data object Applied : ArtworkChange
    data class Rejected(val reason: ArtworkRejection) : ArtworkChange
    data object Unreadable : ArtworkChange
}
