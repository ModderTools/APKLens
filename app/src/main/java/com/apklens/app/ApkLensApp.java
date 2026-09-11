package com.apklens.app;

import android.app.Application;

/** Application entry point. All heavy wiring happens lazily in the service/UI layers. */
public class ApkLensApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Working directories are created lazily by the engine; nothing to do here yet.
    }
}
