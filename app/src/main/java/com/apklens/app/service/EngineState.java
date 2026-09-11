package com.apklens.app.service;

import android.os.Handler;
import android.os.Looper;

import com.apklens.app.engine.EngineResult;

import java.util.concurrent.CopyOnWriteArraySet;

/** Small observable state bridge between ConvertService (background) and the UI (main thread). */
public final class EngineState {

    public enum Phase { IDLE, RUNNING, FINISHED }

    public interface Listener { void onChanged(); }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile String projectName = "";
    private static volatile String stage = "";
    private static volatile String detail = "";
    private static volatile int percent = 0;
    private static volatile boolean cancelRequested = false;
    private static volatile EngineResult unseenResult = null;

    private static final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private EngineState() {}

    public static Phase getPhase() { return phase; }
    public static String getProjectName() { return projectName; }
    public static String getStage() { return stage; }
    public static String getDetail() { return detail; }
    public static int getPercent() { return percent; }
    public static boolean isRunning() { return phase == Phase.RUNNING; }
    public static boolean isCancelRequested() { return cancelRequested; }

    public static void addListener(Listener l) { listeners.add(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }

    public static void markStarting(String name) {
        phase = Phase.RUNNING;
        projectName = name == null ? "" : name;
        stage = "Preparing…";
        detail = "";
        percent = 0;
        cancelRequested = false;
        unseenResult = null;
        notifyListeners();
    }

    public static void update(String newStage, int newPercent, String newDetail) {
        if (newStage != null) stage = newStage;
        if (newPercent >= 0) percent = Math.min(100, Math.max(0, newPercent));
        if (newDetail != null) detail = newDetail;
        notifyListeners();
    }

    public static void requestCancel() { cancelRequested = true; }

    public static void finish(EngineResult result) {
        phase = Phase.FINISHED;
        unseenResult = result;
        percent = 100;
        notifyListeners();
    }

    public static void fail(String message) {
        EngineResult r = new EngineResult();
        r.status = "FAILED";
        r.message = message;
        r.projectName = projectName;
        finish(r);
    }

    /** Returns and clears a result that no screen has consumed yet. */
    public static EngineResult consumeUnseenResult() {
        EngineResult r = unseenResult;
        unseenResult = null;
        return r;
    }

    public static void reset() {
        phase = Phase.IDLE;
        unseenResult = null;
        cancelRequested = false;
        notifyListeners();
    }

    private static void notifyListeners() {
        MAIN.post(() -> {
            for (Listener l : listeners) {
                try { l.onChanged(); } catch (Throwable ignored) {}
            }
        });
    }
}
