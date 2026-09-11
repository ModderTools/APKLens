package com.apklens.app.engine;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** ZipSink writing to the exact filesystem path (legacy storage or All-Files-Access). */
public class DirectFileSink implements ZipSink {

    private final File target;
    private File temp;

    public DirectFileSink(File target) { this.target = target; }

    @Override
    public OutputStream open() throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create output folder: " + dir);
        }
        temp = new File(dir, "." + target.getName() + ".part");
        if (temp.exists() && !temp.delete()) throw new IOException("Output file is locked");
        return new BufferedOutputStream(new FileOutputStream(temp), 1 << 16);
    }

    @Override
    public void commit() throws IOException {
        if (temp == null) throw new IOException("Nothing to commit");
        if (target.exists() && !target.delete()) {
            temp.delete();
            throw new IOException("Could not replace the existing ZIP");
        }
        if (!temp.renameTo(target)) {
            // cross-device rename fallback
            try (java.io.InputStream in = new java.io.FileInputStream(temp)) {
                try (OutputStream os = new FileOutputStream(target)) {
                    Io.copy(in, os, -1);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    @Override public void abort() { if (temp != null) Io.deleteRecursively(temp); }

    @Override public String describe() { return target.getAbsolutePath(); }
    @Override public String mode() { return "direct"; }
    @Override public String uriString() { return ""; }
    @Override public String filePath() { return target.getAbsolutePath(); }
    @Override public String projectId() { return target.getParentFile() == null ? "" : target.getParentFile().getName(); }
}
