package com.audipp.data.secrets

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.data.db.LibraryBatch
import com.audipp.data.db.LibraryDatabase
import com.audipp.data.db.TrackEntity
import com.audipp.data.preferences.PreferenceStore
import com.audipp.domain.model.ThemeMode
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = context.secretStore
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val accounts = mutableListOf<String>()
    private val kind = SecretKind.REFRESH_TOKEN
    private val original = byteArrayOf(1, 2, 3)
    private val updated = byteArrayOf(4, 5, 6)

    @After
    fun removeTestSecrets() = runBlocking {
        for (account in accounts) store.requireAuthentication(account)
    }

    @Test
    fun authorizeUseUpdateAndUseWithoutKeyAuthentication() = runBlocking {
        val account = newAccount()
        val session = store.authorize(account, kind, original)
        assertSecret(session, original)
        store.update(session, kind, updated)
        assertSecret(session, updated)

        val key = keyStore.getKey(alias(account), null) as SecretKey
        val info = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertFalse(info.isUserAuthenticationRequired)
    }

    @Test
    fun rejectsAnotherAccountsCiphertext() = runBlocking {
        val account = newAccount()
        val other = newAccount()
        val session = store.authorize(account, kind, original)
        val otherSession = store.authorize(other, kind, updated)
        record(account).writeBytes(record(other).readBytes())

        assertAuthenticationRequired(session)
        assertSecret(otherSession, updated)
    }

    @Test
    fun rejectsTamperedTag() = runBlocking {
        val account = newAccount()
        val session = store.authorize(account, kind, original)
        val file = record(account)
        val ciphertext = file.readBytes()
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        file.writeBytes(ciphertext)

        assertAuthenticationRequired(session)
    }

    @Test
    fun rejectsRestoredCiphertextWithoutItsKey() = runBlocking {
        val account = newAccount()
        val session = store.authorize(account, kind, original)
        val ciphertext = record(account).readBytes()
        keyStore.deleteEntry(alias(account))
        assertFalse(keyStore.containsAlias(alias(account)))

        assertAuthenticationRequired(session)
        assertFalse(keyStore.containsAlias(alias(account)))
        assertArrayEquals(ciphertext, record(account).readBytes())
    }

    @Test
    fun rejectsRestoredCiphertextWithAReplacementKey() = runBlocking {
        val account = newAccount()
        val oldSession = store.authorize(account, kind, original)
        assertSecret(oldSession, original)
        val ciphertext = record(account).readBytes()
        val session = store.authorize(account, kind, updated)
        assertSecret(session, updated)
        record(account).writeBytes(ciphertext)

        assertAuthenticationRequired(session)
    }

    @Test
    fun removesOnlyTheTargetAccountsSecrets() = runBlocking {
        val account = newAccount()
        val other = newAccount()
        val session = store.authorize(account, kind, original)
        store.update(session, SecretKind.ACCESS_TOKEN, updated)
        val otherSession = store.authorize(other, kind, updated)
        val dbName = "$account.db"
        val dbFile = context.getDatabasePath(dbName)
        val settingsFile = File(context.cacheDir, "$account/settings.preferences_pb")
        val settingsJob = Job()
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + settingsJob)) {
                settingsFile
            }
            PreferenceStore(dataStore).setTheme(ThemeMode.DARK)
            val track = TrackEntity("local:device-test", "Test track")
            val db = LibraryDatabase.open(context, dbName)
            try {
                db.libraryDao().save(LibraryBatch(tracks = listOf(track)))
            } finally {
                db.close()
            }
            val libraryBytes = dbFile.readBytes()
            val settingsBytes = settingsFile.readBytes()

            store.requireAuthentication(session)

            assertEquals(SecretState.AUTH_REQUIRED, session.state.value)
            assertAuthenticationRequired(session)
            assertFalse(keyStore.containsAlias(alias(account)))
            assertFalse(record(account).parentFile!!.exists())
            assertSecret(otherSession, updated)
            assertArrayEquals(libraryBytes, dbFile.readBytes())
            assertArrayEquals(settingsBytes, settingsFile.readBytes())
        } finally {
            settingsJob.cancelAndJoin()
            settingsFile.parentFile!!.deleteRecursively()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun sessionExpiryCancelsPendingUse() = assertPendingUseCancelled { session ->
        store.requireAuthentication(session)
    }

    @Test
    fun accountLogoutCancelsPendingUse() = assertPendingUseCancelled { session ->
        store.requireAuthentication(session.accountId)
    }

    // Each phase runs in its own instrumentation process. The host supplies the same UUID to both phases.
    @Test
    fun processRestartPhase() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("secretsPhase")
        assumeTrue("Run through the documented restart sequence", phase != null)
        val runId = UUID.fromString(requireNotNull(arguments.getString("secretsRunId")))
        val account = "secret-device-restart-$runId"
        when (phase) {
            "WRITE" -> {
                accounts += account
                val session = store.authorize(account, kind, original)
                store.update(session, kind, updated)
                accounts.remove(account)
            }
            "READ_AFTER_RESTART" -> {
                accounts += account
                assertTrue("WRITE must leave ciphertext before the restart", record(account).isFile)
                assertSecret(store.resume(account), updated)
            }
            else -> error("Unknown secretsPhase: $phase")
        }
    }

    private fun assertPendingUseCancelled(invalidate: suspend (SecretSession) -> Unit) = runBlocking {
        withTimeout(10_000) {
            val session = store.authorize(newAccount(), kind, original)
            val entered = CompletableDeferred<Unit>()
            val pending = launch {
                store.use(session, kind) {
                    assertArrayEquals(original, it)
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            invalidate(session)
            pending.join()
            assertTrue(pending.isCancelled)
            assertEquals(SecretState.AUTH_REQUIRED, session.state.value)
            assertAuthenticationRequired(session)
        }
    }

    private suspend fun assertSecret(session: SecretSession, expected: ByteArray) {
        store.use(session, kind) { assertArrayEquals(expected, it) }
        assertEquals(SecretState.READY, session.state.value)
    }

    private suspend fun assertAuthenticationRequired(session: SecretSession) {
        try {
            store.use(session, kind) { fail("Operation received a secret") }
            fail("Expected AUTH_REQUIRED")
        } catch (error: SecretStorageException) {
            assertEquals(SecretFailure.AUTH_REQUIRED, error.failure)
        }
        assertEquals(SecretState.AUTH_REQUIRED, session.state.value)
    }

    private fun newAccount() = "secret-device-${UUID.randomUUID()}".also { accounts += it }

    private fun alias(account: String) = "audipp.secrets.v1.${SecretCipher.accountHash(account)}"

    private fun record(account: String) = File(
        context.noBackupFilesDir,
        "secrets-v1/${SecretCipher.accountHash(account)}/${kind.name}.bin",
    )
}
