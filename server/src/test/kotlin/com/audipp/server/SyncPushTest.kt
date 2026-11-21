package com.audipp.server

import com.audipp.server.OwnershipTest.Companion.token
import com.audipp.server.OwnershipTest.Companion.verifier
import com.audipp.server.OwnershipTest.Companion.issuer
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.*
import io.ktor.utils.io.ByteReadChannel
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

private const val syncPath = "/v1/sync/batches"
private val trackId = UUID.fromString("62662685-cbf4-44fb-8eaf-0bdcf8862ad8")

internal fun syncEvent(
    type: String, entity: String, payload: String, id: UUID = UUID.randomUUID(),
    base: Long = 0, epoch: Long = 0, operation: String = "upsert",
) = """{"event_id":"$id","entity_type":"$type","entity_id":"$entity","operation":"$operation","base_revision":$base,"history_epoch":$epoch,"payload":$payload}"""

internal fun syncBatch(device: UUID, vararg events: String): String {
    val items = events.joinToString(",")
    return """{"device_id":"$device","events":[$items]}"""
}

class SyncPushTest {
    companion object {
        private lateinit var pg: EmbeddedPostgres
        private lateinit var database: Database

        @JvmStatic @BeforeClass fun startPostgres() {
            pg = EmbeddedPostgres.builder().setPort(0).start()
            database = Database(pg.postgresDatabase)
            runBlocking { database.migrate() }
        }

        @JvmStatic @AfterClass fun stopPostgres() {
            if (::pg.isInitialized) pg.close()
        }
    }

    @Before fun clearSync() =
        execute("TRUNCATE sync_events, history_epochs, history_tombstones, devices, identities, users")

    @Test fun `batch accepts 100 events and exactly 1 MiB UTF-8 including streamed requests`() = testApplication {
        application { ownershipApi(database, verifier) }
        val device = client.register()
        client.enableSync(device)
        val events = Array(101) { profile() }
        assertEquals(100, client.push(device, *events.take(100).toTypedArray()).revisions().size)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.push(device, *events).status)

