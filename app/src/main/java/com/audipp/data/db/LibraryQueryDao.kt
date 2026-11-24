package com.audipp.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@Dao
abstract class LibraryQueryDao {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observePages(queries: Flow<LibraryQuery>): Flow<LibraryPage> =
        queries.flatMapLatest { query ->
            observeRows(
                query.keyword.trim(),
                query.title.exactValue, query.title == LibraryFilter.Unknown,
                query.album.exactValue, query.album == LibraryFilter.Unknown,
                query.artist.exactValue, query.artist == LibraryFilter.Unknown,
                query.genre.exactValue, query.genre == LibraryFilter.Unknown,
                query.year.exactValue, query.year == LibraryFilter.Unknown,
                query.format.exactValue, query.format == LibraryFilter.Unknown,
                query.albumId.exactValue, query.albumId == LibraryFilter.Unknown,
                query.folder.exactValue?.rootId, query.folder.exactValue?.path, query.folder == LibraryFilter.Unknown,
                query.sort.name, query.descending, query.limit, query.offset,
            ).map { rows ->
                LibraryPage(query, rows.mapNotNull { it.item }, rows.first().total)
            }
        }.buffer(0)

    // The outer join keeps the count available even when the requested page is empty.
    @Query(
        """
        $ITEMS
        , matched AS (
            SELECT items.*, CASE :sort
                WHEN 'TITLE' THEN title WHEN 'ALBUM' THEN album WHEN 'ARTIST' THEN artist
                WHEN 'GENRE' THEN genre WHEN 'YEAR' THEN year WHEN 'FORMAT' THEN format
                WHEN 'TRACK_NUMBER' THEN CASE WHEN disc_no IS NOT NULL OR track_no IS NOT NULL THEN
                    printf('%010d%010d', COALESCE(disc_no, 2147483647), COALESCE(track_no, 2147483647)) END
            END AS sort_value
            FROM items
            WHERE (:unknownTitle = 0 OR title IS NULL) AND (:title IS NULL OR title = :title COLLATE NOCASE)
              AND (:unknownAlbum = 0 OR album IS NULL) AND (:album IS NULL OR album = :album COLLATE NOCASE)
              AND (:unknownArtist = 0 OR artist IS NULL)
              AND (:artist IS NULL OR artist = :artist COLLATE NOCASE OR (NOT variant_tags AND EXISTS (
                   SELECT 1 FROM credit_names WHERE track_id = items.track_id AND name = :artist COLLATE NOCASE
              )))
              AND (:unknownGenre = 0 OR genre IS NULL) AND (:genre IS NULL OR genre = :genre COLLATE NOCASE)
              AND (:unknownYear = 0 OR year IS NULL) AND (:year IS NULL OR year = :year)
              AND (:unknownFormat = 0 OR format IS NULL) AND (:format IS NULL OR format = :format COLLATE NOCASE)
              AND (:unknownAlbumId = 0 OR album_id IS NULL) AND (:albumId IS NULL OR album_id = :albumId)
              AND (:unknownFolder = 0 OR folder IS NULL)
              AND (:folderPath IS NULL OR (root_id IS :folderRoot AND folder = :folderPath))
              AND (:keyword = '' OR instr(lower(title), lower(:keyword)) > 0
                   OR instr(lower(album), lower(:keyword)) > 0 OR instr(lower(artist), lower(:keyword)) > 0
                   OR instr(lower(genre), lower(:keyword)) > 0 OR instr(CAST(year AS TEXT), :keyword) > 0
                   OR instr(lower(format), lower(:keyword)) > 0 OR (NOT variant_tags AND EXISTS (
                       SELECT 1 FROM credit_names
                       WHERE track_id = items.track_id AND instr(lower(name), lower(:keyword)) > 0
                   )))
        ), page AS (
            SELECT * FROM matched
            ORDER BY $PAGE_ORDER
            LIMIT :limit OFFSET :offset
        )
        SELECT total, variant_id, track_id, album_id, title, album, artist, genre, year, format
        FROM (SELECT COUNT(*) AS total FROM matched) LEFT JOIN page ON 1 = 1
        ORDER BY $PAGE_ORDER
        """,
    )
    protected abstract fun observeRows(
        keyword: String,
        title: String?, unknownTitle: Boolean,
        album: String?, unknownAlbum: Boolean,
        artist: String?, unknownArtist: Boolean,
        genre: String?, unknownGenre: Boolean,
        year: Int?, unknownYear: Boolean,
        format: String?, unknownFormat: Boolean,
        albumId: String?, unknownAlbumId: Boolean,
        folderRoot: String?, folderPath: String?, unknownFolder: Boolean,
        sort: String, descending: Boolean, limit: Int, offset: Int,
    ): Flow<List<QueryRow>>

    protected data class QueryRow(val total: Long, @Embedded val item: LibraryItem?)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeGroups(queries: Flow<LibraryGroupQuery>): Flow<LibraryGroupPage> =
        queries.flatMapLatest { query ->
            observeGroupRows(query.grouping.name, query.keyword.trim(), query.descending, query.limit, query.offset)
                .map { rows -> LibraryGroupPage(query, rows.mapNotNull { it.group }, rows.first().total) }
        }.buffer(0)

