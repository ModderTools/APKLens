package com.apklens.app.ui;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.apklens.app.service.ConvertService;
import com.apklens.app.service.EngineState;

public class ProgressScreen extends Screen {

    private TextView stageTv, detailTv, percentTv;
    private ProgressBar bar;
    private Button cancelBtn;
    private boolean pushedResult;

    public ProgressScreen(MainActivity act) { super(act); }

    @Override
    protected View build() {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(header("Converting…", false));

        int pad = Ui.dp(act, 18);
        LinearLayout card = Ui.card(act);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(pad, Ui.dp(act, 20), pad, pad);
        root.addView(card, clp);

        TextView title = Ui.text(act, EngineState.getProjectName(), 17, Ui.INK, true);
        card.addView(title);

        percentTv = Ui.text(act, "0%", 40, Ui.ACCENT_TEXT, true);
        percentTv.setPadding(0, Ui.dp(act, 10), 0, 0);
        card.addView(percentTv);

        stageTv = Ui.text(act, "Preparing…", 15, Ui.INK, true);
        stageTv.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 4));
        card.addView(stageTv);

        bar = Ui.horizontalProgress(act);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 14));
        blp.topMargin = Ui.dp(act, 8);
        card.addView(bar, blp);

        detailTv = Ui.text(act, "", 12.5f, Ui.MUTED, false);
        detailTv.setPadding(0, Ui.dp(act, 10), 0, 0);
        card.addView(detailTv);

        cancelBtn = Ui.outline(act, "Cancel", Ui.BAD, Ui.BAD);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 48));
        cbp.topMargin = Ui.dp(act, 20);
        cancelBtn.setOnClickListener(v -> {
            ConvertService.cancel(act);
            cancelBtn.setEnabled(false);
            cancelBtn.setText("Cancelling…");
        });
        card.addView(cancelBtn, cbp);

        TextView note = Ui.text(act,
                "Heavy decompilation runs in a foreground service. You can leave this screen — "
                        + "or the app — and the finished project will appear in your history. "
                        + "Large apps can take several minutes.",
                12, Ui.MUTED, false);
        note.setPadding(pad, Ui.dp(act, 4), pad, 0);
        root.addView(note);

        return root;
    }

    private final EngineState.Listener listener = this::refresh;

    @Override
    public void onShow() {
        EngineState.addListener(listener);
        refresh();
    }

    @Override
    public void onHide() {
        EngineState.removeListener(listener);
    }

    private void refresh() {
        act.runOnUiThread(() -> {
            percentTv.setText(EngineState.getPercent() + "%");
            stageTv.setText(EngineState.getStage());
            detailTv.setText(EngineState.getDetail());
            bar.setProgress(EngineState.getPercent());
            if (EngineState.isCancelRequested() && EngineState.isRunning()) {
                cancelBtn.setEnabled(false);
                cancelBtn.setText("Cancelling…");
            }

            if (EngineState.getPhase() == EngineState.Phase.FINISHED && !pushedResult) {
                com.apklens.app.engine.EngineResult r = EngineState.consumeUnseenResult();
                pushedResult = true;
                if (r != null) {
                    // Replace this screen: otherwise Back from Result returns to a dead progress page.
                    act.replaceTop(new ResultScreen(act, r));
                } else {
                    act.popToRoot();
                }
            }
        });
    }
}
