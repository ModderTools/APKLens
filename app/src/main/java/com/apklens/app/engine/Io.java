package com.apklens.app.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** File utilities shared by the engine. Pure java.io/io — safe for JVM tests. */
public final class Io {

    public static final long KB = 1024, MB = KB * 1024, GB = MB * 1024;

    private Io() {}

    /** Copies with a hard byte cap. Throws IOException when the cap is exceeded. */
    public static long copy(InputStream in, OutputStream out, long cap) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (cap >= 0 && total > cap) {
                try { out.close(); } catch (IOException ignored) {}
                throw new IOException("Quota exceeded: file larger than "
                        + human(cap) + " (possible zip bomb) — aborted");
            }
            out.write(buf, 0, n);
        }
        return total;
    }

    public static void copyFile(File src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            copy(in, out, -1);
        }
    }

    public static List<File> walkFiles(File root) {
        List<File> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    private static void walk(File f, List<File> out) {
        if (f.isFile()) { out.add(f); return; }
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) walk(k, out);
    }

    public static int moveTree(File src, File dst) throws IOException {
        int count = 0;
        for (File f : walkFiles(src)) {
            String rel = relPath(src, f);
            File target = new File(dst, rel);
            target.getParentFile().mkdirs();
            if (!f.renameTo(target)) {
                copyFile(f, target);
                // best-effort delete of the original
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            count++;
        }
        return count;
    }

    public static String relPath(File base, File f) throws IOException {
        String b = base.getCanonicalPath();
        String c = f.getCanonicalPath();
        String rel = c.startsWith(b) ? c.substring(b.length()) : c;
        if (rel.startsWith(File.separator)) rel = rel.substring(1);
        return rel.replace(File.separatorChar, '/');
    }

    public static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static void writeString(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static String human(long bytes) {
        if (bytes < KB) return bytes + " B";
        double v = bytes; int i = -1; String[] u = {"KB", "MB", "GB", "TB"};
        while (v >= KB && i < u.length - 1) { v /= KB; i++; }
        return String.format(java.util.Locale.US, "%.1f %s", v, u[i]);
    }

    /** Sanitizes a single path segment (file or directory name). */
    public static String sanitizeName(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-' || ch == ' ') b.append(ch);
            else b.append('_');
        }
        String r = b.toString().trim();
        while (r.startsWith(".")) r = r.substring(1);
        return r.isEmpty() ? "_" : r;
    }
}