    // Artists and formats compare like the exact filters (ASCII case-insensitive); folders compare exactly.
    @Query(
        """
        $ITEMS
        , keyed AS (
            SELECT variant_id,
                   CASE WHEN :grouping = 'ALBUM' THEN album_id END AS album_id,
                   CASE WHEN :grouping = 'FOLDER' AND folder IS NOT NULL THEN root_id END AS root_id,
                   CASE :grouping WHEN 'ALBUM' THEN album WHEN 'ARTIST' THEN artist
                                  WHEN 'FORMAT' THEN format WHEN 'FOLDER' THEN folder END AS value
            FROM items
        ), grouped AS (
            SELECT k.album_id, k.root_id,
                   CASE WHEN :grouping != 'ALBUM' THEN MIN(k.value)
                        WHEN k.album_id IS NULL THEN NULL
                        ELSE COALESCE(NULLIF(TRIM(al.title), ''), MIN(k.value)) END AS name,
                   CASE WHEN k.album_id IS NOT NULL THEN NULLIF(TRIM(al.album_artist), '') END AS album_artist,
                   COUNT(*) AS tracks, MIN(k.variant_id) AS first_variant_id
            FROM keyed k LEFT JOIN albums al ON al.album_id = k.album_id
            GROUP BY k.album_id, k.root_id,
                     CASE :grouping WHEN 'ALBUM' THEN NULL WHEN 'FOLDER' THEN k.value ELSE lower(k.value) END
        ), matched AS (
            SELECT * FROM grouped WHERE :keyword = '' OR instr(lower(name), lower(:keyword)) > 0
        ), page AS (
            SELECT * FROM matched
            ORDER BY $GROUP_ORDER
            LIMIT :limit OFFSET :offset
        )
        SELECT total, album_id, root_id, name, album_artist, tracks, first_variant_id
        FROM (SELECT COUNT(*) AS total FROM matched) LEFT JOIN page ON 1 = 1
        ORDER BY $GROUP_ORDER
        """,
    )
    protected abstract fun observeGroupRows(
        grouping: String, keyword: String, descending: Boolean, limit: Int, offset: Int,
    ): Flow<List<GroupRow>>

    protected data class GroupRow(val total: Long, @Embedded val group: LibraryGroup?)
}

private val <T> LibraryFilter<T>.exactValue: T?
    get() = (this as? LibraryFilter.Exact)?.value

/** Local Variants with the values the library lists show and filter on; missing values stay NULL. */
private const val ITEMS = """
    WITH credit_names AS (
        SELECT ta.track_id, ta.role, ta.ordinal, NULLIF(TRIM(a.name), '') AS name
        FROM track_artists ta JOIN artists a ON a.artist_id = ta.artist_id
    ), metadata AS (
        SELECT v.variant_id, v.track_id, v.album_id, v.codec, v.disc_no, v.track_no,
               v.local_folder AS folder, ls.root_id,
               v.local_title, v.local_album, v.local_artist, v.local_container,
               v.local_container IS NOT NULL OR v.local_title IS NOT NULL
                   OR v.local_album IS NOT NULL OR v.local_artist IS NOT NULL
                   OR v.local_album_artist IS NOT NULL OR v.local_pcm_storage_bits IS NOT NULL
                   OR v.local_pcm_floating_point IS NOT NULL OR v.local_artwork_ref IS NOT NULL AS variant_tags,
               t.title AS track_title, t.artist_display, t.genre, t.year, a.title AS album_title
        FROM track_variants v
        JOIN sources s ON s.source_id = v.source_id AND s.kind = 'LOCAL'
        JOIN tracks t ON t.track_id = v.track_id
        LEFT JOIN albums a ON a.album_id = v.album_id
        LEFT JOIN local_scan_sources ls ON ls.source_id = v.source_id
    ), items AS (
        SELECT variant_id, track_id, album_id, variant_tags, disc_no, track_no, folder, root_id,
               NULLIF(TRIM(CASE WHEN variant_tags THEN local_title ELSE track_title END), '') AS title,
               NULLIF(TRIM(CASE WHEN variant_tags THEN local_album ELSE album_title END), '') AS album,
               CASE WHEN variant_tags THEN NULLIF(TRIM(local_artist), '')
                    ELSE COALESCE(NULLIF(TRIM(artist_display), ''), (
                        SELECT name FROM credit_names
                        WHERE track_id = metadata.track_id AND name IS NOT NULL
                        ORDER BY role, ordinal LIMIT 1
                    ))
               END AS artist,
               NULLIF(TRIM(genre), '') AS genre, year,
               COALESCE(NULLIF(TRIM(local_container), ''), NULLIF(TRIM(codec), '')) AS format
        FROM metadata
    )
"""

private const val GROUP_ORDER = """
    name IS NULL,
    CASE WHEN :descending = 0 THEN name END COLLATE NOCASE ASC,
    CASE WHEN :descending = 1 THEN name END COLLATE NOCASE DESC,
    album_id, root_id, first_variant_id
"""

private const val PAGE_ORDER = """
    sort_value IS NULL,
    CASE WHEN :descending = 0 THEN sort_value END COLLATE NOCASE ASC,
    CASE WHEN :descending = 1 THEN sort_value END COLLATE NOCASE DESC,
    variant_id
"""
