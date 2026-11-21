package com.audipp.server

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.sql.Connection
import java.util.UUID

internal suspend fun Database.pushSync(identity: Identity, batch: SyncBatch): SyncBatchResult {
    val context = currentCoroutineContext()
    return transaction { connection ->
        val user = resolve(connection, identity, forUpdate = true)
        device(connection, user, batch.deviceId)
        val userId = UUID.fromString(user.user_id)
        val cloudEnabled by lazy { connection.consentGranted(userId, "cloud_sync") }
        val historyEnabled by lazy { connection.consentGranted(userId, "history_sync") }
        val historyEpoch by lazy { connection.historyEpoch(userId) }

        val results = batch.events.map { event ->
            context.ensureActive()
            val revision = connection.replayedRevision(userId, event) ?: run {
                if (event.entityType != "consent" && (!cloudEnabled || !event.syncAllowedSource)) throw Forbidden()
                if (event.entityType == "history") {
                    if (!historyEnabled) throw Forbidden()
                    if (event.historyEpoch != historyEpoch) throw SyncConflict("history_epoch_conflict")
                    if (connection.historyDeleted(userId, event.entityId)) throw SyncConflict("history_deleted")
                }
                val latest = connection.entityRevision(userId, event)
                if (event.baseRevision != latest || (event.entityType == "history" && latest != 0L)) {
                    throw SyncConflict("revision_conflict")
                }
                connection.insertSyncEvent(userId, batch.deviceId, event)
            }
            SyncEventResult(event.id.toString(), revision)
        }
        SyncBatchResult(results)
    }
}

private fun Connection.consentGranted(userId: UUID, consent: String): Boolean = prepareStatement("""
    SELECT (payload ->> 'granted')::boolean FROM sync_events
    WHERE user_id = ? AND entity_type = 'consent' AND entity_id = ?
    ORDER BY server_revision DESC LIMIT 1
""".trimIndent()).use {
    it.setObject(1, userId)
    it.setString(2, consent)
    it.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
}

private fun Connection.historyEpoch(userId: UUID): Long = prepareStatement(
    "SELECT history_epoch FROM history_epochs WHERE user_id = ?"
).use {
    it.setObject(1, userId)
    it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
}

private fun Connection.historyDeleted(userId: UUID, entityId: String): Boolean = prepareStatement(
    "SELECT 1 FROM history_tombstones WHERE user_id = ? AND entity_id = ?::uuid"
).use {
    it.setObject(1, userId)
    it.setString(2, entityId)
    it.executeQuery().use { rows -> rows.next() }
}

private fun Connection.replayedRevision(userId: UUID, event: SyncEvent): Long? = prepareStatement(
    "SELECT request_sha256, server_revision FROM sync_events WHERE user_id = ? AND event_id = ?"
).use {
    it.setObject(1, userId)
    it.setObject(2, event.id)
    it.executeQuery().use { rows ->
        if (!rows.next()) return null
        if (!rows.getBytes(1).contentEquals(event.requestHash)) throw SyncConflict("event_id_conflict")
        rows.getLong(2)
    }
}

private fun Connection.entityRevision(userId: UUID, event: SyncEvent): Long = prepareStatement("""
    SELECT server_revision FROM sync_events WHERE user_id = ? AND entity_type = ? AND entity_id = ?
    ORDER BY server_revision DESC LIMIT 1
""".trimIndent()).use {
    it.setObject(1, userId)
    it.setString(2, event.entityType)
    it.setString(3, event.entityId)
    it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
}

private fun Connection.insertSyncEvent(userId: UUID, deviceId: UUID, event: SyncEvent): Long = prepareStatement("""
    INSERT INTO sync_events (user_id, event_id, device_id, entity_type, entity_id, operation,
                             base_revision, history_epoch, payload, request_sha256)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING server_revision
""".trimIndent()).use {
    it.setObject(1, userId)
    it.setObject(2, event.id)
    it.setObject(3, deviceId)
    it.setString(4, event.entityType)
    it.setString(5, event.entityId)
    it.setString(6, event.operation)
    it.setLong(7, event.baseRevision)
    it.setLong(8, event.historyEpoch)
    it.setString(9, event.payload)
    it.setBytes(10, event.requestHash)
    it.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
}

// The reauthenticated deletion service owns calls to these writers. Both use the same user lock as push.
internal suspend fun Database.advanceHistoryEpoch(userId: UUID, expectedEpoch: Long): Long = transaction { connection ->
    connection.lockHistoryOwner(userId)
    val current = connection.historyEpoch(userId)
    if (expectedEpoch < current) return@transaction current
    if (expectedEpoch != current) throw SyncConflict("history_epoch_conflict")
    connection.prepareStatement("""
        INSERT INTO history_epochs (user_id, history_epoch) VALUES (?, ?)
        ON CONFLICT (user_id) DO UPDATE
        SET history_epoch = EXCLUDED.history_epoch, server_revision = EXCLUDED.server_revision
    """.trimIndent()).use {
        it.setObject(1, userId)
        it.setLong(2, current + 1)
        it.executeUpdate()
    }
    connection.prepareStatement("""
        UPDATE sync_events SET payload = NULL
        WHERE user_id = ? AND entity_type = 'history' AND history_epoch <= ? AND payload IS NOT NULL
    """.trimIndent()).use {
        it.setObject(1, userId)
        it.setLong(2, current)
        it.executeUpdate()
    }
    current + 1
}

internal suspend fun Database.tombstoneHistory(userId: UUID, entityId: UUID) = transaction { connection ->
    connection.lockHistoryOwner(userId)
    connection.prepareStatement("""
        INSERT INTO history_tombstones (user_id, entity_id) VALUES (?, ?)
        ON CONFLICT (user_id, entity_id) DO NOTHING
    """.trimIndent()).use {
        it.setObject(1, userId)
        it.setObject(2, entityId)
        it.executeUpdate()
    }
    connection.prepareStatement("""
        UPDATE sync_events SET payload = NULL
        WHERE user_id = ? AND entity_type = 'history' AND entity_id = ? AND payload IS NOT NULL
    """.trimIndent()).use {
        it.setObject(1, userId)
        it.setString(2, entityId.toString())
        it.executeUpdate()
    }
    Unit
}

private fun Connection.lockHistoryOwner(userId: UUID) = prepareStatement(
    "SELECT 1 FROM users WHERE user_id = ? AND active FOR UPDATE"
).use {
    it.setObject(1, userId)
    it.executeQuery().use { rows -> if (!rows.next()) throw Forbidden() }
}
