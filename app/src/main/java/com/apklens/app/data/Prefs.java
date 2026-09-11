package com.apklens.app.data;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("apklens", Context.MODE_PRIVATE);
    }

    public boolean get(String key, boolean def) { return sp.getBoolean(key, def); }
    public void set(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }

    public int threads() {
        int cores = Runtime.getRuntime().availableProcessors();
        int def = Math.max(2, Math.min(6, cores - 1));
        return sp.getInt("threads", def);
    }

    public void setThreads(int t) { sp.edit().putInt("threads", Math.max(1, Math.min(8, t))).apply(); }
}
