package com.apklens.app.ui;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.apklens.app.R;
import com.apklens.app.engine.EngineResult;
import com.apklens.app.platform.Store;

import java.util.LinkedHashMap;
import java.util.Map;

public class ResultScreen extends Screen {

    private final EngineResult r;

    public ResultScreen(MainActivity act, EngineResult r) { super(act); this.r = r; }

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

        LinearLayout card = Ui.card(act);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(pad, Ui.dp(act, 6), pad, pad);
        root.addView(card, clp);

        int color = "SUCCESS".equals(r.status) ? Ui.OK
                : "PARTIAL".equals(r.status) ? Ui.WARN
                : "CANCELLED".equals(r.status) ? Ui.NEUTRAL : Ui.BAD;
        String headline = "SUCCESS".equals(r.status) ? "Project exported ✓"
                : "PARTIAL".equals(r.status) ? "Finished with warnings"
                : "CANCELLED".equals(r.status) ? "Conversion cancelled" : "Conversion failed";

        TextView h = Ui.text(act, headline, 19, color, true);
        card.addView(h);
        TextView sub = Ui.text(act, r.projectName + " · " + r.apkName, 13, Ui.MUTED, false);
        sub.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 10));
        card.addView(sub);

        if (r.status.startsWith("FAIL") || "CANCELLED".equals(r.status)) {
            TextView msg = Ui.text(act, r.message == null ? "" : r.message, 13.5f, Ui.BAD, false);
            msg.setPadding(0, 0, 0, Ui.dp(act, 8));
            card.addView(msg);
        }

        if (!"FAILED".equals(r.status) && !"CANCELLED".equals(r.status)) {
            TextView pathLabel = Ui.text(act, "ZIP LOCATION", 12, Ui.ACCENT, true);
            card.addView(pathLabel);
            TextView path = Ui.text(act, r.pathDisplay, 13, Ui.INK, false);
            path.setTextIsSelectable(true);
            path.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 10));
            card.addView(path);

            Map<String, String> stats = new LinkedHashMap<>();
            stats.put("ZIP size", Ui.human(r.zipSize));
            stats.put("Duration", (r.durationMs / 1000) + " s");
            stats.put("Classes reconstructed", String.valueOf(r.classCount));
            if (r.smaliCount > 0) stats.put("Smali files", String.valueOf(r.smaliCount));
            stats.put("Resource files", String.valueOf(r.resCount));
            stats.put("Assets", String.valueOf(r.assetCount));
            stats.put("Native libs", String.valueOf(r.libCount));
            stats.put("DEX files", String.valueOf(r.dexCount));
            if (r.errorCount > 0) stats.put("Classes with errors", String.valueOf(r.errorCount));
            for (Map.Entry<String, String> e : stats.entrySet()) {
                LinearLayout row = Ui.row(act);
                TextView k = Ui.text(act, e.getKey(), 13.5f, Ui.MUTED, false);
                row.addView(k, Ui.lpWeight(act, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                row.addView(Ui.text(act, e.getValue(), 13.5f, Ui.INK, true));
                card.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            if (!r.warnings.isEmpty()) {
                TextView wl = Ui.text(act, "NOTES", 12, Ui.WARN, true);
                wl.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 4));
                card.addView(wl);
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for (String w : r.warnings) {
                    if (n++ >= 8) { sb.append("… and ").append(r.warnings.size() - 8).append(" more (see analysis-report.txt)"); break; }
                    sb.append("• ").append(w).append("\n");
                }
                TextView wv = Ui.text(act, sb.toString().trim(), 12, Ui.MUTED, false);
                card.addView(wv);
            }
        }

        // Actions
        if (!"FAILED".equals(r.status) && !"CANCELLED".equals(r.status)) {
            LinearLayout btns = Ui.row(act);
            android.widget.Button share = Ui.primary(act, "Share ZIP");
            share.setOnClickListener(v -> Store.shareZipResult(act, r));
            btns.addView(share, Ui.lpWeight(act, Ui.dp(act, 50), 1));

            android.widget.Button open = Ui.outline(act, "Open", Ui.ACCENT, Ui.INK);
            open.setOnClickListener(v -> {
                if (!Store.openZipResult(act, r)) {
                    Toast.makeText(act, "No app can open ZIP files", Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams olp = Ui.lpWeight(act, Ui.dp(act, 50), 1);
            olp.leftMargin = Ui.dp(act, 10);
            btns.addView(open, olp);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.setMargins(pad, Ui.dp(act, 6), pad, Ui.dp(act, 10));
            root.addView(btns, blp);
        }

        android.widget.Button done = Ui.outline(act,
                "FAILED".equals(r.status) ? "Back to Home" : "Done", Ui.ACCENT, Ui.INK);
        done.setOnClickListener(v -> act.popToRoot());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 50));
        dlp.setMargins(pad, Ui.dp(act, 6), pad, Ui.dp(act, 30));
        root.addView(done, dlp);

        return scroll;
    }
}
