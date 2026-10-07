package com.apklens.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.apklens.app.engine.EngineResult;
import com.apklens.app.platform.Store;

public class ResultScreen extends Screen {

    private final EngineResult r;

    public ResultScreen(MainActivity act, EngineResult r) { super(act); this.r = r; }

    /** Back from the result always goes Home — never to a stale progress/new-project screen. */
    @Override
    public boolean onBack() { act.popToRoot(); return true; }

    private boolean ok() { return "SUCCESS".equals(r.status) || "PARTIAL".equals(r.status); }

    @Override
    protected View build() {
        ScrollView scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root, new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(header("Result", false));
        int pad = Ui.dp(act, 18);

        // ---------------- headline card ----------------
        LinearLayout card = Ui.card(act);
        root.addView(card, cardLp(pad, 6));

        int color = "SUCCESS".equals(r.status) ? Ui.OK
                : "PARTIAL".equals(r.status) ? Ui.WARN
                : "CANCELLED".equals(r.status) ? Ui.NEUTRAL : Ui.BAD;
        String headline = "SUCCESS".equals(r.status) ? "Project exported ✓"
                : "PARTIAL".equals(r.status) ? "Finished with warnings"
                : "CANCELLED".equals(r.status) ? "Conversion cancelled" : "Conversion failed";

        LinearLayout top = Ui.row(act);
        card.addView(top);
        top.addView(Ui.text(act, headline, 19, color, true),
                Ui.lpWeight(act, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (ok()) top.addView(Ui.chip(act, Ui.duration(r.durationMs), Ui.NEUTRAL));

        String title = !r.appLabel.isEmpty() ? r.appLabel : r.projectName;
        TextView sub = Ui.text(act, title + " · " + r.apkName, 13, Ui.MUTED, false);
        sub.setPadding(0, Ui.dp(act, 4), 0, 0);
        card.addView(sub);

        if (!ok()) {
            TextView msg = Ui.text(act, r.message == null ? "" : r.message, 14, Ui.INK, false);
            msg.setPadding(0, Ui.dp(act, 10), 0, 0);
            card.addView(msg);
            if (r.outOfMemory) {
                TextView tip = Ui.text(act, "Tip: Settings → turn off “Generate Smali files” and lower the "
                        + "thread count, then try again.", 12.5f, Ui.MUTED, false);
                tip.setPadding(0, Ui.dp(act, 8), 0, 0);
                card.addView(tip);
            }
        } else {
            if (r.message != null && !r.message.isEmpty()) {
                TextView msg = Ui.text(act, r.message, 13, Ui.WARN, false);
                msg.setPadding(0, Ui.dp(act, 8), 0, 0);
                card.addView(msg);
            }
            card.addView(Ui.divider(act));
            card.addView(Ui.label(act, "ZIP LOCATION"));
            TextView path = Ui.text(act, r.pathDisplay, 13, Ui.INK, false);
            path.setTextIsSelectable(true);
            path.setPadding(0, Ui.dp(act, 4), 0, 0);
            card.addView(path);
            TextView size = Ui.text(act, Ui.human(r.zipSize), 12.5f, Ui.MUTED, false);
            size.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 12));
            card.addView(size);

            LinearLayout btns = Ui.row(act);
            Button share = Ui.primary(act, "Share ZIP");
            share.setOnClickListener(v -> Store.shareZipResult(act, r));
            btns.addView(share, Ui.lpWeight(act, Ui.dp(act, 50), 1));
            Button open = Ui.outline(act, "Open", Ui.ACCENT, Ui.INK);
            open.setOnClickListener(v -> Store.openZipResult(act, r));
            LinearLayout.LayoutParams olp = Ui.lpWeight(act, Ui.dp(act, 50), 1);
            olp.leftMargin = Ui.dp(act, 10);
            btns.addView(open, olp);
            Button copy = Ui.outline(act, "Copy path", Ui.ACCENT, Ui.INK);
            copy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("path", r.pathDisplay));
                Toast.makeText(act, "Path copied", Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams clp = Ui.lpWeight(act, Ui.dp(act, 50), 1);
            clp.leftMargin = Ui.dp(act, 10);
            btns.addView(copy, clp);
            card.addView(btns);
        }

        // ---------------- analysis card ----------------
        if (ok() && !r.apkPackage.isEmpty()) {
            LinearLayout a = Ui.card(act);
            root.addView(a, cardLp(pad, 0));
            a.addView(Ui.label(act, "APP"));
            a.addView(spaced(Ui.kv(act, "Package", r.apkPackage), 6));
            if (!r.versionName.isEmpty()) a.addView(Ui.kv(act, "Version", r.versionName));
            if (r.minSdk > 0 || r.targetSdk > 0) {
                a.addView(Ui.kv(act, "SDK", "min " + (r.minSdk > 0 ? r.minSdk : "?")
                        + " · target " + (r.targetSdk > 0 ? r.targetSdk : "?")));
            }
            a.addView(Ui.kv(act, "Permissions", r.permCount + (r.dangerousPerms > 0
                    ? "  (" + r.dangerousPerms + " dangerous)" : "")));
            if (r.exportedComponents > 0) a.addView(Ui.kv(act, "Exported components", String.valueOf(r.exportedComponents)));

            // security flags as chips
            LinearLayout chips = Ui.row(act);
            chips.setPadding(0, Ui.dp(act, 10), 0, 0);
            boolean any = false;
            if (r.debuggable) { chips.addView(chipM(Ui.chip(act, "debuggable", Ui.BAD))); any = true; }
            if (r.cleartext) { chips.addView(chipM(Ui.chip(act, "cleartext HTTP", Ui.WARN))); any = true; }
            if (r.allowBackup) { chips.addView(chipM(Ui.chip(act, "backup allowed", Ui.NEUTRAL))); any = true; }
            if (r.specialPerms > 0) { chips.addView(chipM(Ui.chip(act, r.specialPerms + " high-impact perm", Ui.WARN))); any = true; }
            if (any) {
                android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(act);
                hs.setHorizontalScrollBarEnabled(false);
                hs.addView(chips);
                a.addView(hs);
            }

            if (!r.obfuscationLevel.isEmpty()) {
                a.addView(Ui.divider(act));
                a.addView(Ui.label(act, "CODE"));
                a.addView(spaced(Ui.kv(act, "Obfuscation", r.obfuscationLevel
                        + " (" + r.obfuscationPct + "%)"), 6));
                if (r.kotlinClasses > 0) a.addView(Ui.kv(act, "Kotlin classes", String.valueOf(r.kotlinClasses)));
                a.addView(Ui.kv(act, "URLs found", String.valueOf(r.urlCount)));
                a.addView(Ui.kv(act, "Possible secrets", String.valueOf(r.secretCount)));
                if (!r.libraries.isEmpty()) {
                    TextView libs = Ui.text(act, join(r.libraries), 12.5f, Ui.MUTED, false);
                    libs.setPadding(0, Ui.dp(act, 6), 0, 0);
                    a.addView(libs);
                }
                if (r.secretCount > 0) {
                    TextView t = Ui.text(act, "Details (masked) are in analysis-report.txt inside the ZIP.",
                            12, Ui.WARN, false);
                    t.setPadding(0, Ui.dp(act, 6), 0, 0);
                    a.addView(t);
                }
            }
            if (!r.frameworkHint.isEmpty()) {
                a.addView(Ui.divider(act));
                a.addView(Ui.label(act, "FRAMEWORK"));
                TextView f = Ui.text(act, r.frameworkHint, 13, Ui.INK, false);
                f.setPadding(0, Ui.dp(act, 6), 0, 0);
                a.addView(f);
            }
        }

        // ---------------- statistics card ----------------
        if (ok()) {
            LinearLayout s = Ui.card(act);
            root.addView(s, cardLp(pad, 0));
            s.addView(Ui.label(act, "OUTPUT"));
            s.addView(spaced(Ui.kv(act, "Java classes", String.valueOf(r.classCount)), 6));
            if (r.smaliCount > 0) s.addView(Ui.kv(act, "Smali files", String.valueOf(r.smaliCount)));
            s.addView(Ui.kv(act, "Resource files", String.valueOf(r.resCount)));
            s.addView(Ui.kv(act, "Assets", String.valueOf(r.assetCount)));
            s.addView(Ui.kv(act, "Native libs", String.valueOf(r.libCount)));
            s.addView(Ui.kv(act, "DEX files", String.valueOf(r.dexCount)));
            if (r.errorCount > 0) s.addView(Ui.kv(act, "Classes with errors", String.valueOf(r.errorCount)));
        }

        // ---------------- warnings / notes ----------------
        if (!r.warnings.isEmpty()) root.addView(listCard(pad, "WARNINGS", r.warnings, Ui.WARN));
        if (!r.notes.isEmpty()) root.addView(listCard(pad, "NOTES", r.notes, Ui.MUTED));

        Button done = Ui.outline(act, ok() ? "Done" : "Back to Home", Ui.ACCENT, Ui.INK);
        done.setOnClickListener(v -> act.popToRoot());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 50));
        dlp.setMargins(pad, Ui.dp(act, 4), pad, Ui.dp(act, 30));
        root.addView(done, dlp);

        return scroll;
    }

    private LinearLayout.LayoutParams cardLp(int pad, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, Ui.dp(act, topDp), pad, Ui.dp(act, 14));
        return lp;
    }

    private View spaced(View v, int topDp) {
        v.setPadding(0, Ui.dp(act, topDp), 0, v.getPaddingBottom());
        return v;
    }

    private View chipM(TextView chip) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(act, 6);
        chip.setLayoutParams(lp);
        return chip;
    }

    private LinearLayout listCard(int pad, String title, java.util.List<String> items, int color) {
        LinearLayout c = Ui.card(act);
        c.setLayoutParams(cardLp(pad, 0));
        TextView t = Ui.text(act, title, 12, color == Ui.MUTED ? Ui.ACCENT_TEXT : color, true);
        t.setLetterSpacing(0.06f);
        c.addView(t);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String w : items) {
            if (n++ >= 8) { sb.append("… and ").append(items.size() - 8).append(" more (see analysis-report.txt)"); break; }
            sb.append("• ").append(w).append('\n');
        }
        TextView body = Ui.text(act, sb.toString().trim(), 12.5f, Ui.INK, false);
        body.setPadding(0, Ui.dp(act, 6), 0, 0);
        c.addView(body);
        return c;
    }

    private static String join(java.util.List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
