package com.audipp.data.local

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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AndroidSafAudioTest {
    @Test
    fun recognizesAnAudioContainerFromAReadOnlyDescriptorDespiteTheProviderMimeType() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wave = File.createTempFile("saf-audio", ".bin", context.cacheDir)
        try {
            val pcm = ByteArray(1600)
            wave.writeBytes(ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(pcm.size).put(pcm).array())

            var descriptor: ParcelFileDescriptor? = null
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri) = "application/octet-stream"
                override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                    assertEquals("r", mode)
                    return ParcelFileDescriptor.open(wave, ParcelFileDescriptor.MODE_READ_ONLY).also {
                        descriptor = it
                    }
                }

                override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                    selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = error("file only")
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("read-only")
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read-only")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
                    error("read-only")
            }
            provider.attachInfo(context, ProviderInfo().apply { authority = "fixture" })
            val access = AndroidSafAccess(ContentResolver.wrap(provider))
            val audio = access.analyze(Uri.parse("content://fixture/tree/volume/document/song.bin"))
            assertNotNull(audio)
            assertEquals("audio/raw", audio!!.codec)
            assertEquals(8000, audio.sampleRate)
            assertEquals(1, audio.channels)
            assertEquals(100L, audio.durationMs)
            assertFalse(descriptor!!.fileDescriptor.valid())
        } finally {
            wave.delete()
        }
    }
}
