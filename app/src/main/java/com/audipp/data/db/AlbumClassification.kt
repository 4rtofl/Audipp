package com.audipp.data.db

import androidx.room.ColumnInfo
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** Tags and location that one local Variant contributes to album grouping. */
internal data class AlbumInput(
    @ColumnInfo(name = "variant_id") val variantId: String,
    @ColumnInfo(name = "album_id") val albumId: String?,
    @ColumnInfo(name = "disc_no") val discNo: Int?,
    @ColumnInfo(name = "track_no") val trackNo: Int?,
    val title: String?,
    @ColumnInfo(name = "album_artist") val albumArtist: String?,
    @ColumnInfo(name = "release_namespace") val releaseNamespace: String?,
    @ColumnInfo(name = "release_id") val releaseId: String?,
    @ColumnInfo(name = "root_id") val rootId: String?,
    val folder: String,
)

enum class AlbumCandidateReason {
    /** Variants sharing a release ID or album folder disagree on album title or album artist. */
    CONFLICTING_TAGS,

    /** One album folder has missing and present disc numbers together, or repeats a disc and track. */
    DISC_CONFLICT,

    /** Albums without a release ID share a title and album artist across folders or roots. */
    SAME_TITLE,
}

/** A grouping that was not confirmed automatically. Confirming it is a user merge of [variantIds]. */
data class AlbumCandidate(
    val reason: AlbumCandidateReason,
    val title: String?,
    val albumArtist: String?,
    val variantIds: List<String>,
    /** Albums already formed automatically from these Variants; empty when the Variants are unclassified. */
    val albumIds: List<String>,
)

internal data class AlbumClassification(
    val albums: List<AlbumEntity>,
    /** Every input Variant: its automatic album, or null when the grouping is ambiguous or untagged. */
    val assignments: Map<String, String?>,
    val candidates: List<AlbumCandidate>,
)

internal fun classifyAlbums(rows: List<AlbumInput>): AlbumClassification {
    val assignments = rows.associate { it.variantId to null as String? }.toMutableMap()
    val albums = mutableListOf<AlbumEntity>()
    val candidates = mutableListOf<AlbumCandidate>()
    val folderAlbums = mutableListOf<Pair<AlbumEntity, List<AlbumInput>>>()

    val groups = rows.groupBy { row ->
        val title = normalized(row.title)
        when {
            row.releaseId != null -> listOf("release", row.releaseNamespace, row.releaseId)
            title != null -> listOf("folder", row.rootId.orEmpty(), row.folder, title)
            else -> null
        }
    }
    for ((key, members) in groups) {
        if (key == null) continue
        val ordered = members.sortedWith(TRACK_ORDER)
        val release = key[0] == "release"
        val title = ordered.firstNotNullOfOrNull { it.title?.trim()?.takeIf(String::isNotEmpty) }
        val albumArtist = ordered.firstNotNullOfOrNull { it.albumArtist?.trim()?.takeIf(String::isNotEmpty) }
        val conflict = when {
            members.mapNotNullTo(mutableSetOf()) { normalized(it.albumArtist) }.size > 1 -> AlbumCandidateReason.CONFLICTING_TAGS
            release && members.mapNotNullTo(mutableSetOf()) { normalized(it.title) }.size > 1 -> AlbumCandidateReason.CONFLICTING_TAGS
            // A release ID already identifies the discs, so only folder groups depend on the disc structure.
            !release && !discStructureConsistent(members) -> AlbumCandidateReason.DISC_CONFLICT
            else -> null
        }
        if (conflict != null) {
            candidates += AlbumCandidate(conflict, title, albumArtist, ordered.map { it.variantId }, emptyList())
            continue
        }
        val albumId = "auto-album:" + UUID.nameUUIDFromBytes(key.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        val album = AlbumEntity(
            albumId, title, albumArtist,
            releaseNamespace = if (release) key[1] else null,
            releaseId = if (release) key[2] else null,
        )
        albums += album
        for (member in members) assignments[member.variantId] = albumId
        if (!release) folderAlbums += album to ordered
    }

    // Without an external ID, the same title in another folder may be another disc or another release.
    for (sameName in folderAlbums.groupBy { (album, _) -> normalized(album.title) to normalized(album.albumArtist) }.values) {
        if (sameName.size < 2) continue
        val album = sameName.first().first
        candidates += AlbumCandidate(
            AlbumCandidateReason.SAME_TITLE, album.title, album.albumArtist,
            sameName.flatMap { (_, members) -> members.map { it.variantId } }, sameName.map { it.first.albumId },
        )
    }
    return AlbumClassification(albums, assignments, candidates)
}

private fun discStructureConsistent(members: List<AlbumInput>): Boolean {
    val known = members.count { it.discNo != null }
    if (known != 0 && known != members.size) return false
    val numbered = members.filter { it.trackNo != null }
    return numbered.distinctBy { it.discNo to it.trackNo }.size == numbered.size
}

internal fun normalized(value: String?): String? = value
    ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
    ?.trim()?.replace(WHITESPACE, " ")?.lowercase(Locale.ROOT)
    ?.takeIf(String::isNotEmpty)

private val WHITESPACE = Regex("\\s+")

private val TRACK_ORDER = compareBy<AlbumInput, Int?>(nullsLast()) { it.discNo }
    .thenBy(nullsLast()) { it.trackNo }
    .thenBy { it.variantId }
