package com.audipp.data.db

import androidx.room.ColumnInfo

/** Sort keys. TRACK_NUMBER orders by disc then track number, with unknown numbers after known ones. */
enum class LibraryField { TITLE, ALBUM, ARTIST, GENRE, YEAR, FORMAT, TRACK_NUMBER }

sealed interface LibraryFilter<out T> {
    data object Any : LibraryFilter<Nothing>
    data object Unknown : LibraryFilter<Nothing>
    data class Exact<T>(val value: T) : LibraryFilter<T>
}

/** A folder is only meaningful inside the scan root it was found in; `path` is the provider's document ID. */
data class LibraryFolder(val rootId: String?, val path: String)

data class LibraryQuery(
    val keyword: String = "",
    val title: LibraryFilter<String> = LibraryFilter.Any,
    val album: LibraryFilter<String> = LibraryFilter.Any,
    val artist: LibraryFilter<String> = LibraryFilter.Any,
    val genre: LibraryFilter<String> = LibraryFilter.Any,
    val year: LibraryFilter<Int> = LibraryFilter.Any,
    val format: LibraryFilter<String> = LibraryFilter.Any,
    /** Unknown selects tracks that album classification has not assigned to an album. */
    val albumId: LibraryFilter<String> = LibraryFilter.Any,
    /** Unknown selects tracks without a recorded scan folder. */
    val folder: LibraryFilter<LibraryFolder> = LibraryFilter.Any,
    val sort: LibraryField = LibraryField.TITLE,
    val descending: Boolean = false,
    val offset: Int = 0,
    val limit: Int = 50,
) {
    init {
        require(offset >= 0)
        require(limit in 1..100)
    }
}

data class LibraryItem(
    @ColumnInfo(name = "variant_id") val variantId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "album_id") val albumId: String?,
    val title: String?,
    val album: String?,
    val artist: String?,
    val genre: String?,
    val year: Int?,
    val format: String?,
)

data class LibraryPage(val query: LibraryQuery, val items: List<LibraryItem>, val total: Long)

/** Lists that group the same local tracks that [LibraryQuery] pages. */
enum class LibraryGrouping { ALBUM, ARTIST, FOLDER, FORMAT }

data class LibraryGroupQuery(
    val grouping: LibraryGrouping,
    /** Matches the group's name only. */
    val keyword: String = "",
    val descending: Boolean = false,
    val offset: Int = 0,
    val limit: Int = 50,
) {
    init {
        require(offset >= 0)
        require(limit in 1..100)
    }
}

/**
 * One group. A null [name] is the group of tracks whose value is unknown (for albums: not classified).
 * Albums are told apart by [albumId] and folders by [rootId], so same-named groups stay separate.
 */
data class LibraryGroup(
    @ColumnInfo(name = "album_id") val albumId: String?,
    @ColumnInfo(name = "root_id") val rootId: String?,
    val name: String?,
    @ColumnInfo(name = "album_artist") val albumArtist: String?,
    val tracks: Long,
    /** A member Variant, so album rows can show that Variant's artwork. */
    @ColumnInfo(name = "first_variant_id") val firstVariantId: String,
)

data class LibraryGroupPage(val query: LibraryGroupQuery, val groups: List<LibraryGroup>, val total: Long)
