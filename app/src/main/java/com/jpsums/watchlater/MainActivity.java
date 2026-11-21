package com.jpsums.watchlater;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONException;
import org.json.JSONObject;

/** The main screen: the WatchLater+ page (assets/index.html) with a small bridge to the phone. */
public class MainActivity extends Activity {

    private static final int BG_DARK = 0xFF101014;
    private static final int BG_LIGHT = 0xFFF6F3EC;

    private static final String NOTIFY_PERMISSION = "android.permission.POST_NOTIFICATIONS";
    private static final int ASK_NOTIFY = 7;

    private WebView webView;
    private boolean pageReady = false;
    private String pendingShow = null;     // a list to open once the page is ready (from the reminder)
    private boolean testAfterAllow = false;
    private boolean bubbleAfterAllow = false; // waiting for "Appear on top" to be switched on

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                // saved thumbnails, shown in the list as /thumbs/<video id>.jpg
                .addPathHandler("/thumbs/", new WebViewAssetLoader.InternalStoragePathHandler(this, Previews.thumbsDir(this)))
                // icons of the apps on this phone (Facebook, TikTok...), shown as /icons/<site>.png
                .addPathHandler("/icons/", new WebViewAssetLoader.InternalStoragePathHandler(this, AppIcons.dir(this)))
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
                openPendingList();
            }
        });
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");

        // a preview arrived in the background: let the page show it
        Previews.setListener((id, title, desc) -> runOnUiThread(() -> {
            if (pageReady) webView.evaluateJavascript("window.WLPreviews && window.WLPreviews()", null);
        }));
        Previews.prune(getApplicationContext());
        Reminders.schedule(this);
        BubbleService.startIfWanted(getApplicationContext());
        readIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntent(intent);
    }

    @Override
    protected void onDestroy() {
        Previews.setListener(null);
        super.onDestroy();
    }

    /** Tapping the reminder opens the app on the "To watch" list. */
    private void readIntent(Intent intent) {
        if (intent == null) return;
        String show = intent.getStringExtra("show");
        if (show == null) return;
        intent.removeExtra("show");
        pendingShow = show;
        if (pageReady) openPendingList();
    }

    private void openPendingList() {
        if (pendingShow == null) return;
        String list = "towatch".equals(pendingShow) ? "towatch" : "all";
        pendingShow = null;
        webView.evaluateJavascript("window.WLShow && window.WLShow('" + list + "')", null);
    }

    private boolean needsNotifyPermission() {
        return Build.VERSION.SDK_INT >= 33 && checkSelfPermission(NOTIFY_PERMISSION) != PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != ASK_NOTIFY) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            Reminders.schedule(this);
            if (testAfterAllow) Reminders.show(this, true);
        } else {
            webView.evaluateJavascript("window.WLNotifyDenied && window.WLNotifyDenied()", null);
        }
        testAfterAllow = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bubbleAfterAllow) {
            // back from the phone's "Appear on top" screen
            bubbleAfterAllow = false;
            if (BubbleService.allowed(this)) {
                BubbleService.start(getApplicationContext());
            } else if (pageReady) {
                webView.evaluateJavascript("window.WLBubbleDenied && window.WLBubbleDenied()", null);
            }
        }
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

        /* ----- Part 2 ----- */

        /** Titles and thumbnails fetched so far, as JSON keyed by video id. */
        @JavascriptInterface
        public String appIcons() {
            try {
                return AppIcons.build(getApplicationContext());
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String getPreviews() {
            return Previews.readRaw(MainActivity.this);
        }

        @JavascriptInterface
        public void fetchPreviews() {
            Previews.fetchMissing(getApplicationContext());
        }

        @JavascriptInterface
        public void refreshPreview(String id) {
            Previews.refresh(getApplicationContext(), id);
        }

        @JavascriptInterface
        public void dropPreview(String id) {
            Previews.drop(getApplicationContext(), id);
        }

        /** Auto-sort: returns {"id":"food","sure":true}, or "" when it has no idea. */
        @JavascriptInterface
        public String suggest(String text, String url) {
            try {
                JSONObject data = new JSONObject(Store.readRaw(MainActivity.this));
                Sorter.Pick p = Store.suggest(MainActivity.this, data, text == null ? "" : text, url == null ? "" : url);
                if (p.id.isEmpty()) return "";
                return new JSONObject().put("id", p.id).put("sure", p.sure).toString();
            } catch (JSONException e) {
                return "";
            }
        }

        /** You moved a video by hand: remember it for next time. */
        @JavascriptInterface
        public void learn(String text, String url, String folderId) {
            Store.learn(MainActivity.this, text == null ? "" : text, url == null ? "" : url, folderId, 2);
        }

        /** Reminder settings changed in the page. */
        @JavascriptInterface
        public void remindersChanged(final boolean on) {
            runOnUiThread(() -> {
                if (on && needsNotifyPermission()) {
                    testAfterAllow = false;
                    requestPermissions(new String[]{NOTIFY_PERMISSION}, ASK_NOTIFY);
                } else if (on && !Reminders.allowed(MainActivity.this)) {
                    webView.evaluateJavascript("window.WLNotifyDenied && window.WLNotifyDenied()", null);
                }
                Reminders.schedule(MainActivity.this);
            });
        }

        /** Floating button switched on or off in the page. */
        @JavascriptInterface
        public void setBubble(final boolean on) {
            runOnUiThread(() -> {
                if (!on) {
                    BubbleService.stop(getApplicationContext());
                    return;
                }
                if (BubbleService.allowed(MainActivity.this)) {
                    BubbleService.start(getApplicationContext());
                    return;
                }
                bubbleAfterAllow = true;
                Toast.makeText(MainActivity.this, "Find WatchLater+ and switch on \"Appear on top\", then come back",
                        Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                    } catch (Exception e2) {
                        bubbleAfterAllow = false;
                        webView.evaluateJavascript("window.WLBubbleDenied && window.WLBubbleDenied()", null);
                    }
                }
            });
        }

        @JavascriptInterface
        public void testReminder() {
            runOnUiThread(() -> {
                if (needsNotifyPermission()) {
                    testAfterAllow = true;
                    requestPermissions(new String[]{NOTIFY_PERMISSION}, ASK_NOTIFY);
                } else if (!Reminders.allowed(MainActivity.this)) {
                    webView.evaluateJavascript("window.WLNotifyDenied && window.WLNotifyDenied()", null);
                } else {
                    Reminders.show(MainActivity.this, true);
                }
            });
        }
    }
}
