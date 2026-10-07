package com.apklens.app.engine;

import java.util.ArrayList;
import java.util.List;

public class EngineResult {
    public String status = "FAILED";      // SUCCESS | PARTIAL | FAILED | CANCELLED
    public String message = "";
    public String projectId = "";
    public String projectName = "";
    public String apkName = "";
    public String apkPackage = "";
    public String versionName = "";
    public String appLabel = "";
    public int permCount;

    public String zipMode = "";
    public String zipUri = "";
    public String zipFilePath = "";
    public String pathDisplay = "";
    public long zipSize;

    public long durationMs;
    public int classCount, smaliCount, resCount, assetCount, libCount, dexCount, errorCount;
    public int kotlinClasses;

    // ---- analysis (shown on the Result screen, written to analysis-report.txt) ----
    public int minSdk, targetSdk;
    public int dangerousPerms, specialPerms;
    public boolean debuggable, cleartext, allowBackup;
    public int exportedComponents;
    /** 0-100: share of classes with 1-2 character names. */
    public int obfuscationPct;
    public String obfuscationLevel = "";
    public int urlCount, secretCount;
    public String frameworkHint = "";
    public final List<String> libraries = new ArrayList<>();

    /** True when the run died of OutOfMemoryError (the service may retry with fewer threads). */
    public boolean outOfMemory;

    /** Problems the user should know about — a non-empty list makes the status PARTIAL. */
    public final List<String> warnings = new ArrayList<>();
    /** Purely informational lines (bundle unpacked, deobfuscation on…) — never affect status. */
    public final List<String> notes = new ArrayList<>();
    public final List<String> errorSamples = new ArrayList<>();
}
