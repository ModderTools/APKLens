package com.apklens.app.platform;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.apklens.app.engine.ZipSink;

import java.io.IOException;
import java.io.OutputStream;

/** ZipSink that writes into MediaStore (Download/APKLens/...) on Android 10+. */
public class MediaStoreSink implements ZipSink {

    private final Context ctx;
    private final String relativeDir;
    private final String fileName;
    private Uri uri;

    public MediaStoreSink(Context ctx, String relativeDir, String fileName) {
        this.ctx = ctx;
        this.relativeDir = relativeDir;
        this.fileName = fileName;
    }

    @Override
    public OutputStream open() throws IOException {
        if (Build.VERSION.SDK_INT < 29) {
            throw new IOException("Storage permission required to export on this Android version.");
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Could not create the output file (storage busy?)");
        OutputStream os = ctx.getContentResolver().openOutputStream(uri);
        if (os == null) throw new IOException("Could not open output stream for the ZIP");
        return os;
    }

    @Override
    public void commit() throws IOException {
        if (uri == null || Build.VERSION.SDK_INT < 29) return;
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        try {
            ctx.getContentResolver().update(uri, v, null, null);
        } catch (Exception e) {
            throw new IOException("Failed to finalize the ZIP: " + e.getMessage());
        }
    }

    @Override
    public void abort() {
        if (uri != null && Build.VERSION.SDK_INT >= 29) {
            try { ctx.getContentResolver().delete(uri, null, null); } catch (Throwable ignored) {}
        }
    }

    @Override
    public String describe() {
        String path = relativeDir + "/" + fileName;
        if (uri != null && Build.VERSION.SDK_INT >= 29) {
            try (Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String p = c.getString(0);
                    if (p != null && !p.isEmpty()) return p;
                }
            } catch (Throwable ignored) {}
        }
        return path;
    }

    @Override
    public String mode() { return "media"; }

    @Override
    public String uriString() { return uri == null ? "" : uri.toString(); }

    @Override
    public String filePath() { return null; }

    @Override
    public String projectId() { return null; }
}
