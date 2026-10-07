package com.apklens.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
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
import com.apklens.app.engine.Pipeline;
import com.apklens.app.engine.ProgressListener;
import com.apklens.app.engine.ZipSink;
import com.apklens.app.platform.MediaStoreSink;
import com.apklens.app.platform.Store;
import com.apklens.app.ui.MainActivity;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConvertService extends Service {

    public static final String ACTION_CONVERT = "com.apklens.app.CONVERT";
    public static final String ACTION_CANCEL = "com.apklens.app.CANCEL";

    private static final int NOTIF_PROGRESS = 1;
    private static final int NOTIF_DONE = 2;

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
        // startForegroundService() is the documented way to start a service that will promote
        // itself; onStartCommand() therefore calls startForeground() on EVERY CONVERT path.
        ctx.startForegroundService(i);
    }

    public static void cancel(Context ctx) {
        // Cancel only flips a flag; the service is already foreground, so plain startService is right.
        EngineState.requestCancel();
        Intent i = new Intent(ctx, ConvertService.class);
        i.setAction(ACTION_CANCEL);
        try { ctx.startService(i); } catch (Throwable ignored) {}
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
        String action = intent == null ? null : intent.getAction();

        if (ACTION_CANCEL.equals(action)) {
            EngineState.requestCancel();
            return START_NOT_STICKY;
        }

        // Every other path that reaches here came from startForegroundService(): promote FIRST,
        // otherwise Android kills the app with ForegroundServiceDidNotStartInTimeException.
        startFg("Preparing…", 0);

        final String apkPath = intent == null ? null : intent.getStringExtra("apkPath");
        final String projectName = intent == null ? null : intent.getStringExtra("projectName");
        final String apkName = intent == null ? null : intent.getStringExtra("apkName");
        if (!ACTION_CONVERT.equals(action) || apkPath == null || projectName == null
                || EngineState.isRunning()) {
            if (!EngineState.isRunning()) leaveForeground();
            return START_NOT_STICKY;
        }

        EngineState.markStarting(projectName);

        EXEC.execute(() -> {
            EngineResult result = null;
            File picked = new File(apkPath);
            File workDir = null;
            try {
                Prefs prefs = new Prefs(this);
                File logDir = new File(getFilesDir(), "logs");
                logDir.mkdirs();
                File logFile = new File(logDir, projectName.replaceAll("[^A-Za-z0-9]+", "_") + "_" + Integer.toHexString(projectName.hashCode()) + ".log");

                int threads = prefs.threads();
                for (int attempt = 0; attempt < 2; attempt++) {
                    workDir = new File(getCacheDir(), "work_" + System.currentTimeMillis());
                    EngineConfig cfg = new EngineConfig();
                    cfg.apkFile = picked;
                    cfg.workDir = workDir;
                    cfg.outDir = new File(workDir, "out");
                    cfg.projectName = projectName;
                    cfg.apkName = apkName;
                    cfg.fallback = prefs.flag("fallback");
                    cfg.showInconsistent = prefs.flag("inconsistent");
                    cfg.smali = prefs.flag("smali");
                    cfg.rawDex = prefs.flag("rawDex");
                    cfg.metaInf = prefs.flag("metaInf");
                    cfg.deobf = prefs.flag("deobf");
                    cfg.analyze = prefs.flag("analyze");
                    cfg.threads = threads;
                    cfg.sink = buildSink(projectName, apkName);

                    try (EngineLog log = EngineLog.toFile(logFile)) {
                        result = Pipeline.run(cfg, newListener(), log);
                    } finally {
                        Io.deleteRecursively(workDir);
                    }

                    // Out of memory is the most common failure on big APKs and is usually fixable
                    // by simply running single-threaded — do that automatically, once.
                    if (result.outOfMemory && threads > 1 && !EngineState.isCancelRequested()) {
                        threads = 1;
                        EngineState.update("Low memory — retrying with 1 thread", 0,
                                "The first attempt ran out of memory");
                        updateFg("Low memory — retrying with 1 thread", 0);
                        continue;
                    }
                    break;
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
                Io.deleteRecursively(workDir);
                Io.deleteRecursively(picked);
            }

            final EngineResult fr = result;
            // Persist BEFORE announcing: the UI reacts to finish() by reloading the history.
            persistRecord(fr);
            EngineState.finish(fr);
            notifyDone(fr);
            leaveForeground();
        });
        return START_NOT_STICKY;
    }

    private ProgressListener newListener() {
        return new ProgressListener() {
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
    }

    private void leaveForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private ZipSink buildSink(String projectName, String apkName) {
        String base = apkName == null ? "project" : apkName;
        if (base.toLowerCase().endsWith(".apk")) base = base.substring(0, base.length() - 4);
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
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
        rec.label = r.appLabel;
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
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel conv = new NotificationChannel(
                "apklens_convert", "Conversions", NotificationManager.IMPORTANCE_LOW);
        conv.setShowBadge(false);
        nm.createNotificationChannel(conv);
        NotificationChannel done = new NotificationChannel(
                "apklens_done", "Finished projects", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(done);
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification progressNotification(String text, int pct) {
        Intent cancel = new Intent(this, ConvertService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPi = PendingIntent.getService(this, 11, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action cancelAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat), "Cancel", cancelPi).build();
        return new Notification.Builder(this, "apklens_convert")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("ApkLens · " + EngineState.getProjectName())
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent())
                .addAction(cancelAction)
                .setProgress(100, Math.max(0, Math.min(100, pct)), pct <= 0)
                .build();
    }

    private void startFg(String text, int pct) {
        Notification n = progressNotification(text, pct);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_PROGRESS, n);
        }
    }

    private void updateFg(String text, int pct) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            nm.notify(NOTIF_PROGRESS, progressNotification(text, pct));
        } catch (Throwable ignored) {} // notification permission may be denied — conversion continues
    }

    private void notifyDone(EngineResult r) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean failed = "FAILED".equals(r.status);
        String title = failed ? "Conversion failed"
                : "CANCELLED".equals(r.status) ? "Conversion cancelled"
                : "Project exported: " + r.projectName;
        String text = failed ? String.valueOf(r.message)
                : "CANCELLED".equals(r.status) ? "Nothing was saved"
                : "ZIP ready · " + Io.human(r.zipSize);
        Notification.Builder b = new Notification.Builder(this, "apklens_done")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent());
        try { nm.notify(NOTIF_DONE, b.build()); } catch (Throwable ignored) {}
    }
}
