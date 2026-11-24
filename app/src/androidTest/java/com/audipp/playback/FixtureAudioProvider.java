package com.audipp.playback;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serves silent 8 kHz mono WAV documents from the test APK's own process, so its behavior is
 * encoded in the URI: {@code tone/<name>/<durationMs>} opens, {@code denied/<name>} throws like a
 * revoked grant. That process has no Kotlin runtime, hence Java.
 */
public class FixtureAudioProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "audio/wav";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> path = uri.getPathSegments();
        if (!"tone".equals(path.get(0))) throw new SecurityException("grant revoked");
        int durationMs = Integer.parseInt(path.get(2));
        File file = new File(getContext().getCacheDir(), path.get(1) + "-" + durationMs + ".wav");
        if (!file.exists()) {
            int pcmBytes = durationMs * 8 * 2;
            ByteBuffer wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcmBytes)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(8_000).putInt(16_000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcmBytes);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(wav.array());
            } catch (IOException failure) {
                throw new FileNotFoundException(failure.getMessage());
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
