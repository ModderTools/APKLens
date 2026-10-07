package com.apklens.app.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streams a directory tree into a ZIP with safe entry names and progress reporting. */
public final class ZipWriter {

    private ZipWriter() {}

    /** Counts bytes that actually reach the destination (works for MediaStore too). */
    private static final class Counting extends FilterOutputStream {
        long count;
        Counting(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }

    private static final class Item implements Comparable<Item> {
        final String name; final File file;
        Item(String name, File file) { this.name = name; this.file = file; }
        @Override public int compareTo(Item o) { return name.compareTo(o.name); }
    }

    /** @return number of bytes written to {@code out}. */
    public static long write(File dir, OutputStream out, ProgressListener prog) throws IOException {
        // Relative names are computed ONCE (canonical-path lookups inside a sort comparator
        // made this O(n log n) syscalls on big projects).
        List<Item> items = new ArrayList<>();
        for (File f : Io.walkFiles(dir)) {
            String name = Io.relPath(dir, f);
            if (name.startsWith("/") || name.contains("../")) continue; // paranoia
            items.add(new Item(name, f));
        }
        Collections.sort(items);

        int total = items.size();
        // Counting sits BELOW the buffer so it sees exactly what reaches the destination.
        Counting counting = new Counting(out);
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(counting, 1 << 16));
        try {
            byte[] buf = new byte[64 * 1024];
            for (int i = 0; i < total; i++) {
                if (prog != null && prog.isCancelled()) throw new CancelledException();
                Item it = items.get(i);
                ZipEntry e = new ZipEntry(it.name);
                e.setTime(it.file.lastModified());
                zos.putNextEntry(e);
                try (InputStream in = new BufferedInputStream(new FileInputStream(it.file))) {
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
                if (prog != null && (i % 25 == 0 || i == total - 1)) {
                    prog.detail("Zipping " + (i + 1) + "/" + total + " files…");
                    prog.percent((int) ((long) (i + 1) * 100 / Math.max(1, total)));
                }
            }
            zos.finish();
        } finally {
            try { zos.close(); } catch (IOException ignored) {}
        }
        return counting.count;
    }
}
