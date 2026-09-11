package com.apklens.app.ui;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.apklens.app.R;
import com.apklens.app.platform.Store;

public class AppInfoScreen extends Screen {

    public AppInfoScreen(MainActivity act) { super(act); }

    @Override
    protected View build() {
        ScrollView scroll = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root, new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll.setFillViewport(true);
        root.addView(header("App Info", true));
        int pad = Ui.dp(act, 18);

        // Branding card
        LinearLayout brand = Ui.card(act);
        brand.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(pad, Ui.dp(act, 6), pad, pad);
        root.addView(brand, blp);
        ImageView logo = new ImageView(act);
        logo.setImageResource(R.drawable.logo);
        brand.addView(logo, new LinearLayout.LayoutParams(Ui.dp(act, 84), Ui.dp(act, 84)));
        TextView name = Ui.text(act, "ApkLens", 22, Ui.INK, true);
        name.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 2));
        brand.addView(name);
        brand.addView(Ui.text(act, "v" + Store.appVersion(act), 12.5f, Ui.MUTED, false));
        TextView tag = Ui.text(act, "JADX-style APK decompiler & project extractor — fully offline",
                12.5f, Ui.ACCENT, false);
        tag.setPadding(0, Ui.dp(act, 6), 0, 0);
        brand.addView(tag);

        root.addView(card(pad,
                "PURPOSE",
                "ApkLens converts an installed-package APK file into a readable, Android-project-style "
                        + "folder structure and delivers it as a ZIP: decompiled Java source, decoded "
                        + "resources and manifest, extracted assets and native libraries."));

        root.addView(card(pad,
                "MAJOR FEATURES",
                "• DEX → Java reconstruction (classes.dex + multidex)\n"
                        + "• AndroidManifest.xml and resources.arsc decoding\n"
                        + "• assets/, lib/ (native .so), META-INF extraction\n"
                        + "• Optional Smali output and raw DEX retention\n"
                        + "• Android-project-style output tree + ZIP export\n"
                        + "• Project history, settings, background conversion"));

        root.addView(card(pad,
                "SUPPORTED OPERATIONS",
                "• Normal, multidex, resource-heavy and large APKs\n"
                        + "• Split/merged considerations reported honestly\n"
                        + "• Obfuscated APKs (output uses obfuscated names)\n"
                        + "• Corrupted or hostile archives are rejected safely"));

        root.addView(card(pad,
                "LIMITATIONS (IMPORTANT)",
                "Output is RECONSTRUCTED, not original source. Comments, original formatting and "
                        + "local variable names are permanently lost at compile time. Heavily "
                        + "optimized or obfuscated APKs yield partial or renamed code. Kotlin apps "
                        + "are detected and noted, but output syntax is Java. The ZIP is for reading "
                        + "and study — it is not guaranteed to compile."));

        root.addView(card(pad,
                "PRIVACY & LICENSES",
                "ApkLens has no internet permission: nothing about your APKs ever leaves the device.\n\n"
                        + "Decompilation engine: jadx-core — Apache License 2.0. "
                        + "ApkLens itself: © ApkLens developers.\n\n"
                        + "Use responsibly: analyze apps you own or have permission to analyze; "
                        + "respect copyright and local law."));

        TextView bottom = new TextView(act);
        bottom.setPadding(pad, 0, pad, Ui.dp(act, 30));
        root.addView(bottom);
        return scroll;
    }

    private LinearLayout card(int pad, String title, String body) {
        LinearLayout c = Ui.card(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, 0, pad, pad);
        c.setLayoutParams(lp);
        TextView t = Ui.text(act, title, 12, Ui.ACCENT, true);
        c.addView(t);
        TextView b = Ui.text(act, body, 13.5f, Ui.INK, false);
        b.setLineSpacing(Ui.dp(act, 1.5f), 1f);
        b.setPadding(0, Ui.dp(act, 8), 0, 0);
        c.addView(b);
        return c;
    }
}
