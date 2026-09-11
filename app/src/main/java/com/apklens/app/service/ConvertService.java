package com.apklens.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.apklens.app.R;
import com.apklens.app.data.Prefs;
import com.apklens.app.data.ProjectRecord;
import com.apklens.app.data.ProjectStore;
import com.apklens.app.engine.DirectFileSink;
import com.apklens.app.engine.EngineConfig;
import com.apklens.app.engine.EngineLog;
import com.apklens.app.engine.EngineResult;
import com.apklens.app.engine.Io;
import com.apklens.app.engine.ProgressListener;
import com.apklens.app.engine.ZipSink;                    // FIXED: needed for buildSink's return type
import com.apklens.app.platform.MediaStoreSink;
import com.apklens.app.platform.Store;
import com.apklens.app.ui.MainActivity;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConvertService extends Service {

    public static final String ACTION_CONVERT = "com.apklens.app.CONVERT";
    public static final String ACTION_CANCEL = "com.apklens.app.CANCEL";

    private static volatile boolean alive = false;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    public static boolean isAlive() { return alive; }

    public static void start(Context ctx, String apkPath, String projectName,
                             String apkName, long apkSize) {
        Intent i = new Intent(ctx, ConvertService.class);
        i.setAction(ACTION_CONVERT);
        i.putExtra("apkPath", apkPath);
        i.putExtra("projectName", projectName);
        i.putExtra("apkName", apkName);
        i.putExtra("apkSize", apkSize);
        ctx.startService(i);
    }

    public static void cancel(Context ctx) {
        Intent i = new Intent(ctx, ConvertService.class);
        i.setAction(ACTION_CANCEL);
        ctx.startService(i);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        alive = true;
        ensureChannels();
    }

    @Override
    public void onDestroy() {
        alive = false;
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            EngineState.requestCancel();
            return START_NOT_STICKY;
        }
        if (!ACTION_CONVERT.equals(action)) return START_NOT_STICKY;
        if (EngineState.isRunning()) return START_NOT_STICKY;

        final String apkPath = intent.getStringExtra("apkPath");
        final String projectName = intent.getStringExtra("projectName");
        final String apkName = intent.getStringExtra("apkName");
        final long apkSize = intent.getLongExtra("apkSize", -1);
        if (apkPath == null || projectName == null) return START_NOT_STICKY;

        startFg("Preparing…", 0);
        EngineState.markStarting(projectName);

        EXEC.execute(() -> {
            EngineResult result = null;
            File workDir = new File(getCacheDir(), "work_" + System.currentTimeMillis());
            File picked = new File(apkPath);
            try {
                Prefs prefs = new Prefs(this);
                File outDir = new File(workDir, "out");
                File logDir = new File(getFilesDir(), "logs");
                logDir.mkdirs();
                File logFile = new File(logDir, projectName.replaceAll("\\W+", "_") + ".log");

                EngineConfig cfg = new EngineConfig();
                cfg.apkFile = picked;
                cfg.workDir = workDir;
                cfg.outDir = outDir;
                cfg.projectName = projectName;
                cfg.apkName = apkName;
                cfg.fallback = prefs.get("fallback", false);
                cfg.showInconsistent = prefs.get("inconsistent", true);
                cfg.smali = prefs.get("smali", true);
                cfg.rawDex = prefs.get("rawDex", false);
                cfg.metaInf = prefs.get("metaInf", true);
                cfg.threads = prefs.threads();
                cfg.sink = buildSink(projectName, apkName);

                try (EngineLog log = EngineLog.toFile(logFile)) {
                    ProgressListener prog = new ProgressListener() {
                        private long last = 0;
                        @Override public void stage(String key, String label) {
                            EngineState.update(label, -1, null);
                            updateFg(EngineState.getStage(), EngineState.getPercent());
                        }
                        @Override public void detail(String line) {
                            long now = System.currentTimeMillis();
                            if (now - last > 120) { last = now; EngineState.update(null, -1, line); }
                        }
                        @Override public void percent(int overall) {
                            long now = System.currentTimeMillis();
                            if (now - last > 120) {
                                last = now;
                                EngineState.update(null, overall, null);
                                updateFg(EngineState.getStage(), overall);
                            }
                        }
                        @Override public boolean isCancelled() {
                            return EngineState.isCancelRequested();
                        }
                    };
                    result = com.apklens.app.engine.Pipeline.run(cfg, prog, log);
                }
            } catch (Throwable t) {
                result = new EngineResult();
                result.status = "FAILED";
                result.projectName = projectName;
                result.apkName = apkName;
                result.message = (t instanceof OutOfMemoryError)
                        ? "Ran out of memory. Try fewer decompile threads (Settings) or a smaller APK."
                        : ("Conversion failed: " + t);
            } finally {
                // cleanup volatile working data (keep the log)
                Io.deleteRecursively(workDir);
                Io.deleteRecursively(picked);
            }

            final EngineResult fr = result;
            EngineState.finish(fr);
            persistRecord(fr);
            notifyDone(fr);
            stopForeground(STOP_FOREGROUND_REMOVECompat());
            stopSelf();
        });
        return START_NOT_STICKY;
    }

    private int STOP_FOREGROUND_REMOVECompat() {
        return Build.VERSION.SDK_INT >= 24 ? Service.STOP_FOREGROUND_REMOVE : 1;
    }

    // FIXED: return type is ZipSink (was Object) so it can be assigned to cfg.sink.
    private ZipSink buildSink(String projectName, String apkName) {
        String base = apkName == null ? "project" : apkName;
        if (base.toLowerCase().endsWith(".apk")) base = base.substring(0, base.length() - 4);
        if (base.isEmpty()) base = "project";
        if (Store.directAvailable(this)) {
            File dir = new File(Store.directRoot(), projectName);
            return new DirectFileSink(new File(dir, base + ".zip"));
        }
        return new MediaStoreSink(this, "Download/APKLens/" + projectName, base + ".zip");
    }

    private void persistRecord(EngineResult r) {
        if (r == null) return;
        ProjectRecord rec = new ProjectRecord();
        rec.id = (r.projectId != null && !r.projectId.isEmpty()) ? r.projectId
                : String.valueOf(System.currentTimeMillis());
        rec.name = r.projectName;
        rec.apkName = r.apkName;
        rec.pkg = r.apkPackage;
        rec.version = r.versionName;
        rec.date = System.currentTimeMillis();
        rec.status = r.status;
        rec.message = r.message;
        rec.zipMode = r.zipMode;
        rec.zipPath = r.zipFilePath == null ? "" : r.zipFilePath;
        rec.zipUri = r.zipUri == null ? "" : r.zipUri;
        rec.zipDisplay = r.pathDisplay;
        rec.zipSize = r.zipSize;
        rec.duration = r.durationMs;
        rec.classes = r.classCount;
        rec.smali = r.smaliCount;
        rec.res = r.resCount;
        rec.assets = r.assetCount;
        rec.libs = r.libCount;
        rec.dex = r.dexCount;
        rec.errors = r.errorCount;
        ProjectStore.upsert(this, rec);
    }

    // ---------------- Notifications ----------------

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel conv = new NotificationChannel(
                    "apklens_convert", "Conversions", NotificationManager.IMPORTANCE_LOW);
            conv.setShowBadge(false);
            nm.createNotificationChannel(conv);
            NotificationChannel done = new NotificationChannel(
                    "apklens_done", "Finished projects", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(done);
        }
    }

    private Notification.Builder builder(String channelId) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(this, channelId);
        Notification.Builder b = new Notification.Builder(this);
        b.setPriority(Notification.PRIORITY_LOW);
        return b;
    }

    private void startFg(String text, int pct) {
        Notification.Builder b = builder("apklens_convert")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("ApkLens")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct, false);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, n);
        }
    }

    private void updateFg(String text, int pct) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b = builder("apklens_convert")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("ApkLens")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct, false);
        try {
            nm.notify(1, b.build());
        } catch (Throwable ignored) {} // notification permission may be denied — conversion continues
    }

    private void notifyDone(EngineResult r) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = "FAILED".equals(r.status) ? "Conversion failed"
                : "CANCELLED".equals(r.status) ? "Conversion cancelled"
                : "Project exported: " + r.projectName;
        String text = "FAILED".equals(r.status) ? String.valueOf(r.message)
                : "ZIP ready · " + Ui_human(r.zipSize);
        Notification.Builder b = builder("apklens_done")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi);
        try { nm.notify(2, b.build()); } catch (Throwable ignored) {}
    }

    private static String Ui_human(long b) {
        if (b < 1024) return b + " B";
        double v = b; int i = -1; String[] u = {"KB","MB","GB"};
        while (v >= 1024 && i < 2) { v /= 1024; i++; }
        return String.format(java.util.Locale.US, "%.1f %s", v, u[i]);
    }
}
