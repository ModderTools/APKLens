package com.apklens.app.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.apklens.app.R;
import com.apklens.app.data.ProjectRecord;
import com.apklens.app.data.ProjectStore;
import com.apklens.app.engine.EngineResult;
import com.apklens.app.platform.Store;
import com.apklens.app.service.EngineState;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HomeScreen extends Screen {

    private TextView statProjects, statLast, statStorage, runChip, storageHint;
    private ListView list;
    private LinearLayout emptyState;
    private Adapter adapter;
    private View accentBar;

    public HomeScreen(MainActivity act) { super(act); }

    @Override
    protected View build() {
        FrameLayout outer = new FrameLayout(act);

        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        outer.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // ---------- Top bar ----------
        LinearLayout top = Ui.row(act);
        int pad = Ui.dp(act, 18);
        top.setPadding(pad, pad, pad, Ui.dp(act, 6));

        ImageView logo = new ImageView(act);
        logo.setImageResource(R.drawable.logo);
        top.addView(logo, new LinearLayout.LayoutParams(Ui.dp(act, 34), Ui.dp(act, 34)));

        TextView title = Ui.text(act, "ApkLens", 21, Ui.INK, true);
        LinearLayout.LayoutParams tl = Ui.lpWeight(act, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tl.leftMargin = Ui.dp(act, 10);
        top.addView(title, tl);

        top.addView(iconButton(R.drawable.ic_settings, () -> act.push(new SettingsScreen(act))));
        top.addView(iconButton(R.drawable.ic_info, () -> act.push(new AppInfoScreen(act))));
        top.addView(iconButton(R.drawable.ic_dev, () -> act.push(new DeveloperInfoScreen(act))));
        content.addView(top);

        // ---------- Dashboard card ----------
        LinearLayout dash = Ui.card(act);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(pad, Ui.dp(act, 4), pad, Ui.dp(act, 8));
        content.addView(dash, dlp);

        LinearLayout dashRow = Ui.row(act);
        dash.addView(dashRow);

        LinearLayout colL = new LinearLayout(act);
        colL.setOrientation(LinearLayout.VERTICAL);
        dashRow.addView(colL, Ui.lpWeight(act, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        runChip = Ui.text(act, "READY", 11, Ui.OK, true);
        GradientDrawable chipBg = Ui.round(0x1F3BA55D, 999, act);
        chipBg.setStroke(1, 0x333BA55D);
        runChip.setBackground(chipBg);
        runChip.setPadding(Ui.dp(act, 10), Ui.dp(act, 3), Ui.dp(act, 10), Ui.dp(act, 3));
        colL.addView(runChip);

        TextView brand = Ui.text(act, "APK → Project Extractor", 15, Ui.INK, true);
        brand.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 2));
        colL.addView(brand);

        statProjects = Ui.text(act, "Projects: 0", 13, Ui.MUTED, false);
        colL.addView(statProjects);
        statLast = Ui.text(act, "Last: —", 13, Ui.MUTED, false);
        colL.addView(statLast);
        statStorage = Ui.text(act, "", 12, Ui.MUTED, false);
        statStorage.setPadding(0, Ui.dp(act, 6), 0, 0);
        colL.addView(statStorage);

        ImageView dashLogo = new ImageView(act);
        dashLogo.setImageResource(R.drawable.logo);
        dashLogo.setAlpha(0.95f);
        dashRow.addView(dashLogo, new LinearLayout.LayoutParams(
                Ui.dp(act, 62), Ui.dp(act, 62)));

        FrameLayout barHost = new FrameLayout(act);
        barHost.setBackgroundResource(Ui.BG);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 4));
        blp.topMargin = Ui.dp(act, 12);
        accentBar = new View(act);
        GradientDrawable barBg = Ui.round(Ui.ACCENT, 999, act);
        accentBar.setBackground(barBg);
        accentBar.setPivotX(0f);
        barHost.addView(accentBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        dash.addView(barHost, blp);

        storageHint = Ui.text(act, "", 11.5f, Ui.MUTED, false);
        storageHint.setPadding(0, Ui.dp(act, 8), 0, 0);
        dash.addView(storageHint);

        // ---------- History ----------
        TextView hist = Ui.text(act, "PROJECTS", 12, Ui.MUTED, true);
        hist.setPadding(pad, Ui.dp(act, 12), pad, Ui.dp(act, 6));
        content.addView(hist);

        FrameLayout fl = new FrameLayout(act);
        content.addView(fl, Ui.lpWeight(act, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        emptyState = new LinearLayout(act);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        ImageView big = new ImageView(act);
        big.setImageResource(R.drawable.logo);
        big.setAlpha(0.9f);
        emptyState.addView(big, new LinearLayout.LayoutParams(
                Ui.dp(act, 96), Ui.dp(act, 96)));
        TextView et = Ui.text(act, "No projects yet", 18, Ui.INK, true);
        et.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 4));
        emptyState.addView(et);
        TextView es = Ui.text(act,
                "Tap the + button to create a project, pick an APK, and convert it into a\n"
                        + "readable, Android-project-style folder structure delivered as a ZIP.",
                13, Ui.MUTED, false);
        es.setGravity(Gravity.CENTER);
        es.setPadding(Ui.dp(act, 32), 0, Ui.dp(act, 32), 0);
        es.setLineSpacing(Ui.dp(act, 2), 1f);
        emptyState.addView(es);
        fl.addView(emptyState, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        list = new ListView(act);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setPadding(pad, Ui.dp(act, 2), pad, Ui.dp(act, 110));
        list.setClipToPadding(false);
        fl.addView(list, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // ---------- FAB ----------
        final FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(
                Ui.dp(act, 58), Ui.dp(act, 58), Gravity.BOTTOM | Gravity.END);
        fabLp.rightMargin = Ui.dp(act, 22);
        fabLp.bottomMargin = Ui.dp(act, 26);
        TextView fab = Ui.text(act, "+", 28, android.graphics.Color.WHITE, true);
        fab.setGravity(Gravity.CENTER);
        GradientDrawable circle = Ui.circle(Ui.ACCENT);
        fab.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), circle, null));
        fab.setElevation(Ui.dp(act, 8));
        fab.setOnClickListener(v -> onFab());
        outer.addView(fab, fabLp);

        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                showActions(adapter.getItem(position)));

        AlphaAnimation ea = new AlphaAnimation(0f, 1f);
        ea.setDuration(320);
        ea.setInterpolator(new DecelerateInterpolator());
        TranslateAnimation ta = new TranslateAnimation(0, 0, Ui.dp(act, 18), 0);
        ta.setDuration(320);
        ta.setInterpolator(new DecelerateInterpolator());
        dash.startAnimation(ea);
        dash.startAnimation(ta);
        return outer;
    }

    private ImageView iconButton(int res, Runnable onClick) {
        ImageView iv = new ImageView(act);
        iv.setImageResource(res);
        int p = Ui.dp(act, 7);
        iv.setPadding(p, p, p, p);
        iv.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(act, 36), Ui.dp(act, 36));
        lp.leftMargin = Ui.dp(act, 4);
        iv.setLayoutParams(lp);
        return iv;
    }

    private void onFab() {
        if (EngineState.getPhase() == EngineState.Phase.RUNNING) {
            new AlertDialog.Builder(act)
                    .setTitle("Conversion in progress")
                    .setMessage("A project is currently being converted. Please wait for it to finish or cancel it from the progress screen.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        act.push(new NewProjectScreen(act));
    }

    private int statusColor(String status) {
        if (status == null) return Ui.NEUTRAL;
        switch (status) {
            case "SUCCESS": return Ui.OK;
            case "PARTIAL": return Ui.WARN;
            case "RUNNING": return Ui.ACCENT;
            case "CANCELLED": return Ui.NEUTRAL;
            default: return Ui.BAD;
        }
    }

    @Override
    public void onShow() {
        refresh();

        EngineResult r = EngineState.consumeUnseenResult();
        if (r != null) {
            act.push(new ResultScreen(act, r));
            return;
        }
        if (adapter != null) adapter.reload();
    }

    private void refresh() {
        List<ProjectRecord> all = ProjectStore.load(act);
        statProjects.setText("Projects: " + all.size());
        if (!all.isEmpty()) {
            ProjectRecord last = all.get(0);
            statLast.setText("Last: " + last.name + " · " + last.status);
        } else {
            statLast.setText("Last: —");
        }
        statStorage.setText("Output: " + Store.describeMode(act));

        boolean running = EngineState.getPhase() == EngineState.Phase.RUNNING;
        runChip.setText(running ? "RUNNING" : "READY");
        runChip.setTextColor(running ? Ui.ACCENT : Ui.OK);
        GradientDrawable chipBg = Ui.round(running ? 0x1F958CE8 : 0x1F3BA55D, 999, act);
        chipBg.setStroke(1, running ? 0x33958CE8 : 0x333BA55D);
        runChip.setBackground(chipBg);

        if (Build.VERSION.SDK_INT >= 30 && !Store.hasAllFiles(act)) {
            storageHint.setText("Tip: grant “All files access” in Settings to export to the exact\n/storage/emulated/0/APKLens/ folder. Otherwise ZIPs go to Download/APKLens/.");
            storageHint.setVisibility(VISIBLE);
        } else {
            storageHint.setVisibility(GONE);
        }

        if (adapter != null) adapter.reload();
        emptyState.setVisibility(adapter.getCount() == 0 ? VISIBLE : GONE);
        list.setVisibility(adapter.getCount() == 0 ? GONE : VISIBLE);
    }

    private void showActions(ProjectRecord rec) {
        new AlertDialog.Builder(act)
                .setTitle(rec.name)
                .setItems(new CharSequence[]{"Open ZIP", "Share ZIP", "Copy path", "Delete project"},
                        (d, which) -> {
                            if (which == 0) Store.openZip(act, rec);
                            else if (which == 1) Store.shareZip(act, rec);
                            else if (which == 2) {
                                ClipboardManager cm = (ClipboardManager)
                                        act.getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(ClipData.newPlainText("path", rec.zipPathDisplay()));
                                Toast.makeText(act, "Path copied", Toast.LENGTH_SHORT).show();
                            } else confirmDelete(rec);
                        })
                .show();
    }

    private void confirmDelete(ProjectRecord rec) {
        new AlertDialog.Builder(act)
                .setTitle("Delete project?")
                .setMessage("Removes the history entry"
                        + (rec.isDirectFile() ? " and the exported ZIP file." : ". The exported ZIP in system storage is kept."))
                .setPositiveButton("Delete", (d, w) -> {
                    Store.deleteArtifact(act, rec);
                    ProjectStore.remove(act, rec.id);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private class Adapter extends BaseAdapter {
        private List<ProjectRecord> items = ProjectStore.load(act);
        private final SimpleDateFormat fmt =
                new SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault());

        void reload() { items = ProjectStore.load(act); notifyDataSetChanged(); }

        @Override public int getCount() { return items.size(); }
        @Override public ProjectRecord getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout holder;
            LinearLayout row;
            View dot;
            TextView name, sub;

            if (convertView instanceof FrameLayout) {
                holder = (FrameLayout) convertView;
                row = (LinearLayout) holder.getChildAt(0);
                dot = row.getChildAt(0);
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                name = (TextView) col.getChildAt(0);
                sub = (TextView) col.getChildAt(1);
            } else {
                row = Ui.row(act);
                GradientDrawable bg = Ui.round(Ui.SURFACE, 16, act);
                row.setBackground(bg);
                row.setElevation(Ui.dp(act, 2));
                int p = Ui.dp(act, 14);
                row.setPadding(p, p, p, p);

                dot = new View(act);
                row.addView(dot, new LinearLayout.LayoutParams(Ui.dp(act, 10), Ui.dp(act, 10)));

                LinearLayout col = new LinearLayout(act);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams clp = Ui.lpWeight(act,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                clp.leftMargin = Ui.dp(act, 12);
                row.addView(col, clp);
                name = Ui.text(act, "", 15, Ui.INK, true);
                col.addView(name);
                sub = Ui.text(act, "", 12, Ui.MUTED, false);
                sub.setPadding(0, Ui.dp(act, 2), 0, 0);
                col.addView(sub);

                TextView chev = Ui.text(act, "›", 20, Ui.MUTED, false);
                row.addView(chev);

                holder = new FrameLayout(act);
                int m = Ui.dp(act, 5);
                holder.setPadding(0, m, 0, m);
                holder.addView(row, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            }

            ProjectRecord r = getItem(position);
            GradientDrawable d = Ui.circle(statusColor(r.status));
            dot.setBackground(d);
            name.setText(r.name);
            String size = r.zipSize > 0 ? " · " + Ui.human(r.zipSize) : "";
            sub.setText(r.apkName + " · " + fmt.format(new Date(r.date)) + " · " + r.status + size);
            return holder;
        }
    }

    private ObjectAnimator pulse;

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        pulse = ObjectAnimator.ofFloat(accentBar, "scaleX", 0.35f, 1f);
        pulse.setDuration(1500);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setInterpolator(new DecelerateInterpolator());
        pulse.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (pulse != null) pulse.cancel();
        super.onDetachedFromWindow();
    }
}
