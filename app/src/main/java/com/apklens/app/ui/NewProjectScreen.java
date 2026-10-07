package com.apklens.app.ui;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.apklens.app.engine.Io;
import com.apklens.app.engine.ZipPreview;
import com.apklens.app.platform.Store;
import com.apklens.app.service.ConvertService;
import com.apklens.app.service.EngineState;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class NewProjectScreen extends Screen {

    private EditText nameEt;
    private TextView selName, selMeta, selPreview, statusLine;
    private LinearLayout selCard;
    private Button convertBtn;

    private File stagedApk;
    private String apkDisplayName = "";
    private long apkSize = -1;
    private ZipPreview.Summary preview;
    private boolean importing;
    private boolean nameTouched;       // user typed a name → never overwrite it automatically
    private Runnable pendingAfterWritePerm;

    public NewProjectScreen(MainActivity act) { super(act); }

    @Override
    protected View build() {
        ScrollView scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root, new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(header("New Project", true));

        int pad = Ui.dp(act, 18);
        LinearLayout card = Ui.card(act);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(pad, Ui.dp(act, 4), pad, pad);
        root.addView(card, clp);

        card.addView(Ui.label(act, "APK FILE"));

        Button pick = Ui.outline(act, "Choose APK from storage…", Ui.ACCENT, Ui.INK);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 52));
        plp.topMargin = Ui.dp(act, 8);
        pick.setOnClickListener(v -> startPicker());
        card.addView(pick, plp);
        card.addView(Ui.text(act, "Also works with .xapk / .apks / .apkm split bundles — "
                + "or use “Open with ApkLens” from any file manager.", 12, Ui.MUTED, false));

        selCard = Ui.card(act);
        GradientDrawable selBg = Ui.roundStroke(act, 0xFFF6F6FA, Ui.LINE, 14);
        selCard.setBackground(selBg);
        selCard.setElevation(0);
        selCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(act, 14);
        card.addView(selCard, slp);

        selName = Ui.text(act, "", 15, Ui.INK, true);
        selCard.addView(selName);
        selMeta = Ui.text(act, "", 12.5f, Ui.MUTED, false);
        selMeta.setPadding(0, Ui.dp(act, 3), 0, 0);
        selCard.addView(selMeta);
        selPreview = Ui.text(act, "", 12.5f, Ui.ACCENT_TEXT, false);
        selPreview.setPadding(0, Ui.dp(act, 6), 0, 0);
        selCard.addView(selPreview);

        View sp = new View(act);
        card.addView(sp, new LinearLayout.LayoutParams(1, Ui.dp(act, 18)));
        card.addView(Ui.label(act, "PROJECT NAME"));
        nameEt = Ui.editText(act, "e.g. My First Analysis");
        nameEt.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 50));
        nlp.topMargin = Ui.dp(act, 8);
        card.addView(nameEt, nlp);
        // FIX: the original never re-evaluated the button when the name changed, so typing the
        // name AFTER choosing the APK left "Convert" disabled forever.
        nameEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                if (nameEt.hasFocus()) nameTouched = true;
                maybeEnableConvert();
            }
        });

        statusLine = Ui.text(act, "", 13, Ui.WARN, false);
        statusLine.setPadding(0, Ui.dp(act, 12), 0, 0);
        statusLine.setVisibility(View.GONE);
        card.addView(statusLine);

        convertBtn = Ui.primary(act, "Convert to Project");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 54));
        blp.topMargin = Ui.dp(act, 20);
        convertBtn.setOnClickListener(v -> onConvert());
        convertBtn.setEnabled(false);
        convertBtn.setAlpha(0.45f);
        card.addView(convertBtn, blp);

        TextView note = Ui.text(act,
                "ApkLens reconstructs readable Java source from compiled DEX bytecode. "
                        + "Output is decompiled/reconstructed — not the original source code — "
                        + "and may differ from what the developer wrote, especially for "
                        + "obfuscated or heavily optimized apps.",
                12, Ui.MUTED, false);
        note.setPadding(pad, 0, pad, Ui.dp(act, 30));
        root.addView(note);

        updateRunningState();
        return scroll;
    }

    @Override
    public void onShow() { updateRunningState(); maybeEnableConvert(); }

    /** The screen is going away without converting → don't leave a multi-hundred-MB copy in cache. */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (stagedApk != null && !EngineState.isRunning() && !handedToService) {
            Io.deleteRecursively(stagedApk);
            stagedApk = null;
        }
    }
    private boolean handedToService;

    private void updateRunningState() {
        if (statusLine == null) return;
        if (EngineState.getPhase() == EngineState.Phase.RUNNING) {
            showStatus("A conversion is already running — finish or cancel it first.", Ui.WARN);
        } else if (statusLine.getCurrentTextColor() == Ui.WARN && !importing) {
            statusLine.setVisibility(View.GONE);
        }
    }

    private void showStatus(String msg, int color) {
        statusLine.setText(msg);
        statusLine.setTextColor(color);
        statusLine.setVisibility(View.VISIBLE);
    }

    private void startPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive",
                "application/java-archive",
                "application/octet-stream",
                "application/zip",
                "application/x-zip-compressed"});
        act.startActivityForResult(i, MainActivity.REQ_PICK_APK);
    }

    /** Called by MainActivity with the SAF result. */
    public void onApkPicked(int resultCode, Intent data) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return;
        importUri(data.getData());
    }

    /** Imports a content:// or file:// APK (picker result, "Open with", or share). */
    public void importUri(Uri uri) {
        if (importing) return;
        apkDisplayName = "selected.apk";
        apkSize = -1;
        try (Cursor c = act.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && c.getString(ni) != null) apkDisplayName = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) apkSize = c.getLong(si);
            }
        } catch (Exception ignored) {}
        if ("file".equals(uri.getScheme()) && uri.getLastPathSegment() != null) {
            apkDisplayName = uri.getLastPathSegment();
        }

        // Fail fast when the phone cannot hold a working copy — much better than dying at 90%.
        long free = act.getCacheDir().getUsableSpace();
        if (apkSize > 0 && free > 0 && free < apkSize * 4) {
            showStatus("Not enough free storage: need about " + Io.human(apkSize * 4)
                    + " of working space, only " + Io.human(free) + " is free.", Ui.BAD);
            return;
        }

        discardStaged();
        selCard.setVisibility(View.VISIBLE);
        selName.setText(apkDisplayName);
        selMeta.setText(apkSize >= 0 ? Ui.human(apkSize) : "");
        selPreview.setTextColor(Ui.ACCENT_TEXT);
        selPreview.setText("Importing APK…");
        statusLine.setVisibility(View.GONE);
        importing = true;
        setConvertEnabled(false);

        final Uri u = uri;
        new Thread(() -> {
            File out = new File(act.getCacheDir(), "picked_" + System.currentTimeMillis() + ".apk");
            try {
                long cap = 4L * 1024 * 1024 * 1024; // 4 GB import guard
                try (InputStream in = act.getContentResolver().openInputStream(u);
                     FileOutputStream os = new FileOutputStream(out)) {
                    if (in == null) throw new java.io.IOException("Cannot open selected file");
                    Io.copy(in, os, cap);
                }
                final ZipPreview.Summary sum = ZipPreview.scan(out);
                final long size = out.length();
                act.runOnUiThread(() -> {
                    importing = false;
                    stagedApk = out;
                    preview = sum;
                    if (apkSize < 0) { apkSize = size; selMeta.setText(Ui.human(size)); }
                    onStagedReady();
                });
            } catch (Exception e) {
                Io.deleteRecursively(out);
                act.runOnUiThread(() -> {
                    importing = false;
                    selPreview.setTextColor(Ui.BAD);
                    selPreview.setText("Import failed: " + e.getMessage());
                    Toast.makeText(act, "Could not read that file", Toast.LENGTH_LONG).show();
                });
            }
        }, "apklens-import").start();
    }

    private void discardStaged() {
        if (stagedApk != null) { Io.deleteRecursively(stagedApk); stagedApk = null; }
        preview = null;
    }

    private void onStagedReady() {
        if (preview == null) return;
        if (!preview.valid) {
            selPreview.setTextColor(Ui.BAD);
            selPreview.setText("Not a valid ZIP/APK archive — the file may be corrupted.");
            discardStaged();
            setConvertEnabled(false);
            return;
        }
        if (!preview.convertible()) {
            selPreview.setTextColor(Ui.BAD);
            selPreview.setText("This ZIP has no AndroidManifest.xml and no classes.dex — it is not an APK.");
            discardStaged();
            setConvertEnabled(false);
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (preview.bundle) {
            sb.append("Split bundle · ").append(preview.innerApks).append(" APKs · base: ")
                    .append(preview.baseApk);
        } else {
            sb.append(preview.dexCount == 0 ? "No DEX found"
                    : preview.dexCount + " DEX file" + (preview.dexCount > 1 ? "s" : ""));
            sb.append(preview.hasManifest ? " · manifest ✓" : " · manifest ✗");
            if (preview.hasArsc) sb.append(" · resources ✓");
            if (preview.assetCount > 0) sb.append(" · assets: ").append(preview.assetCount);
            if (!preview.abis.isEmpty()) sb.append(" · native: ").append(preview.abis);
        }
        selPreview.setTextColor(Ui.ACCENT_TEXT);
        selPreview.setText(sb.toString());

        // Suggest a name from the file name, but never overwrite something the user typed.
        if (!nameTouched || nameEt.getText().toString().trim().isEmpty()) {
            String base = apkDisplayName.replaceAll("(?i)\\.(apk|xapk|apks|apkm)$", "");
            nameEt.setText(sanitize(base));
            nameEt.setSelection(nameEt.getText().length());
            nameTouched = false;
        }
        maybeEnableConvert();
    }

    private void setConvertEnabled(boolean on) {
        if (convertBtn == null) return;
        convertBtn.setEnabled(on);
        convertBtn.setAlpha(on ? 1f : 0.45f);
    }

    private void maybeEnableConvert() {
        if (convertBtn == null || nameEt == null) return;
        boolean ok = stagedApk != null && !importing
                && !sanitize(nameEt.getText().toString().trim()).isEmpty()
                && EngineState.getPhase() != EngineState.Phase.RUNNING;
        setConvertEnabled(ok);
    }

    private void onConvert() {
        if (EngineState.getPhase() == EngineState.Phase.RUNNING) {
            Toast.makeText(act, "A conversion is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        if (stagedApk == null) {
            Toast.makeText(act, "Choose an APK first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Store.directAvailable(act)) {
            startConversion();
            return;
        }
        // Direct mode not possible → on API 30+ MediaStore works with no permission.
        if (Build.VERSION.SDK_INT >= 30) {
            startConversion();
            return;
        }
        // API <= 29: ask for WRITE to use the exact APKLens/ folder.
        pendingAfterWritePerm = this::startConversion;
        act.awaitPermission(MainActivity.REQ_WRITE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                granted -> {
                    if (granted) {
                        if (pendingAfterWritePerm != null) pendingAfterWritePerm.run();
                    } else {
                        Toast.makeText(act,
                                "Storage permission is required to export the ZIP on this Android version.",
                                Toast.LENGTH_LONG).show();
                    }
                    pendingAfterWritePerm = null;
                });
    }

    private void startConversion() {
        String projectName = sanitize(nameEt.getText().toString().trim());
        if (projectName.isEmpty()) projectName = "Project";

        act.ensureNotifPermission(); // fire-and-forget on API 33+

        handedToService = true; // the service now owns (and deletes) the staged copy
        ConvertService.start(act, stagedApk.getAbsolutePath(), projectName,
                apkDisplayName, apkSize);
        act.push(new ProgressScreen(act));
    }

    static String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == ' ' || ch == '-' || ch == '_' || ch == '.') b.append(ch);
            else b.append('_');
        }
        String r = b.toString().trim().replaceAll(" +", " ");
        if (r.length() > 60) r = r.substring(0, 60);
        return r.replaceAll("^[._ ]+|[._ ]+$", "");
    }
}
