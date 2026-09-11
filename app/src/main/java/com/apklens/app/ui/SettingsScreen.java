package com.apklens.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.apklens.app.data.Prefs;
import com.apklens.app.platform.Store;

public class SettingsScreen extends Screen {

    private LinearLayout storageCard;
    private TextView storageStatus;
    private TextView threadsLabel;

    public SettingsScreen(MainActivity act) { super(act); }

    @Override
    protected View build() {
        ScrollView scroll = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root, new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll.setFillViewport(true);

        root.addView(header("Settings", true));
        int pad = Ui.dp(act, 18);
        Prefs p = new Prefs(act);

        // ---------- Engine ----------
        root.addView(sectionLabel("DECOMPILATION ENGINE", pad));
        LinearLayout card = Ui.card(act);
        card.setPadding(pad, pad, pad, pad);
        root.addView(card, cardParams(pad));

        card.addView(switchRow(p, "fallback", "Fallback mode",
                "Produce raw, low-level output for APKs that resist normal decompilation."));
        card.addView(switchRow(p, "inconsistent", "Try harder (inconsistent code)",
                "Emit code even when the decompiler could not verify it completely."));
        card.addView(switchRow(p, "smali", "Generate Smali files",
                "Adds a smali/ tree — the exact, always-readable representation of the bytecode."));
        card.addView(switchRow(p, "rawDex", "Include raw DEX files",
                "Copies classes.dex / classes2.dex … into the ZIP (increases ZIP size)."));
        card.addView(switchRow(p, "metaInf", "Include META-INF signatures",
                "Keeps signing manifests and certificate blocks in the output."));

        threadsLabel = Ui.text(act, "Decompile threads: " + p.threads(), 14.5f, Ui.INK, true);
        threadsLabel.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 6));
        card.addView(threadsLabel);
        SeekBar sb = new SeekBar(act);
        sb.setMax(7);
        sb.setProgress(p.threads() - 1);
        sb.getProgressDrawable().setTint(Ui.ACCENT);
        sb.getThumb().setTint(Ui.ACCENT);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                int t = v + 1;
                p.setThreads(t);
                threadsLabel.setText("Decompile threads: " + t);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        card.addView(sb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(Ui.text(act, "Lower this if the app is killed on large APKs (less RAM used).",
                11.5f, Ui.MUTED, false));

        // ---------- Storage ----------
        root.addView(sectionLabel("STORAGE", pad));
        storageCard = Ui.card(act);
        storageCard.setPadding(pad, pad, pad, pad);
        root.addView(storageCard, cardParams(pad));

        storageStatus = Ui.text(act, "", 13.5f, Ui.INK, false);
        storageCard.addView(storageStatus);

        if (Build.VERSION.SDK_INT >= 30) {
            android.widget.Button grant = Ui.outline(act, "Grant All-Files Access", Ui.ACCENT, Ui.INK);
            grant.setOnClickListener(v -> requestAllFiles());
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 46));
            glp.topMargin = Ui.dp(act, 12);
            storageCard.addView(grant, glp);
        }
        android.widget.Button openDl = Ui.outline(act, "Open Downloads", Ui.ACCENT, Ui.INK);
        openDl.setOnClickListener(v -> {
            try {
                // FIXED: Views are not Contexts — must start the activity via `act`.
                act.startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(act, "No Downloads app found", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 46));
        dlp.topMargin = Ui.dp(act, 10);
        storageCard.addView(openDl, dlp);

        // ---------- Maintenance ----------
        root.addView(sectionLabel("MAINTENANCE", pad));
        LinearLayout mCard = Ui.card(act);
        mCard.setPadding(pad, pad, pad, pad);
        root.addView(mCard, cardParams(pad));
        android.widget.Button clear = Ui.outline(act, "Clear cached working files", Ui.BAD, Ui.BAD);
        clear.setOnClickListener(v -> {
            Store.clearWorkCache(act);
            Toast.makeText(act, "Cache cleared", Toast.LENGTH_SHORT).show();
        });
        mCard.addView(clear, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 46)));

        TextView ver = Ui.text(act, "ApkLens v" + Store.appVersion(act)
                + "  ·  Engine: jadx-core (Apache-2.0)", 11.5f, Ui.MUTED, false);
        ver.setPadding(pad, Ui.dp(act, 16), pad, Ui.dp(act, 30));
        root.addView(ver);

        return scroll;
    }

    private TextView sectionLabel(String s, int pad) {
        TextView t = Ui.text(act, s, 12, Ui.MUTED, true);
        t.setPadding(pad, Ui.dp(act, 14), pad, Ui.dp(act, 6));
        return t;
    }

    private LinearLayout.LayoutParams cardParams(int pad) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, 0, pad, 0);
        return lp;
    }

    private View switchRow(Prefs p, String key, String title, String desc) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        Switch sw = Ui.tintedSwitch(act);
        sw.setText(title);
        sw.setChecked(p.get(key, "inconsistent".equals(key) || "smali".equals(key) || "metaInf".equals(key)));
        sw.setOnCheckedChangeListener((b, on) -> p.set(key, on));
        row.addView(sw);
        TextView d = Ui.text(act, desc, 11.5f, Ui.MUTED, false);
        d.setPadding(Ui.dp(act, 8), 0, 0, 0);
        row.addView(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 10);
        row.setLayoutParams(lp);
        return row;
    }

    private void requestAllFiles() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception e2) {
                Toast.makeText(act, "Settings screen not available", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void refreshStorageCard() { refreshCard(); }

    @Override
    public void onShow() { refreshCard(); }

    private void refreshCard() {
        storageStatus.setText(Store.describeMode(act));
    }
}
