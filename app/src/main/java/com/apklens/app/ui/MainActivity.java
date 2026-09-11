package com.apklens.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat; // NOT USED — kept zero-dependency; see note below.

import com.apklens.app.service.EngineState;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Single-activity host. Screens are programmatic FrameLayouts pushed on a stack.
 * NOTE: no AndroidX is used anywhere in this app; the import above is intentionally
 * absent in the final source (kept out of dependencies entirely).
 */
public class MainActivity extends android.app.Activity {

    public static final int REQ_PICK_APK = 41;
    public static final int REQ_WRITE = 42;
    public static final int REQ_NOTIF = 43;

    private FrameLayout root;
    private final ArrayDeque<Screen> stack = new ArrayDeque<>();
    private final Map<Integer, PermCallback> permCallbacks = new HashMap<>();

    public interface PermCallback { void onResult(boolean granted); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        push(new HomeScreen(this), false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If the OS killed the service mid-run, fail the state instead of hanging.
        if (EngineState.getPhase() == EngineState.Phase.RUNNING
                && !com.apklens.app.service.ConvertService.isAlive()) {
            EngineState.fail("Conversion was interrupted (system killed the process).");
        }
        if (!stack.isEmpty()) stack.peek().onShow();
    }

    @Override
    protected void onPause() {
        if (!stack.isEmpty()) stack.peek().onHide();
        super.onPause();
    }

    public Screen top() { return stack.peek(); }

    public void push(Screen s) { push(s, true); }

    public void push(Screen s, boolean animate) {
        if (!stack.isEmpty()) stack.peek().onHide();
        stack.push(s);
        root.addView(s, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (animate) {
            AlphaAnimation a = new AlphaAnimation(0f, 1f);
            a.setDuration(170);
            s.startAnimation(a);
        }
        s.onShow();
    }

    public void pop() {
        if (stack.size() <= 1) return;
        Screen cur = stack.pop();
        cur.onHide();
        root.removeView(cur);
        Screen prev = stack.peek();
        root.addView(prev, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        AlphaAnimation a = new AlphaAnimation(0.55f, 1f);
        a.setDuration(150);
        prev.startAnimation(a);
        prev.onShow();
    }

    public void popToRoot() {
        while (stack.size() > 1) {
            Screen cur = stack.pop();
            cur.onHide();
            root.removeView(cur);
        }
        Screen home = stack.peek();
        AlphaAnimation a = new AlphaAnimation(0.55f, 1f);
        a.setDuration(150);
        home.startAnimation(a);
        home.onShow();
    }

    public void awaitPermission(int req, String perm, PermCallback cb) {
        if (ContextCompat_noop(perm)) {
            cb.onResult(true);
            return;
        }
        permCallbacks.put(req, cb);
        requestPermissions(new String[]{perm}, req);
    }

    /** Minimal local permission check (avoids AndroidX). */
    private boolean ContextCompat_noop(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onBackPressed() {
        Screen s = stack.peek();
        if (s != null && s.onBack()) return;
        if (stack.size() > 1) pop();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Screen s = stack.peek();
        if (requestCode == REQ_PICK_APK && s instanceof NewProjectScreen) {
            ((NewProjectScreen) s).onApkPicked(resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        PermCallback cb = permCallbacks.remove(requestCode);
        if (cb != null) cb.onResult(granted);
        Screen s = stack.peek();
        if (s instanceof SettingsScreen) ((SettingsScreen) s).refreshStorageCard();
    }

    /** Fire-and-forget notification permission (API 33+). Never blocks the workflow. */
    public void ensureNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    public View rootView() { return root; }
}
