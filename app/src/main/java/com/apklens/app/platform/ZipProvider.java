package com.apklens.app.platform;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.apklens.app.engine.Io;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal zero-dependency FileProvider substitute. Serves exported ZIPs that live in the
 * direct-file location (/storage/emulated/0/APKLens/...) so they can be shared/opened
 * with content:// URIs (file:// is forbidden to other apps on modern Android).
 */
public class ZipProvider extends ContentProvider {

    public static final String AUTHORITY = "com.apklens.app.zipfiles";

    public static Uri uriFor(File f) {
        try {
            return new Uri.Builder()
                    .scheme("content")
                    .authority(AUTHORITY)
                    .appendPath("file")
                    .appendPath(Uri.encode(f.getCanonicalPath()))
                    .build();
        } catch (Exception e) {
            return Uri.parse("content://" + AUTHORITY + "/file/" + Uri.encode(f.getAbsolutePath()));
        }
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/zip"; }

    private File fileFor(Uri uri) throws FileNotFoundException {
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() != 2 || !"file".equals(seg.get(0))) throw new FileNotFoundException("bad uri");
        File f = new File(Uri.decode(seg.get(1)));
        try {
            String can = f.getCanonicalPath();
            File root = com.apklens.app.platform.Store.directRoot().getCanonicalFile();
            File appPriv = getContext().getExternalFilesDir(null);
            boolean ok = can.startsWith(root.getCanonicalPath() + File.separator)
                    || (appPriv != null && can.startsWith(appPriv.getCanonicalPath() + File.separator));
            if (!ok || !can.endsWith(".zip")) throw new FileNotFoundException("outside allowed roots");
        } catch (FileNotFoundException fnf) {
            throw fnf;
        } catch (Exception e) {
            throw new FileNotFoundException("resolve failed");
        }
        if (!f.isFile()) throw new FileNotFoundException("missing");
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        if (projection == null) projection = new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(projection);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(projection[i])) row[i] = f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String sel, String[] args) { return 0; }
    @Override
    public int update(Uri uri, ContentValues v, String sel, String[] args) { return 0; }
}
