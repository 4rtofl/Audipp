package com.audipp.data.db

import androidx.room.ColumnInfo

enum class LibraryField { TITLE, ALBUM, ARTIST, GENRE, YEAR, FORMAT }

sealed interface LibraryFilter<out T> {
    data object Any : LibraryFilter<Nothing>
    data object Unknown : LibraryFilter<Nothing>
    data class Exact<T>(val value: T) : LibraryFilter<T>
}

data class LibraryQuery(
    val keyword: String = "",
    val title: LibraryFilter<String> = LibraryFilter.Any,
    val album: LibraryFilter<String> = LibraryFilter.Any,
    val artist: LibraryFilter<String> = LibraryFilter.Any,
    val genre: LibraryFilter<String> = LibraryFilter.Any,
    val year: LibraryFilter<Int> = LibraryFilter.Any,
    val format: LibraryFilter<String> = LibraryFilter.Any,
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
