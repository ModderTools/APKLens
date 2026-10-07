package com.apklens.app.engine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.impl.NoOpCodeCache;

/**
 * The full conversion pipeline:
 *   scan → (unpack bundle) → jadx load → resource decode → per-class Java reconstruction (+ smali)
 *   → native libs / META-INF / raw extras → analysis → project tree → report → streaming ZIP.
 *
 * This class is intentionally free of android.* imports (so it can be exercised on a plain JVM).
 */
public final class Pipeline {

    private Pipeline() {}

    public static EngineResult run(EngineConfig cfg, ProgressListener p, EngineLog log) {
        long t0 = System.currentTimeMillis();
        EngineResult r = new EngineResult();
        r.projectName = cfg.projectName;
        r.apkName = cfg.apkName;
        r.projectId = String.valueOf(t0);
        JadxDecompiler dx = null;

        try {
            // ---------------- Stage: SCAN ----------------
            p.stage("SCAN", "Analyzing APK");
            p.percent(1);
            checkDiskSpace(cfg, r, log);

            File apkFile = cfg.apkFile;
            List<File> splitApks = new ArrayList<>();
            List<String> splitNames = new ArrayList<>();
            boolean bundle;
            try (ZipFile probe = new ZipFile(apkFile)) {
                bundle = BundleUnpacker.isBundle(probe);
            }
            if (bundle) {
                p.stage("SCAN", "Unpacking split-APK bundle");
                BundleUnpacker.Result b = BundleUnpacker.unpack(apkFile,
                        new File(cfg.workDir, "bundle"), log);
                apkFile = b.base;
                splitApks = b.splits;
                splitNames = b.splitNames;
                r.notes.add("Split-APK bundle: decompiled \"" + b.baseEntry + "\""
                        + (splitNames.isEmpty() ? "." : " (+ " + splitNames.size()
                        + " split APK(s) scanned for native libraries)."));
            }

            try (ZipFile zf = new ZipFile(apkFile)) {
                SafeZip.Stats st = SafeZip.scan(zf, log);
                r.dexCount = st.dexCount;
                if (st.entries == 0) throw new EngineAbortException("This file is not a valid APK (the archive is empty).");
                if (st.dexCount == 0 && !st.hasManifest) {
                    throw new EngineAbortException("This ZIP is not an APK: it has no AndroidManifest.xml and no "
                            + "classes.dex. (Split bundles .xapk/.apks/.apkm are supported; other archives are not.)");
                }
                if (st.dexCount == 0) r.warnings.add("No DEX bytecode found — only resources/assets will be extracted.");
                r.frameworkHint = frameworkHint(st.markers);

                // ---------------- Stage: LOAD + DECODE RESOURCES ----------------
                p.stage("DECODE", "Decoding DEX and resources");
                p.percent(5);
                File jadxOut = new File(cfg.workDir, "jadx");
                JadxArgs args = new JadxArgs();
                args.setInputFiles(Collections.singletonList(apkFile));
                args.setOutDir(jadxOut);
                args.setThreadsCount(Math.max(1, cfg.threads));
                args.setShowInconsistentCode(cfg.showInconsistent);
                args.setFallbackMode(cfg.fallback);
                args.setDeobfuscationOn(cfg.deobf);
                // Every class is decompiled exactly once and written straight to disk, so the
                // in-memory code cache would only hoard RAM until the process dies on big APKs.
                args.setCodeCache(NoOpCodeCache.INSTANCE);
                if (cfg.deobf) r.notes.add("Deobfuscation on: short names were given readable aliases in the Java output; smali keeps the original names.");

                dx = new JadxDecompiler(args);
                log.line("jadx: loading " + apkFile.getName());
                dx.load();
                List<JavaClass> everyClass = dx.getClassesWithInners();
                log.line("jadx: loaded " + everyClass.size() + " classes (incl. inner)");
                p.percent(12);

                p.stage("RES", "Decoding resources & manifest");
                try {
                    dx.saveResources();
                } catch (Throwable t) {
                    if (t instanceof OutOfMemoryError) throw (OutOfMemoryError) t;
                    r.warnings.add("Resource decoding failed — raw res/ and assets/ copied instead.");
                    log.line("saveResources failed", t);
                }
                importJadxOutput(jadxOut, cfg.outDir, r, log, zf);
                ManifestInfo mi = ManifestInfo.parse(new File(cfg.outDir, "AndroidManifest.xml"),
                        new File(cfg.outDir, "app/src/main/res/values/strings.xml"));
                applyManifest(r, mi);
                if (mi.pkg.isEmpty() && new File(cfg.outDir, "AndroidManifest.xml").isFile()) {
                    r.warnings.add("Could not parse the decoded manifest for metadata.");
                }
                p.percent(20);

                // ---------------- Stage: DECOMPILE ----------------
                final boolean smaliOn = cfg.smali;
                final int decompileSpan = smaliOn ? 40 : 48;
                final int baseDecompile = 20;

                p.stage("DECOMPILE", "Reconstructing Java sources");
                // Group every class (top-level AND inner) under its top-level class: jadx emits the
                // Java for inner classes inside the top-level file, but smali is one file per class.
                Map<JavaClass, List<JavaClass>> groups = new LinkedHashMap<>();
                for (JavaClass c : everyClass) {
                    JavaClass top = topOf(c);
                    List<JavaClass> g = groups.get(top);
                    if (g == null) { g = new ArrayList<>(); groups.put(top, g); }
                    g.add(c);
                }
                final int totalGroups = groups.size();
                log.line("decompile: " + totalGroups + " top-level classes");

                final File javaRoot = new File(cfg.outDir, "app/src/main/java");
                final File smaliRoot = new File(cfg.outDir, "smali");
                final AtomicInteger done = new AtomicInteger();
                final AtomicInteger errors = new AtomicInteger();
                final AtomicInteger javaCount = new AtomicInteger();
                final AtomicInteger smaliCount = new AtomicInteger();
                final AtomicInteger renamed = new AtomicInteger();
                final AtomicBoolean oom = new AtomicBoolean();
                final Set<String> usedJava = ConcurrentHashMap.newKeySet();
                final Set<String> usedSmali = ConcurrentHashMap.newKeySet();
                final CodeScanner scanner = cfg.analyze ? new CodeScanner() : null;
                final List<String> kotlinFiles = Collections.synchronizedList(new ArrayList<>());
                final List<String[]> errorSamples = Collections.synchronizedList(new ArrayList<>());
                List<Future<?>> futures = new ArrayList<>();
                ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, cfg.threads));

                try {
                    for (Map.Entry<JavaClass, List<JavaClass>> entry : groups.entrySet()) {
                        final JavaClass top = entry.getKey();
                        final List<JavaClass> members = entry.getValue();
                        futures.add(pool.submit(() -> {
                            if (p.isCancelled() || oom.get()) throw new CancelledException();
                            try {
                                String code = null;
                                try {
                                    code = top.getCode();
                                    if (code != null && !code.isEmpty()) {
                                        String rel = unique(usedJava, relForClass(top.getFullName(), ".java"), renamed);
                                        Io.writeString(new File(javaRoot, rel), code);
                                        javaCount.incrementAndGet();
                                        if (KotlinDetector.looksLikeKotlin(top.getFullName(), code)) {
                                            kotlinFiles.add(top.getFullName());
                                        }
                                    } else {
                                        errors.incrementAndGet();
                                        errorSamples.add(new String[]{top.getFullName(), "empty output"});
                                    }
                                } catch (CancelledException ce) {
                                    throw ce;
                                } catch (OutOfMemoryError e) {
                                    throw e;
                                } catch (Throwable t) {
                                    errors.incrementAndGet();
                                    if (errorSamples.size() < 40) {
                                        errorSamples.add(new String[]{top.getFullName(), String.valueOf(t)});
                                    }
                                }
                                if (scanner != null) {
                                    try { scanner.scan(top.getFullName(), simpleName(top.getRawName()), code); }
                                    catch (Throwable ignored) {}
                                }
                                if (smaliOn) {
                                    for (JavaClass m : members) {
                                        try {
                                            String smali = m.getSmali();
                                            if (smali != null && !smali.isEmpty()) {
                                                // Raw (pre-deobfuscation) name: it is what the smali header declares.
                                                String rel = unique(usedSmali,
                                                        relForClass(m.getRawName(), ".smali"), renamed);
                                                Io.writeString(new File(smaliRoot, rel), smali);
                                                smaliCount.incrementAndGet();
                                            }
                                        } catch (OutOfMemoryError e) {
                                            throw e;
                                        } catch (Throwable t) {
                                            // smali renderer unavailable or class failed — non-fatal
                                        }
                                    }
                                }
                            } catch (OutOfMemoryError e) {
                                oom.set(true);
                                throw e;
                            }
                            // NOTE: do NOT call top.unload() here. Other workers may still be
                            // decompiling classes that depend on this one; unloading it makes jadx
                            // throw NullPointerExceptions and silently degrades their output.
                            int d = done.incrementAndGet();
                            if (d % 20 == 0 || d == totalGroups) {
                                p.percent(baseDecompile + (int) ((long) d * decompileSpan / Math.max(1, totalGroups)));
                                p.detail("Reconstructing classes " + d + "/" + totalGroups);
                            }
                        }));
                    }
                    for (Future<?> f : futures) {
                        try { f.get(); } catch (Exception ignored) {}
                        if (oom.get() || p.isCancelled()) break;
                    }
                } finally {
                    pool.shutdownNow(); // on cancel/OOM: don't leave queued classes running
                }
                if (oom.get()) throw new OutOfMemoryError("decompile worker ran out of memory");
                if (p.isCancelled()) throw new CancelledException();
                r.classCount = javaCount.get();
                r.smaliCount = smaliCount.get();
                r.errorCount = errors.get();
                r.kotlinClasses = kotlinFiles.size();
                if (renamed.get() > 0) {
                    r.notes.add(renamed.get() + " file(s) were renamed with a __N suffix because obfuscated class "
                            + "names differ only by letter case (they would overwrite each other on Windows/macOS).");
                }
                log.line("decompile: wrote " + r.classCount + " java + " + r.smaliCount
                        + " smali, errors=" + r.errorCount);
                p.percent(baseDecompile + decompileSpan);

                // ---------------- Stage: BUILD ----------------
                p.stage("BUILD", "Extracting native libs, META-INF and extras");
                List<String> warns = r.warnings;

                // native libraries → app/src/main/jniLibs/<abi>/
                File jniRoot = new File(cfg.outDir, "app/src/main/jniLibs");
                r.libCount = SafeZip.copyMatching(zf,
                        (name, e) -> name.startsWith("lib/") && name.endsWith(".so"),
                        "lib/", jniRoot, 512 * Io.MB, warns, log);
                for (int i = 0; i < splitApks.size(); i++) {
                    try (ZipFile sz = new ZipFile(splitApks.get(i))) {
                        r.libCount += SafeZip.copyMatching(sz,
                                (name, e) -> name.startsWith("lib/") && name.endsWith(".so"),
                                "lib/", jniRoot, 512 * Io.MB, warns, log);
                        if (hasDex(sz)) {
                            warns.add("Split \"" + splitNames.get(i)
                                    + "\" contains DEX code that was not decompiled (feature module).");
                        }
                    } catch (IOException ex) {
                        log.line("split scan failed: " + splitNames.get(i), ex);
                    }
                }

                if (cfg.metaInf) {
                    File meta = new File(cfg.outDir, "META-INF");
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
                    Set<String> skip = new HashSet<>();
                    skip.add("AndroidManifest.xml");
                    skip.add("resources.arsc");
                    skip.addAll(st.dexNames);
                    SafeZip.copyRootFiles(zf, new File(cfg.outDir, "apk/root-files"), skip, warns, log);
                }

                // ---------------- analysis → report ----------------
                if (scanner != null) {
                    r.obfuscationPct = scanner.obfuscationPercent();
                    r.obfuscationLevel = CodeScanner.obfuscationLevel(r.obfuscationPct);
                    r.urlCount = scanner.urlCount();
                    r.secretCount = scanner.secretCount();
                    for (Map.Entry<String, Integer> e : scanner.libraries().entrySet()) {
                        r.libraries.add(e.getKey());
                    }
                }
                r.durationMs = System.currentTimeMillis() - t0; // so the report shows real elapsed time
                r.durationMs = System.currentTimeMillis() - t0; // so the report shows real time
                writeReports(cfg, r, mi, scanner, kotlinFiles, errorSamples, st);
                p.percent(84);

                // ---------------- Stage: ZIP ----------------
                p.stage("ZIP", "Creating ZIP archive");
                OutputStream os = cfg.sink.open();
                long zt0 = System.currentTimeMillis();
                long written = ZipWriter.write(cfg.outDir, os, new ProgressListener() {
                    @Override public void stage(String k, String l) {}
                    @Override public void detail(String line) { p.detail(line); }
                    @Override public void percent(int zipPct) { p.percent(84 + zipPct * 15 / 100); } // 84..99
                    @Override public boolean isCancelled() { return p.isCancelled(); }
                });
                cfg.sink.commit();
                r.zipSize = written > 0 ? written : sinkSize(cfg.sink);
                log.line("zip: committed " + Io.human(r.zipSize) + " in "
                        + (System.currentTimeMillis() - zt0) + "ms");
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
            }
        } catch (CancelledException c) {
            r.status = "CANCELLED";
            r.message = "Cancelled by user.";
            abort(cfg);
        } catch (EngineAbortException e) {
            r.status = "FAILED";
            r.message = e.getMessage();
            abort(cfg);
        } catch (OutOfMemoryError oom) {
            r.status = "FAILED";
            r.outOfMemory = true;
            r.message = "Ran out of memory. Lower the thread count in Settings or use a smaller APK.";
            abort(cfg);
        } catch (java.util.zip.ZipException e) {
            r.status = "FAILED";
            r.message = "This file is not a valid APK/ZIP archive (" + e.getMessage() + "). It may be corrupted or incomplete.";
            abort(cfg);
        } catch (Exception e) {
            r.status = "FAILED";
            r.message = "Conversion failed: " + e;
            abort(cfg);
        } catch (Throwable t) {
            // Engine-level Errors (NoSuchMethodError, NoClassDefFoundError, …) land here
            // so the UI always gets a clean failure and the sink never leaks a pending file.
            r.status = "FAILED";
            r.message = "Conversion failed (engine error): " + t;
            abort(cfg);
        } finally {
            if (dx != null) {
                try { dx.close(); } catch (Throwable ignored) {}
            }
        }
        r.durationMs = System.currentTimeMillis() - t0;
        return r;
    }

    // ------------------------------------------------------------------

    private static void abort(EngineConfig cfg) {
        try { cfg.sink.abort(); } catch (Throwable ignored) {}
    }

    private static void checkDiskSpace(EngineConfig cfg, EngineResult r, EngineLog log) {
        try {
            cfg.workDir.mkdirs();
            long apk = cfg.apkFile.length();
            long free = cfg.workDir.getUsableSpace();
            log.line("disk: apk=" + Io.human(apk) + " free=" + Io.human(free));
            if (free <= 0) return; // unknown — don't block
            long need = Math.max(128L * Io.MB, apk * 3);
            if (free < need) {
                throw new EngineAbortException("Not enough free storage: about " + Io.human(need)
                        + " is needed for this APK, only " + Io.human(free) + " is available.");
            }
            if (free < apk * 8) {
                r.notes.add("Free storage is low for an APK this size — if the conversion fails, "
                        + "turn off Smali output in Settings.");
            }
        } catch (EngineAbortException e) {
            throw e;
        } catch (Throwable ignored) {}
    }

    private static boolean hasDex(ZipFile zf) {
        java.util.Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            if (Io.isDexName(en.nextElement().getName())) return true;
        }
        return false;
    }

    private static void applyManifest(EngineResult r, ManifestInfo mi) {
        r.apkPackage = mi.pkg;
        r.versionName = mi.versionName;
        r.appLabel = mi.label;
        r.permCount = mi.permissions.size();
        r.dangerousPerms = mi.dangerous.size();
        r.specialPerms = mi.special.size();
        r.minSdk = mi.minSdk;
        r.targetSdk = mi.targetSdk;
        r.debuggable = mi.debuggable;
        r.cleartext = mi.cleartext;
        r.allowBackup = mi.allowBackup;
        r.exportedComponents = mi.exported.size();
    }

    private static String frameworkHint(Set<String> m) {
        if (m.contains("libflutter.so") || m.contains("flutter_assets"))
            return "Flutter — app logic is compiled into native code (libapp.so); the Java output is only the engine glue.";
        if (m.contains("index.android.bundle") || m.contains("libreactnativejni.so") || m.contains("libhermes.so"))
            return "React Native — app logic lives in assets/index.android.bundle (JavaScript or Hermes bytecode), not in Java.";
        if (m.contains("libil2cpp.so") || m.contains("libunity.so") || m.contains("unity-data"))
            return "Unity — game logic is in libil2cpp.so + global-metadata.dat (or Mono assemblies), not in Java.";
        if (m.contains("libmonodroid.so") || m.contains("xamarin-dll"))
            return "Xamarin/.NET — app logic lives in assemblies/*.dll, not in Java.";
        if (m.contains("assets/www"))
            return "Cordova/Ionic — app logic is HTML/JavaScript under assets/www.";
        if (m.contains("libgodot_android.so") || m.contains("libgodot.so"))
            return "Godot — game logic is in the .pck/native engine, not in Java.";
        return "";
    }

    private static JavaClass topOf(JavaClass c) {
        try {
            JavaClass t = c.getTopParentClass();
            return t == null ? c : t;
        } catch (Throwable t) {
            return c;
        }
    }

    private static String simpleName(String fullName) {
        if (fullName == null) return "";
        return fullName.substring(fullName.lastIndexOf('.') + 1);
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

    /**
     * Returns a path no other class has claimed. Comparison is case-INSENSITIVE because the ZIP
     * is usually opened on Windows/macOS, where obfuscated pairs like a/A.java and a/a.java
     * would otherwise silently overwrite each other.
     */
    private static String unique(Set<String> used, String rel, AtomicInteger renamed) {
        if (used.add(rel.toLowerCase(Locale.ROOT))) return rel;
        int dot = rel.lastIndexOf('.');
        String stem = dot > 0 ? rel.substring(0, dot) : rel;
        String ext = dot > 0 ? rel.substring(dot) : "";
        for (int i = 2; ; i++) {
            String cand = stem + "__" + i + ext;
            if (used.add(cand.toLowerCase(Locale.ROOT))) {
                renamed.incrementAndGet();
                return cand;
            }
        }
    }

    private static void importJadxOutput(File jadxOut, File out, EngineResult r,
                                         EngineLog log, ZipFile zf) throws IOException {
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

    // ------------------------------------------------------------------ report

    private static void writeReports(EngineConfig cfg, EngineResult r, ManifestInfo mi, CodeScanner scanner,
                                     List<String> kotlinFiles, List<String[]> errorSamples,
                                     SafeZip.Stats st) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("==============================================================\n");
            sb.append(" ApkLens — Reconstruction Analysis Report\n");
            sb.append("==============================================================\n\n");
            sb.append("Project          : ").append(cfg.projectName).append('\n');
            sb.append("APK              : ").append(cfg.apkName).append('\n');
            if (!r.appLabel.isEmpty()) sb.append("App name         : ").append(r.appLabel).append('\n');
            if (!r.apkPackage.isEmpty()) sb.append("Package          : ").append(r.apkPackage).append('\n');
            if (!r.versionName.isEmpty()) {
                sb.append("Version          : ").append(r.versionName);
                if (!mi.versionCode.isEmpty()) sb.append(" (").append(mi.versionCode).append(')');
                sb.append('\n');
            }
            if (mi.minSdk > 0 || mi.targetSdk > 0) {
                sb.append("SDK              : min ").append(mi.minSdk > 0 ? mi.minSdk : "?")
                        .append(" · target ").append(mi.targetSdk > 0 ? mi.targetSdk : "?").append('\n');
            }
            if (!mi.launcher.isEmpty()) sb.append("Launcher         : ").append(mi.launcher).append('\n');
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

            if (!r.frameworkHint.isEmpty()) {
                sb.append("Framework detected\n--------------------------------\n  ")
                        .append(r.frameworkHint).append("\n\n");
            }

            // ---- security-relevant manifest facts ----
            sb.append("Manifest security flags\n--------------------------------\n");
            sb.append("  debuggable            : ").append(flag(mi.debuggable, "YES — can be attached to by a debugger", "no")).append('\n');
            sb.append("  allowBackup           : ").append(mi.allowBackup ? "yes (adb backup can copy app data)" : "no").append('\n');
            sb.append("  usesCleartextTraffic  : ").append(mi.cleartext ? "YES — plain HTTP allowed" : "not set").append('\n');
            sb.append("  testOnly              : ").append(mi.testOnly ? "YES" : "no").append('\n');
            sb.append("  networkSecurityConfig : ").append(mi.hasNetworkSecurityConfig ? "present (see res/xml/)" : "none").append('\n');
            sb.append("  components            : ").append(mi.activities).append(" activities, ")
                    .append(mi.services).append(" services, ").append(mi.receivers).append(" receivers, ")
                    .append(mi.providers).append(" providers\n\n");

            sb.append("Permissions (").append(mi.permissions.size()).append(" total, ")
                    .append(mi.dangerous.size()).append(" runtime-dangerous, ")
                    .append(mi.special.size()).append(" special/high-impact)\n");
            sb.append("--------------------------------\n");
            if (mi.permissions.isEmpty()) sb.append("  (none declared)\n");
            for (String pm : mi.dangerous) sb.append("  [DANGEROUS] ").append(pm).append('\n');
            for (String pm : mi.special) sb.append("  [SPECIAL]   ").append(pm).append('\n');
            for (String pm : mi.permissions) {
                if (!mi.dangerous.contains(pm) && !mi.special.contains(pm)) sb.append("              ").append(pm).append('\n');
            }
            sb.append('\n');

            if (!mi.exported.isEmpty()) {
                sb.append("Components reachable by other apps (").append(mi.exported.size()).append(")\n");
                sb.append("--------------------------------\n");
                int n = 0;
                for (String ex : mi.exported) {
                    if (n++ >= 80) { sb.append("  … and ").append(mi.exported.size() - 80).append(" more\n"); break; }
                    sb.append("  ").append(ex).append('\n');
                }
                sb.append('\n');
            }

            // ---- code-derived findings ----
            if (scanner != null) {
                sb.append("Obfuscation\n--------------------------------\n");
                sb.append("  ").append(r.obfuscationLevel).append(" — ").append(r.obfuscationPct)
                        .append("% of classes have 1–2 character names\n");
                if (r.obfuscationPct >= 12 && !cfg.deobf) {
                    sb.append("  Tip: enable \"Deobfuscation\" in Settings for readable aliases.\n");
                }
                sb.append('\n');

                Map<String, Integer> libs = scanner.libraries();
                sb.append("Libraries / SDKs recognised by package name\n--------------------------------\n");
                if (libs.isEmpty()) sb.append("  (none recognised)\n");
                for (Map.Entry<String, Integer> e : libs.entrySet()) {
                    sb.append("  ").append(e.getKey()).append("  (").append(e.getValue()).append(" classes)\n");
                }
                sb.append('\n');

                List<String[]> secrets = scanner.secrets();
                sb.append("Possible hard-coded secrets (").append(secrets.size()).append(")\n");
                sb.append("--------------------------------\n");
                if (secrets.isEmpty()) {
                    sb.append("  (no known key patterns found)\n");
                } else {
                    sb.append("  Pattern matches only — values are masked; confirm in the source.\n");
                    for (String[] s : secrets) {
                        sb.append("  ").append(s[0]).append(": ").append(s[1]).append("   in ").append(s[2]).append('\n');
                    }
                }
                sb.append('\n');

                List<Map.Entry<String, Integer>> urls = scanner.sortedUrls();
                sb.append("URLs found in code (").append(urls.size()).append(" unique) — full list in analysis/urls.txt\n");
                sb.append("--------------------------------\n");
                int shown = 0;
                for (Map.Entry<String, Integer> e : urls) {
                    if (shown++ >= 25) break;
                    sb.append("  ").append(e.getKey()).append('\n');
                }
                if (urls.isEmpty()) sb.append("  (none)\n");
                sb.append('\n');
                if (!urls.isEmpty()) {
                    StringBuilder ub = new StringBuilder();
                    for (Map.Entry<String, Integer> e : urls) {
                        ub.append(e.getKey()).append("    x").append(e.getValue()).append('\n');
                    }
                    Io.writeString(new File(cfg.outDir, "analysis/urls.txt"), ub.toString());
                }
            }

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
            if (!r.notes.isEmpty()) {
                sb.append("Notes:\n--------------------------------\n");
                for (String w : r.notes) sb.append("  • ").append(w).append('\n');
                sb.append('\n');
            }

            sb.append("Output layout:\n");
            sb.append("  AndroidManifest.xml      decoded manifest\n");
            sb.append("  app/src/main/java/       reconstructed Java sources (package tree preserved)\n");
            sb.append("  app/src/main/res/        decoded resources\n");
            sb.append("  app/src/main/assets/     original assets (verbatim)\n");
            sb.append("  app/src/main/jniLibs/    native libraries (verbatim)\n");
            sb.append("  smali/                   exact bytecode, one file per class incl. inner classes\n");
            sb.append("  analysis/urls.txt        every URL string found in the code\n");
            sb.append("  apk/                     raw extras (dex/unknown/root files)\n");
            sb.append("  META-INF/                signing metadata (when enabled)\n");
            sb.append('\n').append("Generated by ApkLens · engine: jadx-core (Apache-2.0)\n");
            Io.writeString(new File(cfg.outDir, "analysis-report.txt"), sb.toString());

            Io.writeString(new File(cfg.outDir, "README.txt"),
                    "This is a RECONSTRUCTED project generated by ApkLens from a compiled APK.\n"
                            + "It is intended for reading and study, NOT for building:\n"
                            + "decompiled code frequently does not recompile and is not the original source.\n\n"
                            + "See analysis-report.txt for statistics, permissions, security flags,\n"
                            + "recognised libraries, URLs, Kotlin-origin class detection, warnings and the\n"
                            + "list of classes that failed to reconstruct.\n");
        } catch (IOException ignored) {}
    }

    private static String flag(boolean on, String yes, String no) { return on ? yes : no; }

    /** ZIP size after commit (direct mode only; fallback when byte counting yielded nothing). */
    private static long sinkSize(ZipSink sink) {
        try {
            if ("direct".equals(sink.mode()) && sink.filePath() != null) {
                File f = new File(sink.filePath());
                if (f.isFile()) return f.length();
            }
        } catch (Throwable ignored) {}
        return 0;
    }
}
