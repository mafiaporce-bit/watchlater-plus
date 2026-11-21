package com.jpsums.watchlater;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The Home screen widget: how many videos are waiting, with the next one's picture.
 * Tap the picture to watch it, tap the count to open the "To watch" list.
 */
public class WatchWidget extends AppWidgetProvider {

    private static Handler worker;
    private static final Runnable[] PENDING = new Runnable[1];

    private static synchronized Handler worker() {
        if (worker == null) {
            HandlerThread t = new HandlerThread("watchlater-widget");
            t.start();
            worker = new Handler(t.getLooper());
        }
        return worker;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refreshSoon(context);
    }

    /** The list changed: redraw the widget shortly (several changes in a row redraw once). */
    public static void refreshSoon(Context context) {
        final Context c = context.getApplicationContext();
        try {
            int[] ids = AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, WatchWidget.class));
            if (ids == null || ids.length == 0) return;          // no widget on the Home screen
        } catch (Exception e) {
            return;
        }
        Handler h = worker();
        synchronized (PENDING) {
            if (PENDING[0] != null) h.removeCallbacks(PENDING[0]);
            PENDING[0] = () -> draw(c, true);
            h.postDelayed(PENDING[0], 600);
        }
    }

    /** Puts the widget on the Home screen (the phone asks you to confirm). */
    static boolean askToPin(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            if (m == null || !m.isRequestPinAppWidgetSupported()) return false;
            return m.requestPinAppWidget(new ComponentName(c, WatchWidget.class), null, null);
        } catch (Exception e) {
            return false;
        }
    }

    private static String ytId(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:youtu\\.be/|[?&]v=|/shorts/|/live/|/embed/)([\\w-]{6,20})").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    private static String sourceName(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (u.contains("facebook.com") || u.contains("fb.watch")) return "Facebook";
        if (u.contains("tiktok.com")) return "TikTok";
        if (u.contains("instagram.com")) return "Instagram";
        if (u.contains("youtube.com") || u.contains("youtu.be")) return "YouTube";
        if (u.contains("threads.")) return "Threads";
        if (u.contains("x.com") || u.contains("twitter.com")) return "X";
        String h = Sorter.hostToken(url);
        return h.isEmpty() ? "Link" : h.substring(1);
    }

    private static Bitmap loadThumb(File f) {
        if (!f.exists()) return null;
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), b);
            BitmapFactory.Options o = new BitmapFactory.Options();
            int s = 1;
            while (b.outWidth / (s * 2) >= 360) s *= 2;
            o.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** YouTube's picture is not kept by the app (the page shows it directly), so fetch it once for the widget. */
    private static boolean fetchYouTube(File dst, String id) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg").openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(8000);
            try {
                if (con.getResponseCode() != 200) return false;
                InputStream in = con.getInputStream();
                File tmp = new File(dst.getPath() + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[16384];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0 && total < 2_000_000) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                } finally {
                    out.close();
                    in.close();
                }
                return tmp.renameTo(dst);
            } finally {
                con.disconnect();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static void draw(Context c, boolean mayFetch) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, WatchWidget.class));
            if (ids == null || ids.length == 0) return;

            JSONObject data = new JSONObject(Store.readRaw(c));
            JSONObject previews = new JSONObject(Previews.readRaw(c));
            JSONArray items = data.optJSONArray("items");
            List<JSONObject> waiting = new ArrayList<JSONObject>();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null && !it.optBoolean("watched")) waiting.add(it);
            }
            Collections.sort(waiting, (a, b) -> Long.compare(b.optLong("addedAt"), a.optLong("addedAt")));

            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            int n = waiting.size();
            v.setTextViewText(R.id.w_count, String.valueOf(n));
            v.setTextViewText(R.id.w_label, n == 1 ? "video to watch" : "videos to watch");

            Intent list = new Intent(c, MainActivity.class).putExtra("show", "towatch")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent openList = PendingIntent.getActivity(c, 30, list, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            v.setOnClickPendingIntent(R.id.w_top, openList);

            if (n == 0) {
                v.setViewVisibility(R.id.w_next, View.GONE);
                v.setViewVisibility(R.id.w_done, View.VISIBLE);
                v.setOnClickPendingIntent(R.id.w_done, openList);
            } else {
                JSONObject next = waiting.get(0);
                String url = next.optString("url");
                v.setViewVisibility(R.id.w_next, View.VISIBLE);
                v.setViewVisibility(R.id.w_done, View.GONE);
                v.setTextViewText(R.id.w_title, Store.displayName(next, previews));
                v.setTextViewText(R.id.w_source, "Next · " + sourceName(url));

                String safeId = next.optString("id").replaceAll("[^A-Za-z0-9_-]", "");
                File thumb = new File(Previews.thumbsDir(c), safeId + ".jpg");
                String yt = ytId(url);
                Bitmap bmp = loadThumb(thumb);
                if (bmp == null && !yt.isEmpty() && mayFetch && fetchYouTube(thumb, yt)) bmp = loadThumb(thumb);
                if (bmp != null) {
                    v.setImageViewBitmap(R.id.w_thumb, bmp);
                    v.setViewVisibility(R.id.w_thumb, View.VISIBLE);
                    v.setViewVisibility(R.id.w_nothumb, View.GONE);
                } else {
                    v.setViewVisibility(R.id.w_thumb, View.GONE);
                    v.setViewVisibility(R.id.w_nothumb, View.VISIBLE);
                    v.setTextViewText(R.id.w_nothumb, sourceName(url));
                }

                // tap the video: open it in its own app (Facebook, TikTok...) and mark it to ask "watched?" later
                PendingIntent watch = null;
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    watch = PendingIntent.getActivity(c, 31, view, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                }
                v.setOnClickPendingIntent(R.id.w_next, watch != null ? watch : openList);
            }
            m.updateAppWidget(ids, v);
        } catch (Exception e) {
            // the widget simply keeps its last picture
        }
    }
}
