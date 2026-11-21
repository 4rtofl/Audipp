package com.audipp.server

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal const val SYNC_MAX_BODY_BYTES = 1024 * 1024
private const val MAX_EVENTS = 100
internal class SyncConflict(val code: String) : RuntimeException()
internal class SyncBusy : RuntimeException()

@Serializable
internal data class SyncBatchResult(val results: List<SyncEventResult>)

@Serializable
internal data class SyncEventResult(val event_id: String, val server_revision: Long)

internal class SyncBatch(val deviceId: UUID, val events: List<SyncEvent>)

internal class SyncEvent(
    val id: UUID,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val baseRevision: Long,
    val historyEpoch: Long,
    val payload: String?,
    val syncAllowedSource: Boolean,
    val requestHash: ByteArray,
)

private val syncJson = JsonMapper.builder(
    JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(6).build())
        .build()
)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
    .build()

internal fun parseSyncBatch(json: String): SyncBatch {
    val root = syncJson.readTree(json)
    root.requireFields("device_id", "events")
    val deviceId = canonicalUuid(root["device_id"].string())
    val events = root["events"]
    require(events.isArray && !events.isEmpty)
    if (events.size() > MAX_EVENTS) throw PayloadTooLarge()
    val parsed = events.map { parseSyncEvent(it, deviceId) }
    val consentCount = parsed.count { it.entityType == "consent" }
    require(consentCount == 0 || consentCount == parsed.size)
    return SyncBatch(deviceId, parsed)
}

private fun parseSyncEvent(node: JsonNode, deviceId: UUID): SyncEvent {
    node.requireFields("event_id", "entity_type", "entity_id", "operation", "base_revision", "history_epoch", "payload")
    val id = canonicalUuid(node["event_id"].string())
    val type = node["entity_type"].string()
    val entityId = node["entity_id"].string()
    when (type) {
        "consent" -> require(entityId == "cloud_sync" || entityId == "history_sync")
        "setting" -> require(entityId in setOf("theme", "visualizer_enabled", "visualizer_fps"))
        "playlist", "queue", "profile", "audio_preset", "history" -> canonicalUuid(entityId)
        else -> throw IllegalArgumentException()
    }
    val operation = node["operation"].string()
    val baseRevision = node["base_revision"].nonNegativeLong()
    val historyEpoch = node["history_epoch"].nonNegativeLong()
    require(type == "history" || historyEpoch == 0L)
    val payload = node["payload"]
    val allowedSource = when (operation) {
        "upsert" -> validatePayload(type, entityId, payload)
        "delete" -> {
            require(type != "history" && type != "consent" && payload.isNull)
            true
        }
        else -> throw IllegalArgumentException()
    }
    // Persist the digest separately so a deleted history receipt can still be replayed.
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(deviceId.toString().toByteArray())
    val hash = digest.digest(syncJson.writeValueAsBytes(node))
    return SyncEvent(
        id, type, entityId, operation, baseRevision, historyEpoch,
        payload.takeUnless { it.isNull }?.let(syncJson::writeValueAsString), allowedSource, hash,
    )
}

private fun validatePayload(type: String, entityId: String, payload: JsonNode): Boolean {
    when (type) {
        "consent" -> {
            payload.requireFields("version", "granted")
            require(payload["version"].nonNegativeLong() == 1L && payload["granted"].isBoolean)
        }
        "setting" -> {
            payload.requireFields("value")
            val value = payload["value"]
            when (entityId) {
                "theme" -> require(value.string() in setOf("SYSTEM", "DARK", "LIGHT"))
                "visualizer_enabled" -> require(value.isBoolean)
                "visualizer_fps" -> require(value.nonNegativeLong() in setOf(15L, 30L, 60L))
            }
        }
        "playlist", "queue" -> {
            if (type == "playlist") {
                payload.requireFields("name", "tracks")
                validateName(payload["name"])
            } else {
                payload.requireFields("tracks")
            }
            val tracks = payload["tracks"]
            require(tracks.isArray)
            var allowedSource = true
            for (track in tracks) {
                if (!validateTrack(track)) allowedSource = false
            }
            return allowedSource
        }
        "profile", "audio_preset" -> {
            payload.requireFields("name")
            validateName(payload["name"])
        }
        "history" -> {
            payload.requireFields("track", "started_at", "listened_ms")
            val timestamp = payload["started_at"].string()
            require(timestamp.endsWith("Z"))
            Instant.parse(timestamp)
            require(payload["listened_ms"].nonNegativeLong() > 0L)
            return validateTrack(payload["track"])
        }
    }
    return true
}

private fun validateTrack(track: JsonNode): Boolean {
    track.requireFields("track_id", "source")
    canonicalUuid(track["track_id"].string())
    val source = track["source"].string()
    require(source == "local" || source == "provider")
    return source == "local"
}

private val externalReference = Regex(
    """(?i)(?<![a-z0-9+.-])[a-z][a-z0-9+.-]*://|\b(?:(?:content|file):/|data:[a-z0-9!#$&^_.+-]+/)""" +
        """|\A *(?:bearer|basic) +(?=[a-z0-9._~+/-]{8})(?=[a-z]*[0-9._~+/=-])[a-z0-9._~+/-]+=* *\z""",
)

private fun validateName(node: JsonNode) {
    val name = node.string()
    require(name.isNotBlank() && name.none { it.isISOControl() } && !externalReference.containsMatchIn(name))
    name.encodeToByteArray(throwOnInvalidSequence = true)
}

private fun JsonNode.requireFields(vararg fields: String) {
    require(isObject && size() == fields.size && fields.all { has(it) })
}

private fun JsonNode.string(): String {
    require(isTextual)
    return textValue()
}

private fun JsonNode.nonNegativeLong(): Long {
    require(isIntegralNumber && canConvertToLong() && longValue() >= 0)
    return longValue()
}
