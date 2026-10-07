package com.apklens.app;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** Application entry point + crash logging: every uncaught exception is appended to files/crash.log. */
public class ApkLensApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // A fresh process cannot have a conversion running yet (the service is not sticky), so any
        // picked_*/work_* leftovers are from a run the OS killed — they can be hundreds of MB.
        new Thread(() -> {
            try { com.apklens.app.platform.Store.clearWorkCache(this, 10 * 60 * 1000L); } catch (Throwable ignored) {}
        }, "apklens-cache-gc").start();
        final Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                File f = new File(getFilesDir(), "crash.log");
                try (FileOutputStream os = new FileOutputStream(f, true)) {
                    os.write(("\n==== " + new Date() + "  thread=" + t.getName() + " ====\n")
                            .getBytes(StandardCharsets.UTF_8));
                    os.write(Log.getStackTraceString(e).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {}
            if (prior != null) prior.uncaughtException(t, e);
        });
    }
}
