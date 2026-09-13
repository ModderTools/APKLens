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
