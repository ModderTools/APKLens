package com.apklens.app.engine;

import java.io.File;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Quick, cheap APK overview used on the New Project screen before a full conversion. */
public final class ZipPreview {

    public static class Summary {
        public int dexCount;
        public boolean hasManifest;
        public boolean hasArsc;
        public int assetCount;
        public int resCount;
        public Set<String> abis = new TreeSet<>();
        /** A readable ZIP container (says nothing about whether it is an APK). */
        public boolean valid;
        /** XAPK / APKS / APKM: a ZIP that contains the real APK(s). */
        public boolean bundle;
        public int innerApks;
        public String baseApk = "";

        /** Something ApkLens can actually convert. */
        public boolean convertible() {
            return valid && (bundle || dexCount > 0 || hasManifest);
        }

        @Override public String toString() { return valid ? "ok" : "invalid"; }
    }

    private ZipPreview() {}

    public static Summary scan(File apk) {
        Summary s = new Summary();
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (n.startsWith("/") || n.contains("../")) continue;
                if (Io.isDexName(n)) s.dexCount++;
                else if (n.equals("AndroidManifest.xml")) s.hasManifest = true;
                else if (n.equals("resources.arsc")) s.hasArsc = true;
                else if (n.startsWith("assets/")) s.assetCount++;
                else if (n.startsWith("res/")) s.resCount++;
                else if (n.startsWith("lib/") && n.endsWith(".so")) {
                    String[] parts = n.split("/");
                    if (parts.length >= 3) s.abis.add(parts[1]); // lib/<abi>/x.so
                } else if (n.toLowerCase(java.util.Locale.US).endsWith(".apk")) {
                    s.innerApks++;
                }
            }
            s.valid = true;
            if (s.dexCount == 0 && !s.hasManifest && s.innerApks > 0 && BundleUnpacker.isBundle(zf)) {
                s.bundle = true;
                String base = BundleUnpacker.findBaseEntry(zf);
                s.baseApk = base == null ? "" : base.substring(base.lastIndexOf('/') + 1);
            }
        } catch (java.io.IOException | RuntimeException e) {
            s.valid = false;
        }
        return s;
    }
}
