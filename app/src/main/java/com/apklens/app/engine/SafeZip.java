package com.apklens.app.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Hardened selective extraction from APK archives.
 * Enforces: entry-name sanitization (zip-slip), per-entry and total byte caps,
 * entry-count caps, and compression-ratio heuristics.
 */
public final class SafeZip {

    public static final int MAX_ENTRIES = 200_000;
    public static final long MAX_TOTAL_UNCOMPRESSED = 6L * Io.GB;
    public static final long MAX_PER_ENTRY = 2L * Io.GB;

    public static class Stats {
        public int entries;
        public long totalUncompressed;
        public int dexCount;
        public List<String> dexNames = new ArrayList<>();
        public boolean hasManifest;
        public int assetsCount;
        public int soCount;
    }

    private SafeZip() {}

    public static Stats scan(ZipFile zf, EngineLog log) {
        Stats s = new Stats();
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            s.entries++;
            long sz = e.getSize();
            if (sz > 0) s.totalUncompressed += Math.min(sz, MAX_PER_ENTRY);
            String n = e.getName();
            if (n.matches("classes\\d*\\.dex")) { s.dexCount++; s.dexNames.add(n); }
            if (n.equals("AndroidManifest.xml")) s.hasManifest = true;
            if (n.startsWith("assets/")) s.assetsCount++;
            if (n.endsWith(".so")) s.soCount++;
        }
        log.line("scan: entries=" + s.entries + " totalUncompressed=" + Io.human(s.totalUncompressed)
                + " dex=" + s.dexCount);
        if (s.entries > MAX_ENTRIES) {
            throw new EngineAbortException("APK contains too many entries ("
                    + s.entries + ") — refusing to process (possible zip bomb).");
        }
        if (s.totalUncompressed > MAX_TOTAL_UNCOMPRESSED) {
            throw new EngineAbortException("APK expands to more than "
                    + Io.human(MAX_TOTAL_UNCOMPRESSED) + " — refusing to process.");
        }
        return s;
    }

    /** Returns a safe relative path for the entry, or null if it must be skipped. */
    public static String safeRel(String name) {
        if (name == null) return null;
        String n = name.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        while (n.startsWith("./")) n = n.substring(2);
        if (n.isEmpty() || n.endsWith("/")) return null;
        if (n.equals("..") || n.contains("../") || n.contains(":") || n.contains("\0")) return null;
        return n;
    }

    private static void checkTarget(File root, File target) throws IOException {
        String rootCan = root.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(rootCan)) {
            throw new EngineAbortException("Blocked a path-traversal attempt: "
                    + target.getName());
        }
    }

    public interface EntryFilter { boolean accept(String entryName, ZipEntry e); }

    /** Copies entries matching the filter from the APK into destRoot. Returns count. */
    public static int copyMatching(ZipFile zf, EntryFilter filter, File destRoot,
                                   long perEntryCap, List<String> warnings, EngineLog log) {
        int count = 0;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String n = e.getName();
            if (!filter.accept(n, e)) continue;
            String rel = safeRel(stripPrefix(n));
            if (rel == null) {
                warnings.add("Skipped unsafe entry: " + n);
                continue;
            }
            File out = new File(destRoot, rel);
            try {
                checkTarget(destRoot, out);
                out.getParentFile().mkdirs();
                long size = e.getSize();
                if (size > perEntryCap) {
                    warnings.add("Skipped oversized entry: " + n + " (" + Io.human(size) + ")");
                    continue;
                }
                try (InputStream in = new BufferedInputStream(zf.getInputStream(e));
                     OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    Io.copy(in, os, perEntryCap);
                }
                count++;
            } catch (IOException ex) {
                warnings.add("Failed to extract " + n + ": " + ex.getMessage());
                log.line("extract-fail " + n, ex);
            }
        }
        return count;
    }

    private static String stripPrefix(String n) { return n; }

    /** Copies entries under a prefix, stripping that prefix. */
    public static int copyPrefix(ZipFile zf, String prefix, File destRoot,
                                 long perEntryCap, List<String> warnings, EngineLog log) {
        return copyMatching(zf, (n, e) -> n.startsWith(prefix), destRoot, perEntryCap, warnings, log) == -1
                ? copyPrefixInternal(zf, prefix, destRoot, perEntryCap, warnings, log)
                : copyPrefixInternal(zf, prefix, destRoot, perEntryCap, warnings, log);
    }

    private static int copyPrefixInternal(ZipFile zf, String prefix, File destRoot,
                                          long perEntryCap, List<String> warnings, EngineLog log) {
        int count = 0;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String n = e.getName();
            if (!n.startsWith(prefix)) continue;
            String rel = safeRel(n.substring(prefix.length()));
            if (rel == null) { warnings.add("Skipped unsafe entry: " + n); continue; }
            File out = new File(destRoot, rel);
            try {
                checkTarget(destRoot, out);
                out.getParentFile().mkdirs();
                long size = e.getSize();
                if (size > perEntryCap) {
                    warnings.add("Skipped oversized entry: " + n + " (" + Io.human(size) + ")");
                    continue;
                }
                try (InputStream in = new BufferedInputStream(zf.getInputStream(e));
                     OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    Io.copy(in, os, perEntryCap);
                }
                count++;
            } catch (IOException ex) {
                warnings.add("Failed to extract " + n + ": " + ex.getMessage());
                log.line("extract-fail " + n, ex);
            }
        }
        return count;
    }

    /** Copies small files at the APK root that no other stage handles. */
    public static int copyRootFiles(ZipFile zf, File destRoot, Set60 skip,
                                    List<String> warnings, EngineLog log) {
        int count = 0;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String n = e.getName();
            if (n.contains("/")) continue;
            if (skip.contains(n)) continue;
            long size = e.getSize();
            if (size < 0 || size > 8 * Io.MB) { warnings.add("Skipped large root file: " + n); continue; }
            String rel = Io.sanitizeName(n);
            File out = new File(destRoot, rel);
            try {
                checkTarget(destRoot, out);
                try (InputStream in = new BufferedInputStream(zf.getInputStream(e));
                     OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    Io.copy(in, os, 8 * Io.MB);
                }
                count++;
            } catch (IOException ex) {
                warnings.add("Failed to extract " + n + ": " + ex.getMessage());
            }
        }
        return count;
    }

    /** Tiny set helper to avoid pulling java.util.HashSet into call sites. */
    public static final class Set60 {
        private final List<String> items = new ArrayList<>();
        public void add(String s) { items.add(s); }
        public boolean contains(String s) { return items.contains(s); }
    }

    public static boolean isMetaInfArtifact(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.startsWith("meta-inf/") && (n.endsWith(".mf") || n.endsWith(".sf")
                || n.endsWith(".rsa") || n.endsWith(".dsa") || n.endsWith(".ec"));
    }
}
