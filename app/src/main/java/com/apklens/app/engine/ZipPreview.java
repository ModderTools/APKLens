package com.apklens.app.engine;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
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
        public boolean valid;

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
                if (n.matches("classes\\d*\\.dex")) s.dexCount++;
                else if (n.equals("AndroidManifest.xml")) s.hasManifest = true;
                else if (n.equals("resources.arsc")) s.hasArsc = true;
                else if (n.startsWith("assets/")) s.assetCount++;
                else if (n.startsWith("res/")) s.resCount++;
                else if (n.startsWith("lib/") && n.endsWith(".so")) {
                    String[] parts = n.split("/");
                    if (parts.length >= 2) s.abis.add(parts[1]);
                }
            }
            s.valid = true;
            return s;
        } catch (ZipException | java.io.EOFException e) {
            s.valid = false;
            return s;
        } catch (IOException e) {
            s.valid = false;
            return s;
        }
    }
}
