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
    public int permCount;

    public String zipMode = "";
    public String zipUri = "";
    public String zipFilePath = "";
    public String pathDisplay = "";
    public long zipSize;

    public long durationMs;
    public int classCount, smaliCount, resCount, assetCount, libCount, dexCount, errorCount;
    public int kotlinClasses;
    public final List<String> warnings = new ArrayList<>();
    public final List<String> errorSamples = new ArrayList<>();
}
