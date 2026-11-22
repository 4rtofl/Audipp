package com.audipp.playback.source

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.domain.model.Availability
import com.audipp.domain.model.PlaybackCapabilities
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.VariantId
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Resolves through the same [AndroidDocumentAccess] the playback service uses, against a real ContentResolver. */
@RunWith(AndroidJUnit4::class)
// ContentResolver.wrap, which routes a resolver to an in-process provider, exists from API 29.
@SdkSuppress(minSdkVersion = 29)
class SourceResolverDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val audio = File.createTempFile("resolver", ".bin", context.cacheDir).apply { writeBytes(ByteArray(64)) }
    private val opened = mutableListOf<String>()
    private val descriptors = mutableListOf<ParcelFileDescriptor>()
    private var denied = false
    private val device = ResolutionContext(PlaybackTarget("phone", TargetKind.DEVICE), 1, TransitionId("attempt"))
    private val renderer = device.copy(target = PlaybackTarget("living-room", TargetKind.RENDERER))
    private val capabilities = PlaybackCapabilities(canPlay = true, canPause = true, canReadPosition = true)

    @After
    fun deleteAudio() {
        audio.delete()
    }

    @Test
    fun readableDocumentResolvesToTheSelectedVersionAndClosesTheProbe() = runBlocking {
        val selected = variant("five-minute", "song")
        val resolver = resolver(device)
        val result = resolver.resolve(ResolutionRequest(selected, device)) as SourceResolution.Resolved.Playback

        assertEquals(listOf("content://fixture/song"), opened)
        assertTrue(descriptors.none { it.fileDescriptor.valid() })
        assertEquals(selected.variantId, result.plan.variantId)
        assertEquals(selected.source, result.plan.source)
        assertEquals("content://fixture/song", (result.descriptor as SourceDescriptor.LocalDocument).uri)
        assertNull(resolver.rejectionFor(result))
    }

    @Test
    fun missingDocumentOffersOtherVersionsWithoutOpeningThem() = runBlocking {
        val selected = variant("five-minute", "gone")
        val other = variant("seven-minute", "song")
        val result = resolver(device).resolve(ResolutionRequest(selected, device), listOf(other))

        assertEquals(SourceResolution.Unavailable(ResolutionFailure.SOURCE_MISSING, listOf(other.variantId)), result)
        assertEquals(listOf("content://fixture/gone"), opened)
    }

    @Test
    fun revokedGrantAsksForPermissionAgain() = runBlocking {
        denied = true
        val result = resolver(device).resolve(ResolutionRequest(variant("five-minute", "song"), device))

        assertEquals(SourceResolution.Unavailable(ResolutionFailure.PERMISSION_REQUIRED), result)
    }

    @Test
    fun contentUriIsNeverHandedToARenderer() = runBlocking {
        val request = ResolutionRequest(variant("five-minute", "song"), renderer)
        val unregistered = resolver(renderer).resolve(request)
        val misregistered = SourceResolver(
            mapOf(AdapterKey(SourceKind.LOCAL, TargetKind.RENDERER) to localAdapter()), { renderer },
        ).resolve(request)

        assertEquals(SourceResolution.Unavailable(ResolutionFailure.ADAPTER_UNAVAILABLE), unregistered)
        assertEquals(SourceResolution.Unavailable(ResolutionFailure.INVALID_DESCRIPTOR), misregistered)
        assertTrue(opened.isEmpty())
    }

    private fun resolver(current: ResolutionContext) =
        SourceResolver(mapOf(AdapterKey(SourceKind.LOCAL, TargetKind.DEVICE) to localAdapter()), { current })

    private fun localAdapter() = LocalDocumentSourceAdapter(capabilities, AndroidDocumentAccess(fixtureResolver())::check)

    private fun variant(id: String, document: String) = TrackVariant(VariantId(id), TrackId("track"),
        SourceRef(SourceId("local:$document"), SourceKind.LOCAL,
            localDocumentUri = "content://fixture/$document", availability = Availability.PLAYABLE), 300_000)

    private fun fixtureResolver(): ContentResolver {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/octet-stream"
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                opened += uri.toString()
                assertEquals("r", mode)
                if (denied) throw SecurityException("grant revoked")
                if (uri.lastPathSegment != "song") throw FileNotFoundException("gone")
                return ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptors += it }
            }

            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = error("file only")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("read-only")
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read-only")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
                error("read-only")
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "fixture" })
        return ContentResolver.wrap(provider)
    }
}
