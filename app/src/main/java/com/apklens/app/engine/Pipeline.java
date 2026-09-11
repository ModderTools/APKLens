package com.apklens.app.engine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

/**
 * The full conversion pipeline:
 *   scan → jadx load → resource decode → per-class Java reconstruction (+ smali)
 *   → native libs / META-INF / raw extras → project tree → report → streaming ZIP.
 *
 * This class is intentionally free of android.* imports.
 */
public final class Pipeline {

    private Pipeline() {}

    public static EngineResult run(EngineConfig cfg, ProgressListener p, EngineLog log) {
        long t0 = System.currentTimeMillis();
        EngineResult r = new EngineResult();
        r.projectName = cfg.projectName;
        r.apkName = cfg.apkName;
        r.projectId = String.valueOf(t0);

        try {
            // ---------------- Stage: SCAN ----------------
            p.stage("SCAN", "Analyzing APK");
            p.percent(1);
            try (ZipFile zf = new ZipFile(cfg.apkFile)) {
                SafeZip.Stats st = SafeZip.scan(zf, log);
                r.dexCount = st.dexCount;
                if (st.entries == 0) throw new EngineAbortException("This file is not a valid APK (no entries).");
                if (st.dexCount == 0) r.warnings.add("No DEX bytecode found — only resources/assets will be extracted.");

                // ---------------- Stage: LOAD + DECODE RESOURCES ----------------
                p.stage("DECODE", "Decoding DEX and resources");
                p.percent(5);
                File jadxOut = new File(cfg.workDir, "jadx");
                // jadx writes decoded resources under this dir (layout resolved dynamically)
                JadxArgs args = new JadxArgs();
                args.setInputFiles(Collections.singletonList(cfg.apkFile));
                args.setOutDir(jadxOut);
                args.setThreadsCount(Math.max(1, cfg.threads));
                args.setShowInconsistentCode(cfg.showInconsistent);
                args.setFallbackMode(cfg.fallback);
                JadxDecompiler dx = new JadxDecompiler(args);
                log.line("jadx: loading " + cfg.apkFile.getName());
                dx.load();
                int nClasses = dx.getClasses().size();
                log.line("jadx: loaded " + nClasses + " classes");
                p.percent(12);

                p.stage("RES", "Decoding resources & manifest");
                boolean resOk = true;
                try {
                    dx.saveResources();
                } catch (Throwable t) {
                    resOk = false;
                    r.warnings.add("Resource decoding failed — raw res/ and assets/ copied instead.");
                    log.line("saveResources failed", t);
                }
                importJadxOutput(jadxOut, cfg.outDir, r, log, zf, resOk);
                parseManifestInfo(cfg.outDir, r);
                p.percent(20);

                // ---------------- Stage: DECOMPILE ----------------
                boolean smaliOn = cfg.smali;
                int decompileSpan = smaliOn ? 40 : 48;
                int smaliSpan = smaliOn ? 8 : 0;
                int baseDecompile = 20;

                p.stage("DECOMPILE", "Reconstructing Java sources");
                Map<JavaClass, List<JavaClass>> groups = new LinkedHashMap<>();
                for (JavaClass c : dx.getClasses()) {
                    JavaClass top = topOf(c);
                    List<JavaClass> g = groups.get(top);
                    if (g == null) { g = new ArrayList<>(); groups.put(top, g); }
                    g.add(c);
                }
                int totalGroups = groups.size();
                log.line("decompile: " + totalGroups + " top-level classes");

                File javaRoot = new File(cfg.outDir, "app/src/main/java");
                File smaliRoot = new File(cfg.outDir, "smali");
                AtomicInteger done = new AtomicInteger();
                AtomicInteger errors = new AtomicInteger();
                List<String> kotlinFiles = Collections.synchronizedList(new ArrayList<>());
                List<String[]> errorSamples = Collections.synchronizedList(new ArrayList<>());
                List<Future<?>> futures = new ArrayList<>();
                ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, cfg.threads));

                try {
                    for (Map.Entry<JavaClass, List<JavaClass>> entry : groups.entrySet()) {
                        JavaClass top = entry.getKey();
                        List<JavaClass> members = entry.getValue();
                        futures.add(pool.submit(() -> {
                            if (p.isCancelled()) throw new CancelledException();
                            try {
                                String code = top.getCode();
                                if (code != null && !code.isEmpty()) {
                                    String rel = relForClass(top.getFullName(), ".java");
                                    Io.writeString(new File(javaRoot, rel), code);
                                    r.classCount++;
                                    if (KotlinDetector.looksLikeKotlin(top.getFullName(), code)) {
                                        kotlinFiles.add(top.getFullName());
                                    }
                                } else {
                                    errors.incrementAndGet();
                                    errorSamples.add(new String[]{top.getFullName(), "empty output"});
                                }
                            } catch (CancelledException ce) {
                                throw ce;
                            } catch (Throwable t) {
                                errors.incrementAndGet();
                                if (errorSamples.size() < 40) {
                                    errorSamples.add(new String[]{top.getFullName(), String.valueOf(t)});
                                }
                            }
                            if (smaliOn) {
                                for (JavaClass m : members) {
                                    try {
                                        String smali = m.getSmali();
                                        if (smali != null && !smali.isEmpty()) {
                                            Io.writeString(new File(smaliRoot,
                                                    relForClass(m.getFullName(), ".smali")), smali);
                                            r.smaliCount++;
                                        }
                                    } catch (Throwable t) {
                                        // smali renderer unavailable or class failed — non-fatal
                                    }
                                }
                            }
                            int d = done.incrementAndGet();
                            int pct = baseDecompile
                                    + (int) ((long) d * decompileSpan / Math.max(1, totalGroups));
                            if (d % 20 == 0 || d == totalGroups) {
                                p.percent(pct);
                                p.detail("Reconstructing classes " + d + "/" + totalGroups);
                            }
                        }));
                    }
                    for (Future<?> f : futures) {
                        try { f.get(); } catch (Exception ignored) {}
                    }
                } finally {
                    pool.shutdown();
                }
                if (p.isCancelled()) throw new CancelledException();
                r.errorCount = errors.get();
                r.kotlinClasses = kotlinFiles.size();
                log.line("decompile: wrote " + r.classCount + " classes, errors=" + r.errorCount);
                int pctAfterDecompile = baseDecompile + decompileSpan;
                p.percent(pctAfterDecompile);

                // ---------------- Stage: BUILD ----------------
                p.stage("BUILD", "Extracting native libs, META-INF and extras");
                List<String> warns = r.warnings;

                // native libraries → app/src/main/jniLibs/<abi>/
                File jniRoot = new File(cfg.outDir, "app/src/main/jniLibs");
                r.libCount = SafeZip.copyMatching(zf,
                        (name, e) -> name.startsWith("lib/") && name.endsWith(".so"),
                        jniRoot, 512 * Io.MB, warns, log);

                if (cfg.metaInf) {
                    File meta = new File(cfg.outDir, "META-INF");
                    r.warnings.size(); // no-op keep
                    SafeZip.copyMatching(zf, (name, e) -> SafeZip.isMetaInfArtifact(name),
                            meta, 32 * Io.MB, warns, log);
                }
                if (cfg.rawDex && st.dexCount > 0) {
                    File dexDir = new File(cfg.outDir, "apk/dex");
                    for (String dexName : st.dexNames) {
                        copyOne(zf, dexName, new File(dexDir, dexName), 512 * Io.MB, warns, log);
                    }
                }
                // Small unknown root files (only when jadx didn't already save an unknown/ dir)
                File jadxUnknown = firstDir(new File(jadxOut, "resources/unknown"),
                        new File(jadxOut, "unknown"));
                if (jadxUnknown == null) {
                    SafeZip.Set60 skip = new SafeZip.Set60();
                    skip.add("AndroidManifest.xml");
                    skip.add("resources.arsc");
                    for (String dn : st.dexNames) skip.add(dn);
                    SafeZip.copyRootFiles(zf, new File(cfg.outDir, "apk/root-files"), skip, warns, log);
                }

                writeReports(cfg, r, kotlinFiles, errorSamples, st);
                p.percent(84);

                // ---------------- Stage: ZIP ----------------
                p.stage("ZIP", "Creating ZIP archive");
                OutputStream os = cfg.sink.open();
                long zt0 = System.currentTimeMillis();
                ZipWriter.write(cfg.outDir, os, new ProgressListener() {
                    @Override public void stage(String k, String l) {}
                    @Override public void detail(String line) {
                        p.detail(line);
                    }
                    @Override public void percent(int ignored) {
                        // local zip progress mapped into the final 16%
                    }
                    @Override public boolean isCancelled() { return p.isCancelled(); }
                });
                r.zipSize = guessSize(cfg.sink);
                cfg.sink.commit();
                log.line("zip: committed in " + (System.currentTimeMillis() - zt0) + "ms");
                r.zipMode = cfg.sink.mode();
                r.zipUri = cfg.sink.uriString();
                r.zipFilePath = cfg.sink.filePath();
                r.pathDisplay = cfg.sink.describe();
                p.percent(100);

                r.status = (r.errorCount > 0 || !r.warnings.isEmpty()) ? "PARTIAL" : "SUCCESS";
                if (r.errorCount > 0) {
                    r.message = r.errorCount + " class(es) could not be fully reconstructed — "
                            + "check smali/ and analysis-report.txt.";
                }
                dx = null; // release engine memory before we leave
            }
        } catch (CancelledException c) {
            r.status = "CANCELLED";
            r.message = "Cancelled by user.";
            try { cfg.sink.abort(); } catch (Throwable ignored) {}
        } catch (EngineAbortException e) {
            r.status = "FAILED";
            r.message = e.getMessage();
            try { cfg.sink.abort(); } catch (Throwable ignored) {}
        } catch (OutOfMemoryError oom) {
            r.status = "FAILED";
            r.message = "Ran out of memory. Lower the thread count in Settings or use a smaller APK.";
            try { cfg.sink.abort(); } catch (Throwable ignored) {}
        } catch (Exception e) {
            r.status = "FAILED";
            r.message = "Conversion failed: " + e;
            try { cfg.sink.abort(); } catch (Throwable ignored) {}
        }
        r.durationMs = System.currentTimeMillis() - t0;
        return r;
    }

    // ------------------------------------------------------------------

    private static JavaClass topOf(JavaClass c) {
        try {
            JavaClass t = c.getTopParentClass();
            return t == null ? c : t;
        } catch (Throwable t) {
            return c;
        }
    }

    private static String relForClass(String fullName, String ext) {
        String name = fullName.replace('.', '/');
        if (name.contains("..")) name = name.replace("..", "_");
        StringBuilder b = new StringBuilder();
        for (char ch : name.toCharArray()) {
            b.append((Character.isLetterOrDigit(ch) || ch == '/' || ch == '_' || ch == '$' || ch == '-') ? ch : '_');
        }
        return b + ext;
    }

    private static void importJadxOutput(File jadxOut, File out, EngineResult r,
                                         EngineLog log, ZipFile zf, boolean resOk) throws IOException {
        // Manifest
        File manifest = firstFile(new File(jadxOut, "AndroidManifest.xml"),
                new File(jadxOut, "resources/AndroidManifest.xml"));
        if (manifest != null) {
            Io.copyFile(manifest, new File(out, "AndroidManifest.xml"));
        } else {
            r.warnings.add("Manifest could not be decoded — raw binary manifest kept as AndroidManifest.raw.xml");
            try {
                copyOne(zf, "AndroidManifest.xml", new File(out, "AndroidManifest.raw.xml"),
                        8 * Io.MB, r.warnings, log);
            } catch (Exception ignored) {}
        }

        // res/
        File resDir = firstDir(new File(jadxOut, "resources/res"), new File(jadxOut, "res"));
        if (resDir != null) {
            r.resCount = Io.moveTree(resDir, new File(out, "app/src/main/res"));
        } else {
            r.warnings.add("Decoded res/ not found — copying raw res/ (XML resources stay binary).");
            r.resCount = SafeZip.copyMatching(zf, (n, e) -> n.startsWith("res/"),
                    new File(out, "app/src/main/res"), 128 * Io.MB, r.warnings, log);
        }

        // assets/
        File assets = firstDir(new File(jadxOut, "resources/assets"), new File(jadxOut, "assets"));
        if (assets != null) {
            r.assetCount = Io.moveTree(assets, new File(out, "app/src/main/assets"));
        } else {
            r.assetCount = SafeZip.copyMatching(zf, (n, e) -> n.startsWith("assets/"),
                    new File(out, "app/src/main/assets"), 512 * Io.MB, r.warnings, log);
        }

        // unknown/
        File unknown = firstDir(new File(jadxOut, "resources/unknown"), new File(jadxOut, "unknown"));
        if (unknown != null) {
            Io.moveTree(unknown, new File(out, "apk/unknown"));
        }
    }

    private static void copyOne(ZipFile zf, String entryName, File target,
                                long cap, List<String> warns, EngineLog log) throws IOException {
        ZipEntry e = zf.getEntry(entryName);
        if (e == null) return;
        target.getParentFile().mkdirs();
        try (InputStream in = zf.getInputStream(e);
             OutputStream os = new java.io.FileOutputStream(target)) {
            Io.copy(in, os, cap);
        }
    }

    private static File firstFile(File... candidates) {
        for (File f : candidates) if (f.isFile()) return f;
        return null;
    }

    private static File firstDir(File... candidates) {
        for (File f : candidates) if (f.isDirectory()) return f;
        return null;
    }

    private static void parseManifestInfo(File outDir, EngineResult r) {
        File mf = new File(outDir, "AndroidManifest.xml");
        if (!mf.isFile()) return;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Document d = f.parse(mf);
            Element root = d.getDocumentElement();
            r.apkPackage = root.getAttribute("package");
            String ns = "http://schemas.android.com/apk/res/android";
            Attr vn = root.getAttributeNodeNS(ns, "versionName");
            if (vn != null) r.versionName = vn.getValue();
            NodeList perms = root.getElementsByTagName("uses-permission");
            r.permCount = perms.getLength();
        } catch (Throwable t) {
            r.warnings.add("Could not parse the decoded manifest for metadata.");
        }
    }

    private static void writeReports(EngineConfig cfg, EngineResult r,
                                     List<String> kotlinFiles, List<String[]> errorSamples,
                                     SafeZip.Stats st) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("==============================================================\n");
            sb.append(" ApkLens — Reconstruction Analysis Report\n");
            sb.append("==============================================================\n\n");
            sb.append("Project          : ").append(cfg.projectName).append('\n');
            sb.append("APK              : ").append(cfg.apkName).append('\n');
            if (!r.apkPackage.isEmpty()) sb.append("Package          : ").append(r.apkPackage).append('\n');
            if (!r.versionName.isEmpty()) sb.append("Version          : ").append(r.versionName).append('\n');
            sb.append("DEX files        : ").append(st.dexCount).append('\n');
            sb.append("Classes written  : ").append(r.classCount).append('\n');
            sb.append("Smali files      : ").append(r.smaliCount).append('\n');
            sb.append("Resource files   : ").append(r.resCount).append('\n');
            sb.append("Assets           : ").append(r.assetCount).append('\n');
            sb.append("Native libraries : ").append(r.libCount).append('\n');
            sb.append("Class errors     : ").append(r.errorCount).append('\n');
            sb.append("Duration         : ").append(r.durationMs / 1000.0).append(" s\n\n");

            sb.append("IMPORTANT — WHAT THIS OUTPUT IS\n");
            sb.append("--------------------------------\n");
            sb.append("Everything under app/src/main/java/ is RECONSTRUCTED (decompiled)\n");
            sb.append("Java-syntax source produced from compiled DEX bytecode. It is NOT the\n");
            sb.append("original source code: comments, original formatting and local variable\n");
            sb.append("names are permanently lost during compilation. Obfuscated apps keep\n");
            sb.append("their obfuscated names. This project is for READING and STUDY — it is\n");
            sb.append("not guaranteed to compile or build.\n\n");

            sb.append("Kotlin-origin classes (detected heuristically, code is still Java-syntax):\n");
            sb.append("--------------------------------\n");
            if (kotlinFiles.isEmpty()) {
                sb.append("  (none detected)\n");
            } else {
                int n = 0;
                for (String k : kotlinFiles) {
                    if (n++ >= 60) { sb.append("  … and ").append(kotlinFiles.size() - 60).append(" more\n"); break; }
                    sb.append("  ").append(k).append('\n');
                }
            }
            sb.append('\n');

            if (!errorSamples.isEmpty()) {
                sb.append("Classes with reconstruction errors (first ")
                  .append(errorSamples.size()).append("):\n");
                sb.append("--------------------------------\n");
                for (String[] e : errorSamples) {
                    sb.append("  ").append(e[0]).append(" — ").append(e[1]).append('\n');
                }
                sb.append('\n');
            }

            if (!r.warnings.isEmpty()) {
                sb.append("Warnings:\n--------------------------------\n");
                for (String w : r.warnings) sb.append("  • ").append(w).append('\n');
                sb.append('\n');
            }

            sb.append("Output layout:\n");
            sb.append("  AndroidManifest.xml      decoded manifest\n");
            sb.append("  app/src/main/java/       reconstructed Java sources (package tree preserved)\n");
            sb.append("  app/src/main/res/        decoded resources\n");
            sb.append("  app/src/main/assets/     original assets (verbatim)\n");
            sb.append("  app/src/main/jniLibs/    native libraries (verbatim)\n");
            sb.append("  smali/                   exact bytecode representation (when enabled)\n");
            sb.append("  apk/                     raw extras (dex/unknown/root files)\n");
            sb.append("  META-INF/                signing metadata (when enabled)\n");
            sb.append('\n').append("Generated by ApkLens · engine: jadx-core (Apache-2.0)\n");
            Io.writeString(new File(cfg.outDir, "analysis-report.txt"), sb.toString());

            Io.writeString(new File(cfg.outDir, "README.txt"),
                    "This is a RECONSTRUCTED project generated by ApkLens from a compiled APK.\n"
                    + "It is intended for reading and study, NOT for building:\n"
                    + "decompiled code frequently does not recompile and is not the original source.\n\n"
                    + "See analysis-report.txt for statistics, Kotlin-origin class detection,\n"
                    + "warnings and the list of classes that failed to reconstruct.\n");
        } catch (IOException ignored) {}
    }

    private static long guessSize(ZipSink sink) {
        try {
            if (sink instanceof DirectFileSink) {
                String p = sink.filePath();
                if (p != null) {
                    File f = new File(p);
                    if (f.isFile()) return f.length();
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }
}
