package com.jpsums.watchlater;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

/** The main screen: the WatchLater+ page (assets/index.html) with a small bridge to the phone. */
public class MainActivity extends Activity {

    private static final int BG_DARK = 0xFF101014;
    private static final int BG_LIGHT = 0xFFF6F3EC;

    private WebView webView;
    private boolean pageReady = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(BG_DARK);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        webView.addJavascriptInterface(new Bridge(), "WLNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            /** The page never leaves the app: a real link opens outside instead. */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                openOutside(u.toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
            }
        });
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // pick up videos saved from the Share popup while the app was in the background
        if (pageReady) webView.evaluateJavascript("window.WLResume && window.WLResume()", null);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (!pageReady) {
            moveTaskToBack(true);
            return;
        }
        webView.evaluateJavascript("(window.WLBack && window.WLBack()) ? 1 : 0", value -> {
            if (value == null || !value.contains("1")) moveTaskToBack(true);
        });
    }

    /** Opens a video link in its own app (Facebook, YouTube...) or the browser. */
    private void openOutside(String url) {
        if (url == null) return;
        String low = url.toLowerCase();
        if (!low.startsWith("https://") && !low.startsWith("http://")) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void applyBars(boolean dark) {
        int bg = dark ? BG_DARK : BG_LIGHT;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        View decor = getWindow().getDecorView();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        int flags = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(dark ? (flags & ~light) : (flags | light));
        webView.setBackgroundColor(bg);
    }

    /** What the page can ask the phone to do. Called from JavaScript as WLNative.xxx(). */
    public final class Bridge {

        @JavascriptInterface
        public String getData() {
            return Store.readRaw(MainActivity.this);
        }

        @JavascriptInterface
        public void saveData(String json) {
            Store.writeRaw(MainActivity.this, json);
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            runOnUiThread(() -> openOutside(url));
        }

        @JavascriptInterface
        public void shareText(final String text) {
            runOnUiThread(() -> {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
                    startActivity(Intent.createChooser(send, "Send video to"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Could not share", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void setTheme(final boolean dark) {
            runOnUiThread(() -> applyBars(dark));
        }
    }
}
