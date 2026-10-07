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
 * Split-APK bundles (.xapk / .apks / .apkm) are ZIPs that CONTAIN APKs: a base APK plus
 * config/feature splits. Decompiling the outer ZIP directly would find no DEX, so we pull
 * the base APK out first and treat the splits as extra sources of native libraries.
 *
 * Pure java.io — JVM-testable.
 */
public final class BundleUnpacker {

    public static final class Result {
        public File base;
        public String baseEntry = "";
        public final List<File> splits = new ArrayList<>();
        public final List<String> splitNames = new ArrayList<>();
    }

    private static final long MAX_INNER_APK = 3L * Io.GB;

    private BundleUnpacker() {}

    /** True when the ZIP is not an APK itself but carries one or more APKs. */
    public static boolean isBundle(ZipFile zf) {
        boolean hasManifest = false, hasDex = false, hasInnerApk = false;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String n = e.getName();
            if (n.equals("AndroidManifest.xml")) hasManifest = true;
            else if (Io.isDexName(n)) hasDex = true;
            else if (isInnerApk(n)) hasInnerApk = true;
        }
        return !hasManifest && !hasDex && hasInnerApk;
    }

    private static boolean isInnerApk(String name) {
        String n = name.toLowerCase(Locale.US);
        if (!n.endsWith(".apk")) return false;
        // Only top-level APKs and one folder deep (some APKM layouts) — never OBB payload trees.
        int slashes = 0;
        for (int i = 0; i < n.length(); i++) if (n.charAt(i) == '/') slashes++;
        return slashes <= 1 && !n.startsWith("android/");
    }

    private static boolean looksLikeSplit(String name) {
        String n = baseName(name).toLowerCase(Locale.US);
        return n.startsWith("config.") || n.startsWith("split_") || n.contains(".config.")
                || n.startsWith("split-") || n.contains("_config.");
    }

    private static String baseName(String entry) {
        return entry.substring(entry.lastIndexOf('/') + 1);
    }

    /** Name of the APK entry that should be treated as the app, or null. */
    public static String findBaseEntry(ZipFile zf) {
        ZipEntry best = null;
        ZipEntry bestAny = null;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory() || !isInnerApk(e.getName())) continue;
            if (baseName(e.getName()).equalsIgnoreCase("base.apk")) return e.getName();
            if (bestAny == null || e.getSize() > bestAny.getSize()) bestAny = e;
            if (!looksLikeSplit(e.getName()) && (best == null || e.getSize() > best.getSize())) best = e;
        }
        if (best != null) return best.getName();
        return bestAny == null ? null : bestAny.getName();
    }

    /**
     * Extracts the base APK (and the other APKs as splits) into destDir.
     * @throws EngineAbortException when the bundle has no usable APK.
     */
    public static Result unpack(File bundle, File destDir, EngineLog log) throws IOException {
        Result res = new Result();
        destDir.mkdirs();
        try (ZipFile zf = new ZipFile(bundle)) {
            String baseEntry = findBaseEntry(zf);
            if (baseEntry == null) throw new EngineAbortException("This archive contains no APK files.");
            res.baseEntry = baseEntry;
            res.base = extract(zf, zf.getEntry(baseEntry), new File(destDir, "base.apk"));
            log.line("bundle: base = " + baseEntry);

            Enumeration<? extends ZipEntry> en = zf.entries();
            int i = 0;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !isInnerApk(e.getName()) || e.getName().equals(baseEntry)) continue;
                try {
                    File out = extract(zf, e, new File(destDir, "split_" + (i++) + ".apk"));
                    res.splits.add(out);
                    res.splitNames.add(baseName(e.getName()));
                } catch (IOException ex) {
                    log.line("bundle: could not extract split " + e.getName(), ex);
                }
            }
        }
        return res;
    }

    private static File extract(ZipFile zf, ZipEntry e, File out) throws IOException {
        try (InputStream in = new BufferedInputStream(zf.getInputStream(e));
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            Io.copy(in, os, MAX_INNER_APK);
        }
        return out;
    }
}
