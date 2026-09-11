package com.apklens.app.engine;

public interface ProgressListener {
    void stage(String key, String label);
    void detail(String line);
    void percent(int overallPercent);
    boolean isCancelled();
}
