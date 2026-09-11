package com.apklens.app.engine;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streams a directory tree into a ZIP with safe entry names and progress reporting. */
public final class ZipWriter {

    private ZipWriter() {}

    public static void write(File dir, OutputStream out, ProgressListener prog) throws IOException {
        List<File> files = Io.walkFiles(dir);
        Collections.sort(files, Comparator.comparing(f -> {
            try { return Io.relPath(dir, f); } catch (IOException e) { return f.getName(); }
        }));
        int total = files.size();
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(out, 1 << 16));
        try {
            byte[] buf = new byte[64 * 1024];
            for (int i = 0; i < total; i++) {
                if (prog != null && prog.isCancelled()) throw new CancelledException();
                File f = files.get(i);
                String name = Io.relPath(dir, f);
                if (name.startsWith("/") || name.contains("../")) continue; // paranoia
                ZipEntry e = new ZipEntry(name);
                e.setTime(f.lastModified());
                zos.putNextEntry(e);
                try (java.io.InputStream in = new java.io.BufferedInputStream(
                        new java.io.FileInputStream(f))) {
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
                if (prog != null && total > 0 && (i % 25 == 0 || i == total - 1)) {
                    prog.detail("Zipping " + (i + 1) + "/" + total + " files…");
                }
            }
            zos.finish();
        } finally {
            try { zos.close(); } catch (IOException ignored) {}
        }
    }
}
