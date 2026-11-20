package com.audipp.integrations.network.smb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.data.secrets.secretStore
import com.audipp.domain.model.TransitionId
import com.audipp.integrations.network.DirectoryRequest
import com.audipp.integrations.network.NetworkCredentials
import com.audipp.integrations.network.NetworkEndpoint
import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.NetworkProtocol
import com.audipp.integrations.network.NetworkResult
import com.audipp.playback.source.PlaybackTarget
import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceDescriptor
import com.audipp.playback.source.TargetKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmbAndroidTest {
    @Test fun signedFixtureUsesAndroidKeysAndReadsAtANonzeroOffset() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val port = arguments.getString("smbFixturePort")?.toIntOrNull()
        assumeTrue("Run only on the emulator with the explicit local Samba fixture", port in 1024..65535)
        val endpoint = NetworkEndpoint(NetworkProtocol.SMB, "smb://127.0.0.1:$port/music")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val credentials = NetworkCredentials(target.secretStore)
        val library = SmbLibrary(endpoint, { true })
        val secret = SmbPassword.encode("root", "audipp-fixture-test".toCharArray(), "WORKGROUP")
        val account = "smb-android-fixture"
        val token = try {
            success(library.verifyCredential(secret))
            credentials.authorize(endpoint, account, secret)
            credentials.resume(endpoint, account)
        } finally {
            secret.fill(0)
        }
        try {
            var allowed = true
            val smb = SmbLibrary(endpoint, { allowed }, credentials = credentials, credential = token)
            val album = success(smb.client.listDirectory(DirectoryRequest(smb.rootDirectoryId))).single { it.name == "album" }
            val track = success(smb.client.listMedia(DirectoryRequest(album.id))).single()
            val context = ResolutionContext(PlaybackTarget("emulator", TargetKind.DEVICE), 1, TransitionId("fixture"))
            val descriptor = success(smb.client.resolveStream(ResolutionRequest(track.variant, context)))
                .descriptor as SourceDescriptor.DeviceResource
            assertArrayEquals("klmn".toByteArray(), success(smb.readRange(descriptor, 64, 4)))
            allowed = false
            assertEquals(NetworkResult.Failure(NetworkFailure.PERMISSION_REQUIRED), smb.readRange(descriptor, 68, 4))
        } finally {
            credentials.requireAuthentication(token)
        }
    }

    private fun <T> success(result: NetworkResult<T>): T =
        (result as? NetworkResult.Success)?.value ?: error("Expected success, received $result")
}