        val json = syncBatch(device, profile(name = "音楽")).encodeToByteArray()
        val exact = json.copyOf(SYNC_MAX_BODY_BYTES).apply { fill(32, json.size) }
        fun stream(bytes: ByteArray): OutgoingContent = object : OutgoingContent.ReadChannelContent() {
            override val contentType = ContentType.Application.Json
            override fun readFrom() = ByteReadChannel(bytes)
        }
        assertEquals(1, client.sendPush(stream(exact)).revisions().size)
        val oversized = exact + byteArrayOf(32)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.sendPush(oversized).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.sendPush(stream(oversized)).status)
        assertEquals(101L, dataCount())
    }

    @Test fun `preset and playlist names can contain ordinary words and punctuation`() = testApplication {
        application { ownershipApi(database, verifier) }
        val device = client.register()
        client.enableSync(device)
        for (name in listOf("Basic EQ", "Basic 2026-11-21")) {
            val preset = syncEvent("audio_preset", UUID.randomUUID().toString(), """{"name":"$name"}""")
            assertEquals(1, client.push(device, preset).revisions().size)
        }
        for (name in listOf("NFS: Heat OST", "Big Data: Remix", "Data:Remix, Vol 2")) {
            val playlist = syncEvent("playlist", UUID.randomUUID().toString(), """{"name":"$name","tracks":[]}""")
            assertEquals(1, client.push(device, playlist).revisions().size)
        }
        assertEquals(5L, dataCount())
    }

    @Test fun `malformed input or disallowed source anywhere in a batch stores nothing`() = testApplication {
        application { ownershipApi(database, verifier) }
        val device = client.register()
        client.enableSync(device, history = true)
        val first = profile()
        val invalid = listOf(
            profile().replace("\"base_revision\":0", "\"base_revision\":-1"),
            profile().replace("\"base_revision\":0", "\"base_revision\":\"0\""),
            profile().replace("\"base_revision\":0", "\"base_revision\":9223372036854775808"),
            profile().replace("\"base_revision\":0", "\"base_revision\":0,\"base_revision\":0"),
            profile().replace("\"base_revision\":0", "\"user_id\":\"other\",\"base_revision\":0"),
            profile(name = "content://private/library"),
            profile(name = "Source HTTPS://private/library"),
            profile(name = "content:/private/library"),
            profile(name = "file:/private/library"),
            profile(name = "data:text/plain,fixture"),
            profile(name = "data:;base64,Zml4dHVyZQ=="),
            profile(name = "data:;charset=UTF-8;base64,Zml4dHVyZQ=="),
            profile(name = "data:,fixture"),
            profile(name = "Bearer Zml4dHVyZS10b2tl"),
            profile(name = "Basic dXNlcjpmaXh0dXJlLXBhc3M="),
            profile(name = "\\uD800"),
            syncEvent("profile", UUID.randomUUID().toString(), """{"name":"DAC","token":"fixture-secret"}"""),
            history(UUID.randomUUID()).replace("2026-11-20T07:00:00Z", "invalidZ"),
            history(UUID.randomUUID()).replace("\"track_id\":", "\"uri\":\"content://private/library\",\"track_id\":"),
            consent("cloud_sync"),
        )
        for (last in invalid) {
            val response = client.push(device, first, last)
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("""{"error":"invalid_request"}""", response.bodyAsText())
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }
        for (body in listOf("", " ", "null", "[]", syncBatch(device), syncBatch(device, first) + "{}")) {
            assertEquals(HttpStatusCode.BadRequest, client.sendPush(body).status)
        }
        assertEquals(HttpStatusCode.BadRequest, client.sendPush(byteArrayOf(-1)).status)
        assertEquals(HttpStatusCode.BadRequest, client.sendPush(syncBatch(device, first), query = "?cursor=obsolete").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/sync?cursor=obsolete") { bearerAuth(token()) }.status)

        val forbidden = client.push(device, first, history(UUID.randomUUID(), source = "provider"))
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals("""{"error":"forbidden"}""", forbidden.bodyAsText())
        assertEquals(0L, dataCount())
    }

    @Test fun `registered device user scope and current explicit consent are required for new data`() = testApplication {
        application { ownershipApi(database, verifier) }
        val device = client.register()
        val event = profile()
        val unauthenticated = client.sendPush(syncBatch(device, event), subject = null)
        assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
        assertTrue(unauthenticated.headers[HttpHeaders.WWWAuthenticate]?.startsWith("Bearer") == true)
        assertEquals(HttpStatusCode.Forbidden, client.push(device, event, subject = "bob").status)
        assertEquals(HttpStatusCode.Forbidden, client.push(UUID.randomUUID(), event).status)
        assertEquals(HttpStatusCode.Forbidden, client.push(device, event).status)

        val grant = client.push(device, consent("cloud_sync")).revisions().single()
        assertEquals(HttpStatusCode.Forbidden, client.push(device, history(UUID.randomUUID())).status)
        val accepted = client.push(device, event).revisions()
        client.push(device, consent("cloud_sync", granted = false, base = grant)).revisions()
        assertEquals(HttpStatusCode.Forbidden, client.push(device, profile()).status)
        assertEquals(accepted, client.push(device, event).revisions())

        val bob = client.register("bob")
        client.enableSync(bob, subject = "bob")
        assertNotEquals(accepted, client.push(bob, event, subject = "bob").revisions())
        assertEquals(2L, dataCount())
        execute("UPDATE users SET active = false")
        assertEquals(HttpStatusCode.Forbidden, client.push(device, event).status)
    }

    @Test fun `exact duplicates replay per position but ID reuse and stale revisions roll back the batch`() = testApplication {
        application { ownershipApi(database, verifier) }
        val device = client.register()
        client.enableSync(device)
        val entity = UUID.randomUUID().toString()
        val id = UUID.randomUUID()
        val first = syncEvent("profile", entity, """{"name":"DAC A"}""", id)
        val changed = syncEvent("profile", entity, """{"name":"DAC B"}""", id)
        val pending = profile()
        client.push(device, first, changed).assertConflict("event_id_conflict")
        assertEquals(0L, dataCount())

        val replay = JsonObject(Json.parseToJsonElement(first).jsonObject.toList().asReversed().toMap()).toString()
        val revisions = client.push(device, first, replay).revisions()
        assertEquals(revisions[0], revisions[1])
        client.push(device, pending, changed).assertConflict("event_id_conflict")
        assertEquals(1L, dataCount())
        assertEquals(listOf(revisions[0]), client.push(device, replay).revisions())

        val update = syncEvent("profile", entity, """{"name":"DAC v2"}""", base = revisions[0])
        val competing = syncEvent("profile", entity, """{"name":"DAC v3"}""", base = revisions[0])
        client.push(device, update, competing).assertConflict("revision_conflict")
        assertEquals(1L, dataCount())
        val revision = client.push(device, update).revisions().single()
        client.push(device, competing).assertConflict("revision_conflict")
        assertEquals(listOf(revision), client.push(device, update).revisions())

        val deletion = syncEvent("profile", entity, "null", base = revision, operation = "delete")
        client.push(device, deletion).revisions()
        client.push(device, syncEvent("profile", entity, """{"name":"old device"}""")).assertConflict("revision_conflict")
        assertEquals(3L, dataCount())
    }

    @Test fun `concurrent devices sharing a user serialize conflicting writes and retried results`() = testApplication {
        application { ownershipApi(database, verifier) }
        val first = client.register()
        val second = client.register()
        client.enableSync(first)
        val entity = UUID.randomUUID().toString()
        val commands = listOf(first to profile(entity, "A"), second to profile(entity, "B"))
        coroutineScope {
            val responses = commands.map { (device, event) -> async { client.push(device, event) } }.awaitAll()
            val winner = responses.indexOfFirst { it.status == HttpStatusCode.OK }
            assertTrue(winner >= 0)
            responses[1 - winner].assertConflict("revision_conflict")
            val (device, event) = commands[winner]
            val (otherDevice, _) = commands[1 - winner]
            val original = responses[winner].revisions()
            val replays = List(2) { async { client.push(device, event).revisions() } }.awaitAll()
            assertTrue(replays.all { it == original })
            client.push(otherDevice, event).assertConflict("event_id_conflict")
        }
        assertEquals(1L, dataCount())
    }

    @Test fun `history deletion suppresses late and re-IDed events without reapplying old receipts`() = testApplication {
        application { ownershipApi(database, verifier) }
        val device = client.register()
        client.enableSync(device, history = true)
        val owner = Json.parseToJsonElement(client.get("/v1/me") { bearerAuth(token()) }.bodyAsText())
        val userId = UUID.fromString(owner.jsonObject.getValue("user_id").jsonPrimitive.content)
        val originalId = UUID.randomUUID()
        val original = history(originalId)
        val first = client.push(device, original).revisions()
        val tombstoned = UUID.randomUUID()
        database.tombstoneHistory(userId, tombstoned)
        client.push(device, history(tombstoned)).assertConflict("history_deleted")

        assertEquals(1L, database.advanceHistoryEpoch(userId, 0))
        assertEquals(1L, database.advanceHistoryEpoch(userId, 0))
        assertEquals(first, client.push(device, original).revisions())
        assertEquals(0L, scalar("SELECT count(*) FROM sync_events WHERE entity_type = 'history' AND payload IS NOT NULL"))
        client.push(device, profile(), history(UUID.randomUUID())).assertConflict("history_epoch_conflict")
        client.push(device, history(originalId)).assertConflict("history_epoch_conflict")
        client.push(device, history(originalId, epoch = 1)).assertConflict("revision_conflict")
        client.push(device, history(tombstoned, epoch = 1)).assertConflict("history_deleted")
        val currentId = UUID.randomUUID()
        val current = history(currentId, epoch = 1)
        val receipt = client.push(device, current).revisions()
        assertEquals(2L, dataCount())
        database.tombstoneHistory(userId, currentId)
        database.tombstoneHistory(userId, currentId)
        assertEquals(receipt, client.push(device, current).revisions())
        client.push(device, history(currentId, epoch = 1)).assertConflict("history_deleted")
        assertEquals(0L, scalar("SELECT count(*) FROM sync_events WHERE entity_type = 'history' AND payload IS NOT NULL"))
        assertEquals(2L, scalar("SELECT count(*) FROM history_tombstones"))
    }

    @Test fun `database timeout after first write rolls back and the same batch recovers`() = testApplication {
        application { ownershipApi(database, verifier) }
        val device = client.register()
        client.enableSync(device)
        val blocked = UUID.randomUUID()
        val batch = syncBatch(device, profile(), profile(blocked.toString()))
        withSlowInsert(blocked) {
            val failed = client.sendPush(batch)
            assertEquals(HttpStatusCode.ServiceUnavailable, failed.status)
            assertEquals("1", failed.headers[HttpHeaders.RetryAfter])
            assertEquals("""{"error":"temporarily_unavailable"}""", failed.bodyAsText())
            assertEquals(0L, dataCount())
        }
        val accepted = client.sendPush(batch).revisions()
        assertEquals(2, accepted.size)
        assertEquals(accepted, client.sendPush(batch).revisions())
        assertEquals(2L, dataCount())
    }

    @Test fun `cancelling push during a JDBC write leaves no events and allows retry`() = testApplication {
        application { ownershipApi(database, verifier) }
        val device = client.register()
        client.enableSync(device)
        val blocked = UUID.randomUUID()
        val batch = syncBatch(device, profile(), profile(blocked.toString()))
        val identity = Identity(issuer, "alice")
        withSlowInsert(blocked, seconds = 1) {
            coroutineScope {
                val job = launch { database.pushSync(identity, parseSyncBatch(batch)) }
                try {
                    withTimeout(2_000) {
                        while (scalar("SELECT count(*) FROM pg_stat_activity WHERE wait_event = 'PgSleep'") == 0L) {
                            delay(10)
                        }
                    }
                } finally {
                    job.cancelAndJoin()
                }
            }
            assertEquals(0L, dataCount())
        }
        assertEquals(2, client.sendPush(batch).revisions().size)
    }

    @Test fun `busy returns retry-after without changing the batch and retries when a permit returns`() = testApplication {
        val admission = Semaphore(1, acquiredPermits = 1)
        application { ownershipApi(database, verifier, admission) }
        val device = client.register()
        val command = consent("cloud_sync")
        val busy = client.push(device, command)
        assertEquals(HttpStatusCode.TooManyRequests, busy.status)
        assertEquals("1", busy.headers[HttpHeaders.RetryAfter])
        assertEquals("""{"error":"try_later"}""", busy.bodyAsText())
        assertEquals(0L, scalar("SELECT count(*) FROM sync_events"))
        admission.release()
        assertEquals(1, client.push(device, command).revisions().size)
    }

    private suspend fun HttpClient.register(subject: String = "alice"): UUID = UUID.randomUUID().also { id ->
        val response = post("/v1/auth/session") {
            bearerAuth(token(subject))
            contentType(ContentType.Application.Json)
            setBody("""{"device_id":"$id"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun HttpClient.enableSync(device: UUID, history: Boolean = false, subject: String = "alice") {
        val grants = if (history) arrayOf(consent("cloud_sync"), consent("history_sync")) else arrayOf(consent("cloud_sync"))
        assertEquals(grants.size, push(device, *grants, subject = subject).revisions().size)
    }

    private suspend fun HttpClient.push(device: UUID, vararg events: String, subject: String = "alice") =
        sendPush(syncBatch(device, *events), subject)

    private suspend inline fun <reified T : Any> HttpClient.sendPush(body: T, subject: String? = "alice", query: String = "") =
        post(syncPath + query) {
            if (subject != null) bearerAuth(token(subject))
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.revisions(): List<Long> {
        val body = bodyAsText()
        assertEquals(body, HttpStatusCode.OK, status)
        return Json.parseToJsonElement(body).jsonObject.getValue("results").jsonArray.map {
            assertEquals(setOf("event_id", "server_revision"), it.jsonObject.keys)
            it.jsonObject.getValue("server_revision").jsonPrimitive.long
        }
    }

    private suspend fun HttpResponse.assertConflict(code: String) {
        assertEquals(HttpStatusCode.Conflict, status)
        assertEquals("""{"error":"$code"}""", bodyAsText())
    }

    private fun consent(key: String, granted: Boolean = true, base: Long = 0) =
        syncEvent("consent", key, """{"version":1,"granted":$granted}""", base = base)

    private fun profile(entity: String = UUID.randomUUID().toString(), name: String = "DAC") =
        syncEvent("profile", entity, """{"name":"$name"}""")

    private fun history(entity: UUID, epoch: Long = 0, source: String = "local") = syncEvent(
        "history", entity.toString(),
        """{"track":{"track_id":"$trackId","source":"$source"},"started_at":"2026-11-20T07:00:00Z","listened_ms":2000}""",
        epoch = epoch,
    )

    private suspend fun withSlowInsert(entity: UUID, seconds: Int = 3, test: suspend () -> Unit) {
        execute("""
            CREATE FUNCTION fixture_slow_insert() RETURNS trigger LANGUAGE plpgsql AS '
            BEGIN
                IF NEW.entity_id = ''$entity'' THEN PERFORM pg_sleep($seconds); END IF;
                RETURN NEW;
            END;';
            CREATE TRIGGER fixture_slow_insert BEFORE INSERT ON sync_events
                FOR EACH ROW EXECUTE FUNCTION fixture_slow_insert()
        """.trimIndent())
        try {
            test()
        } finally {
            execute("DROP TRIGGER fixture_slow_insert ON sync_events; DROP FUNCTION fixture_slow_insert()")
        }
    }

    private fun dataCount(): Long = scalar("SELECT count(*) FROM sync_events WHERE entity_type <> 'consent'")

    private fun scalar(sql: String): Long = pg.postgresDatabase.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getLong(1) }
        }
    }

    private fun execute(sql: String) {
        pg.postgresDatabase.connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }
}
