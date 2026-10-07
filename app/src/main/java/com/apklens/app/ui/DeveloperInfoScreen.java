package com.apklens.app.ui;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

/** Developer Info page — content is loaded from assets/developer.html (editable by the developer). */
public class DeveloperInfoScreen extends Screen {

    private WebView web;

    public DeveloperInfoScreen(MainActivity act) { super(act); }

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

        web = new WebView(act);
        WebSettings s = web.getSettings();
        // The page is a static asset — it needs no file/content access and no JavaScript.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptEnabled(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setDefaultTextEncodingName("utf-8");
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setWebViewClient(new WebViewClient() {
            // The app has no INTERNET permission by design: external links open in the user's
            // browser instead of inside this WebView (where target=_blank silently did nothing).
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme) || "tg".equals(scheme)
                        || "mailto".equals(scheme)) {
                    try {
                        act.startActivity(new Intent(Intent.ACTION_VIEW, u)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        Toast.makeText(act, "No app can open this link", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                return false; // our own asset files
            }
        });
        web.loadUrl("file:///android_asset/developer.html");
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Release the WebView's native resources when the screen is popped.
        if (web != null) { web.destroy(); web = null; }
    }
}
