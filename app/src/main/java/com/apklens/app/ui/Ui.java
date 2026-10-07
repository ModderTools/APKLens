package com.apklens.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

/** Programmatic UI factory + design tokens. No XML layouts anywhere in this app. */
public final class Ui {
    // ---- Design tokens (from the spec) ----
    public static final int BG      = 0xFFDBDBE5;
    public static final int SURFACE = 0xFFFFFFFF;
    public static final int INK     = 0xFF21222D;
    public static final int ACCENT  = 0xFF958CE8;
    /** Accent for small TEXT on white — #958CE8 is only ~3:1 there; this one is ~6:1. */
    public static final int ACCENT_TEXT = 0xFF5A4FCF;
    public static final int ACCENT2 = 0xFFACD1FD;
    public static final int MUTED   = 0xFF6A6B78;
    public static final int LINE    = 0xFFE4E4EC;

    // Semantic status colors (kept muted to fit the palette)
    public static final int OK   = 0xFF3BA55D;
    public static final int WARN = 0xFFDE8F2E;
    public static final int BAD  = 0xFFDF5A5A;
    public static final int NEUTRAL = 0xFF9A9AA8;

    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable roundStroke(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = round(fill, radiusDp, c);
        g.setStroke(Math.max(1, dp(c, 1.2f)), stroke);
        return g;
    }

    public static GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(SURFACE, 20, c));
        l.setElevation(dp(c, 3));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static Button primary(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = round(ACCENT, 14, c);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(0x3321222D), bg, null);
        b.setBackground(ripple);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 20), 0, dp(c, 20), 0);
        return b;
    }

    public static Button outline(Context c, String label, int strokeColor, int textColor) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(textColor);
        GradientDrawable bg = roundStroke(c, SURFACE, strokeColor, 14);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(0x2221222D), bg, null);
        b.setBackground(ripple);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 46));
        b.setPadding(dp(c, 20), 0, dp(c, 20), 0);
        return b;
    }

    public static EditText editText(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(INK);
        e.setTextSize(15);
        e.setBackground(roundStroke(c, SURFACE, LINE, 12));
        int p = dp(c, 13);
        e.setPadding(p, p, p, p);
        e.setSingleLine(true);
        return e;
    }

    public static Switch tintedSwitch(Context c) {
        Switch s = new Switch(c);
        ColorStateList on = ColorStateList.valueOf(ACCENT);
        ColorStateList off = ColorStateList.valueOf(0xFFBFC0CC);
        s.setThumbTintList(new ColorStateList(
                new int[][]{ {android.R.attr.state_checked}, {} },
                new int[]{ ACCENT, 0xFFFFFFFF }));
        s.setTrackTintList(new ColorStateList(
                new int[][]{ {android.R.attr.state_checked}, {} },
                new int[]{ 0x66958CE8, 0xFFCFD0DA }));
        s.setTextColor(INK);
        s.setTextSize(15);
        return s;
    }

    public static ProgressBar horizontalProgress(Context c) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        p.setProgressTintList(ColorStateList.valueOf(ACCENT));
        p.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFCFD0DA));
        p.setMax(100);
        return p;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpWeight(Context c, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, h, weight);
        return p;
    }

    public static void margin(View v, int l, int t, int r, int b) {
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        if (m == null) return;
        m.setMargins(l, t, r, b);
        v.setLayoutParams(m);
    }

    /** Small ALL-CAPS section label (uses the accessible accent). */
    public static TextView label(Context c, String s) {
        TextView t = text(c, s, 12, ACCENT_TEXT, true);
        t.setLetterSpacing(0.06f);
        return t;
    }

    /** Pill-shaped status chip. */
    public static TextView chip(Context c, String s, int color) {
        TextView t = text(c, s, 11.5f, color, true);
        int fill = (color & 0x00FFFFFF) | 0x22000000;
        GradientDrawable bg = round(fill, 999, c);
        bg.setStroke(1, (color & 0x00FFFFFF) | 0x55000000);
        t.setBackground(bg);
        t.setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3));
        t.setSingleLine(true);
        return t;
    }

    /** "label ........ value" row. */
    public static LinearLayout kv(Context c, String k, String v) {
        LinearLayout row = row(c);
        row.setPadding(0, dp(c, 4), 0, dp(c, 4));
        TextView kt = text(c, k, 13.5f, MUTED, false);
        row.addView(kt, lpWeight(c, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView vt = text(c, v, 13.5f, INK, true);
        vt.setGravity(Gravity.END);
        row.addView(vt);
        return row;
    }

    /** 1dp separator line. */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.topMargin = dp(c, 10);
        lp.bottomMargin = dp(c, 10);
        v.setLayoutParams(lp);
        return v;
    }

    public static String duration(long ms) {
        long s = ms / 1000;
        if (s < 60) return (ms < 10_000 ? String.format(java.util.Locale.US, "%.1f", ms / 1000.0) : String.valueOf(s)) + " s";
        return (s / 60) + " min " + (s % 60) + " s";
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] u = {"KB", "MB", "GB"};
        int i = -1;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(java.util.Locale.US, "%.1f %s", v, u[i]);
    }
}
