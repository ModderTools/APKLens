package com.apklens.app.ui;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;

/** Developer Info page — content is loaded from assets/developer.html (editable by the developer). */
public class DeveloperInfoScreen extends Screen {

    public DeveloperInfoScreen(MainActivity act) { super(act); }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected View build() {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(header("Developer Info", true));

        boolean exists = false;
        try (InputStream is = act.getAssets().open("developer.html")) {
            exists = is.read() >= 0;
        } catch (IOException ignored) {}

        if (!exists) {
            TextView fb = Ui.text(act,
                    "developer.html was not found in app assets.\n\n"
                            + "Place your file at app/src/main/assets/developer.html — "
                            + "it will be shown here automatically.",
                    14, Ui.MUTED, false);
            int p = Ui.dp(act, 24);
            fb.setPadding(p, p, p, p);
            root.addView(fb);
            return root;
        }

        WebView wv = new WebView(act);
        WebSettings s = wv.getSettings();
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setJavaScriptEnabled(false);
        s.setDefaultTextEncodingName("utf-8");
        wv.setBackgroundColor(Color.TRANSPARENT);
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                // Leave the page; the HTML itself communicates status.
            }
        });
        wv.loadUrl("file:///android_asset/developer.html");
        root.addView(wv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        return root;
    }
}
