package com.apklens.app.ui;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * Base class for all screens: a FrameLayout built 100% in Java.
 *
 * TWO-PHASE CONSTRUCTION (important):
 * The constructor does NOT call build(). A superclass constructor invoking an
 * overridable method runs BEFORE subclass fields assigned in subclass
 * constructors exist (ResultScreen.r was null -> NPE). Instead, MainActivity.push()
 * calls ensureBuilt() once, after the subclass is fully constructed.
 */
public abstract class Screen extends FrameLayout {
    protected final MainActivity act;
    private boolean built = false;

    public Screen(MainActivity act) {
        super(act);
        this.act = act;
        setBackgroundColor(Ui.BG);
    }

    protected abstract View build();

    /** Builds the screen exactly once. Called by MainActivity.push() before onShow(). */
    public final void ensureBuilt() {
        if (built) return;
        built = true;
        addView(build(), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** Called when the screen becomes the visible top of the stack. */
    public void onShow() {}

    /** Called when the screen is covered or removed. */
    public void onHide() {}

    /** Return true to consume the back press. */
    public boolean onBack() { return false; }

    /** Standard header: optional back arrow + title. Returns the header row. */
    protected LinearLayout header(String title, boolean withBack) {
        LinearLayout h = Ui.row(act);
        int pad = Ui.dp(act, 16);
        h.setPadding(pad, pad, pad, Ui.dp(act, 8));
        if (withBack) {
            ImageView back = new ImageView(act);
            back.setImageResource(com.apklens.app.R.drawable.ic_back);
            back.setBackground(new android.graphics.drawable.ColorDrawable(0x00000000));
            int bp = Ui.dp(act, 6);
            back.setPadding(bp, bp, bp, bp);
            back.setOnClickListener(v -> act.pop());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(act, 34), Ui.dp(act, 34));
            lp.rightMargin = Ui.dp(act, 10);
            h.addView(back, lp);
        }
        android.widget.TextView t = Ui.text(act, title, 20, Ui.INK, true);
        h.addView(t, Ui.lpWeight(act, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return h;
    }
}
