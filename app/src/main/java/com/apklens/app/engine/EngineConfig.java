package com.apklens.app.engine;

import java.io.File;

public class EngineConfig {
    public File apkFile;
    public File workDir;       // volatile scratch (deleted after run)
    public File outDir;        // assembled project tree (zipped, then deleted)
    public String projectName;
    public String apkName;
    public boolean fallback;
    public boolean showInconsistent;
    public boolean smali;
    public boolean rawDex;
    public boolean metaInf;
    public int threads = 4;
    public ZipSink sink;
}
