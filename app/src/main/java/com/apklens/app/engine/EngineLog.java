package com.apklens.app.engine;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Simple file logger for post-mortem analysis of conversions. */
public final class EngineLog implements Closeable {

    private final Writer w;
    private final long t0 = System.currentTimeMillis();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private EngineLog(Writer w) { this.w = w; }

    public static EngineLog toFile(File f) {
        try {
            f.getParentFile().mkdirs();
            OutputStream os = new FileOutputStream(f);
            return new EngineLog(new OutputStreamWriter(os, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new EngineLog(nullWriter());
        }
    }

    private static Writer nullWriter() {
        return new Writer() {
            @Override public void write(char[] c, int o, int l) {}
            @Override public void flush() {}
            @Override public void close() {}
        };
    }

    public synchronized void line(String s) {
        try {
            w.write(fmt.format(new Date()) + "  " + s + "\n");
            w.flush();
        } catch (Exception ignored) {}
    }

    public synchronized void line(String s, Throwable t) {
        line(s + " — " + t);
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(6, st.length); i++) line("    at " + st[i]);
    }

    public long elapsedMs() { return System.currentTimeMillis() - t0; }

    @Override public void close() {
        try { w.close(); } catch (Exception ignored) {}
    }
}
