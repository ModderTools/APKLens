package com.apklens.app.platform;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.apklens.app.data.ProjectRecord;
import com.apklens.app.engine.Io;

import java.io.File;

/** Version-aware storage decisions + share/open/delete helpers for exported ZIPs. */
public final class Store {

    private Store() {}

    // ---------- Permission model ----------

    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    public static boolean hasWrite(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return true; // not used for our MediaStore path
        return c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasAllFiles(Context c) {
        return Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager();
    }

    /** True when the exact /storage/emulated/0/APKLens/ folder is writable. */
    public static boolean directAvailable(Context c) {
        if (hasAllFiles(c)) return true;
        return Build.VERSION.SDK_INT <= 29 && hasWrite(c); // legacy storage enabled via manifest flag
    }

    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    public static File directRoot() {
        return new File(Environment.getExternalStorageDirectory(), "APKLens");
    }

    public static String describeMode(Context c) {
        if (directAvailable(c)) {
            return directRoot().getAbsolutePath() + "/<Project>/<apk>.zip";
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return "Download/APKLens/<Project>/<apk>.zip (MediaStore)";
        }
        return "Storage permission required";
    }

    public static String appVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) { return "?"; }
    }

    public static void clearWorkCache(Context c) {
        Io.deleteRecursively(new File(c.getCacheDir(), "work"));
        File[] files = c.getCacheDir().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith("picked_") || f.getName().startsWith("work_")) {
                    Io.deleteRecursively(f);
                }
            }
        }
    }

    // ---------- Share / Open / Delete ----------

    public static Uri uriFor(Context c, ProjectRecord rec) {
        if ("media".equals(rec.zipMode) && rec.zipUri != null && !rec.zipUri.isEmpty()) {
            return Uri.parse(rec.zipUri);
        }
        if (rec.zipPath != null && !rec.zipPath.isEmpty()) {
            return ZipProvider.uriFor(new File(rec.zipPath));
        }
        return null;
    }

    public static Uri uriForResult(Context c, com.apklens.app.engine.EngineResult r) {
        if ("media".equals(r.zipMode) && r.zipUri != null && !r.zipUri.isEmpty()) {
            return Uri.parse(r.zipUri);
        }
        if (r.zipFilePath != null && !r.zipFilePath.isEmpty()) {
            return ZipProvider.uriFor(new File(r.zipFilePath));
        }
        return null;
    }

    public static void shareZip(Context c, ProjectRecord rec) { share(c, uriFor(c, rec)); }

    public static void shareZipResult(Context c, com.apklens.app.engine.EngineResult r) {
        share(c, uriForResult(c, r));
    }

    private static void share(Context c, Uri uri) {
        if (uri == null) { toast(c, "ZIP location unknown"); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.setClipData(android.content.ClipData.newRawUri("zip", uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            c.startActivity(Intent.createChooser(i, "Share project ZIP")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) { toast(c, "No app can share this file"); }
    }

    public static boolean openZip(Context c, ProjectRecord rec) { return open(c, uriFor(c, rec)); }
    public static boolean openZipResult(Context c, com.apklens.app.engine.EngineResult r) {
        return open(c, uriForResult(c, r));
    }

    private static boolean open(Context c, Uri uri) {
        if (uri == null) { toast(c, "ZIP location unknown"); return false; }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/zip");
        i.setClipData(android.content.ClipData.newRawUri("zip", uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); return true; }
        catch (Exception e) { toast(c, "No app can open ZIP files"); return false; }
    }

    public static void deleteArtifact(Context c, ProjectRecord rec) {
        try {
            if ("media".equals(rec.zipMode) && rec.zipUri != null && !rec.zipUri.isEmpty()) {
                c.getContentResolver().delete(Uri.parse(rec.zipUri), null, null);
            } else if (rec.zipPath != null && !rec.zipPath.isEmpty()) {
                File f = new File(rec.zipPath);
                if (f.getCanonicalPath().startsWith(directRoot().getCanonicalPath() + File.separator)) {
                    Io.deleteRecursively(f);
                    File parent = f.getParentFile();
                    if (parent != null && parent.isDirectory()
                            && parent.listFiles() != null && parent.listFiles().length == 0
                            && parent.getCanonicalPath()
                                .startsWith(directRoot().getCanonicalPath() + File.separator)) {
                        parent.delete();
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void toast(Context c, String m) {
        android.widget.Toast.makeText(c, m, android.widget.Toast.LENGTH_SHORT).show();
    }
}
