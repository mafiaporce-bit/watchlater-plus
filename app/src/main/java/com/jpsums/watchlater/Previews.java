package com.jpsums.watchlater;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Previews: the title and thumbnail of each saved link, fetched in the background
 * so you can tell which video is which. Kept in their own small file (previews.json)
 * plus one picture per video in files/thumbs.
 *
 * One entry per video id: t = title, d = description, i = 1 when a thumbnail file exists,
 * a = when we tried, l = true once its words were used for learning.
 */
public final class Previews {

    public interface Listener {
        void onPreview(String id, String title, String desc);
    }

    private static final String FILE = "previews.json";
    private static final Object LOCK = new Object();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Set<String> BUSY = Collections.synchronizedSet(new HashSet<String>());
    private static volatile Listener appListener;

    private Previews() { }

    /** The main screen registers here to refresh itself when a preview arrives. */
    public static void setListener(Listener l) {
        appListener = l;
    }

    public static File thumbsDir(Context c) {
        File d = new File(c.getFilesDir(), "thumbs");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    private static String safe(String id) {
        return id == null ? "" : id.replaceAll("[^A-Za-z0-9_-]", "");
    }

    /* ---------- the file ---------- */

    public static String readRaw(Context c) {
        synchronized (LOCK) {
            return readLocked(c).toString();
        }
    }

    private static JSONObject readLocked(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return new JSONObject();
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            } finally {
                in.close();
            }
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    private static void writeLocked(Context c, JSONObject all) {
        File dst = new File(c.getFilesDir(), FILE);
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(all.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(dst)) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(dst);
            }
        } catch (IOException e) {
            // previews are a nice-to-have: the videos themselves are safe in the main file
        }
    }

    public static void markLearned(Context c, String id) {
        id = safe(id);
        if (id.isEmpty()) return;
        synchronized (LOCK) {
            try {
                JSONObject all = readLocked(c);
                JSONObject e = all.optJSONObject(id);
                if (e == null) e = new JSONObject();
                e.put("l", true);
                all.put(id, e);
                writeLocked(c, all);
            } catch (JSONException ex) {
                // ignore
            }
        }
    }

    public static void drop(Context c, String id) {
        id = safe(id);
        if (id.isEmpty()) return;
        synchronized (LOCK) {
            JSONObject all = readLocked(c);
            if (all.has(id)) {
                all.remove(id);
                writeLocked(c, all);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        new File(thumbsDir(c), id + ".jpg").delete();
    }

    /** Clears out previews of videos that were deleted, or shared but never saved. */
    public static void prune(final Context c) {
        POOL.execute(() -> {
            try {
                Set<String> live = new HashSet<String>();
                JSONArray items = new JSONObject(Store.readRaw(c)).optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it != null) live.add(safe(it.optString("id")));
                    }
                }
                long old = System.currentTimeMillis() - 60L * 60 * 1000;
                synchronized (LOCK) {
                    JSONObject all = readLocked(c);
                    Set<String> gone = new HashSet<String>();
                    Iterator<String> keys = all.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        JSONObject e = all.optJSONObject(k);
                        if (!live.contains(k) && (e == null || e.optLong("a") < old)) gone.add(k);
                    }
                    for (String k : gone) all.remove(k);
                    if (!gone.isEmpty()) writeLocked(c, all);
                }
                File[] files = thumbsDir(c).listFiles();
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName();
                        String id = name.endsWith(".jpg") ? name.substring(0, name.length() - 4) : name;
                        if (!live.contains(id) && f.lastModified() < old) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                }
            } catch (Exception e) {
                // tidy-up only
            }
        });
    }

    /* ---------- fetching ---------- */

    /** Looks for videos that have no preview yet and fetches them. Called when the app opens. */
    public static void fetchMissing(final Context c) {
        POOL.execute(() -> {
            try {
                JSONArray items = new JSONObject(Store.readRaw(c)).optJSONArray("items");
                if (items == null) return;
                JSONObject all;
                synchronized (LOCK) {
                    all = readLocked(c);
                }
                int started = 0;
                for (int i = items.length() - 1; i >= 0 && started < 15; i--) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    String id = safe(it.optString("id"));
                    JSONObject e = all.optJSONObject(id);
                    if (id.isEmpty() || (e != null && e.has("a"))) continue;
                    fetch(c, id, it.optString("url"), it.optString("title"), null);
                    started++;
                }
            } catch (Exception e) {
                // try again next time the app opens
            }
        });
    }

    /** Forget what we have for one video and fetch it again ("Refresh preview"). */
    public static void refresh(final Context c, final String rawId) {
        final String id = safe(rawId);
        if (id.isEmpty()) return;
        POOL.execute(() -> {
            try {
                JSONArray items = new JSONObject(Store.readRaw(c)).optJSONArray("items");
                if (items == null) return;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it != null && id.equals(safe(it.optString("id")))) {
                        run(c, id, it.optString("url"), "", null);
                        return;
                    }
                }
            } catch (Exception e) {
                // nothing to refresh
            }
        });
    }

    /**
     * Fetch the preview of one link in the background.
     * once (may be null) hears the result too; it is called on a background thread.
     */
    public static void fetch(final Context context, final String rawId, final String url,
                             final String knownTitle, final Listener once) {
        final Context c = context.getApplicationContext();
        final String id = safe(rawId);
        if (id.isEmpty() || url == null || !BUSY.add(id)) return;
        POOL.execute(() -> {
            try {
                run(c, id, url, knownTitle == null ? "" : knownTitle, once);
            } finally {
                BUSY.remove(id);
            }
        });
    }

    private static void run(Context c, String id, String url, String knownTitle, Listener once) {
        PageInfo info;
        boolean yt = isYouTube(url);
        try {
            // YouTube already shares its title, and its thumbnail needs no fetching
            info = (yt && !knownTitle.trim().isEmpty()) ? new PageInfo() : load(url);
        } catch (IOException e) {
            return; // offline or unreachable: leave it for next time
        } catch (Exception e) {
            info = new PageInfo();
        }

        boolean thumb = false;
        if (!yt && !info.image.isEmpty()) thumb = saveThumb(c, id, info.image);

        boolean learnedAlready = false;
        synchronized (LOCK) {
            try {
                JSONObject all = readLocked(c);
                JSONObject e = all.optJSONObject(id);
                if (e == null) e = new JSONObject();
                learnedAlready = e.optBoolean("l");
                e.put("a", System.currentTimeMillis());
                if (!info.title.isEmpty()) e.put("t", info.title);
                if (!info.desc.isEmpty()) e.put("d", info.desc);
                if (thumb) e.put("i", 1);
                all.put(id, e);
                writeLocked(c, all);
            } catch (JSONException ex) {
                // ignore
            }
        }

        // You picked this video's folder by hand before its title was known: learn from the title now.
        if (!info.title.isEmpty() && !learnedAlready) {
            try {
                JSONArray items = new JSONObject(Store.readRaw(c)).optJSONArray("items");
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null || !id.equals(safe(it.optString("id")))) continue;
                    String fid = it.optString("folderId");
                    if (it.optBoolean("manual") && !fid.isEmpty() && !"other".equals(fid)) {
                        Store.learn(c, info.title + " " + info.desc, url, fid, 1);
                        markLearned(c, id);
                    }
                    break;
                }
            } catch (JSONException ex) {
                // ignore
            }
        }

        if (once != null) once.onPreview(id, info.title, info.desc);
        Listener app = appListener;
        if (app != null) app.onPreview(id, info.title, info.desc);
    }

    private static String host(String url) {
        try {
            String x = new URL(url).getHost();
            if (x == null) return "";
            x = x.toLowerCase(Locale.ROOT);
            return x.startsWith("www.") ? x.substring(4) : x;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isYouTube(String url) {
        String h = host(url);
        return h.equals("youtube.com") || h.endsWith(".youtube.com") || h.equals("youtu.be");
    }

    private static PageInfo load(String url) throws IOException {
        String h = host(url);
        try {
            if (isYouTube(url)) {
                return oembed(get("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8"), 200000));
            }
            if (h.equals("tiktok.com") || h.endsWith(".tiktok.com")) {
                PageInfo p = oembed(get("https://www.tiktok.com/oembed?url=" + URLEncoder.encode(url, "UTF-8"), 200000));
                if (!p.title.isEmpty()) return p;
            }
        } catch (JSONException e) {
            // fall through to reading the page itself
        }
        String html = get(url, 600000);
        return html == null ? new PageInfo() : PageInfo.parse(html);
    }

    private static PageInfo oembed(String json) throws JSONException {
        PageInfo p = new PageInfo();
        if (json == null) return p;
        JSONObject o = new JSONObject(json);
        p.title = PageInfo.cleanTitle(o.optString("title"));
        String author = o.optString("author_name").trim();
        if (p.title.isEmpty() && !author.isEmpty()) p.title = "Video by " + author;
        p.desc = author;
        String img = o.optString("thumbnail_url");
        if (img.startsWith("https://")) p.image = img;
        return p;
    }

    private static String userAgent() {
        return "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; " + Build.MODEL
                + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
    }

    /** Text of a page, or null when the site answers with an error. Throws when there is no connection. */
    private static String get(String url, int maxBytes) throws IOException {
        byte[] b = getBytes(url, maxBytes, "text/html,application/json;q=0.9,*/*;q=0.5");
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] getBytes(String url, int maxBytes, String accept) throws IOException {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            if (current.regionMatches(true, 0, "http://", 0, 7)) current = "https://" + current.substring(7);
            if (!current.regionMatches(true, 0, "https://", 0, 8)) return null;
            HttpURLConnection con = (HttpURLConnection) new URL(current).openConnection();
            try {
                con.setInstanceFollowRedirects(false);
                con.setConnectTimeout(7000);
                con.setReadTimeout(9000);
                con.setRequestProperty("User-Agent", userAgent());
                con.setRequestProperty("Accept", accept);
                con.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                int code = con.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = con.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) return null;
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                if (code != 200) return null;
                InputStream in = con.getInputStream();
                try {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while (bo.size() < maxBytes && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    return bo.toByteArray();
                } finally {
                    in.close();
                }
            } finally {
                con.disconnect();
            }
        }
        return null;
    }

    /** Downloads the preview picture, shrinks it and keeps it on the phone (the web copies expire). */
    private static boolean saveThumb(Context c, String id, String imageUrl) {
        try {
            byte[] raw = getBytes(imageUrl, 4 * 1024 * 1024, "image/*,*/*;q=0.5");
            if (raw == null || raw.length < 100) return false;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 480) sample *= 2;
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
            if (bmp == null) return false;
            if (bmp.getWidth() > 520) {
                int h = Math.max(1, Math.round(bmp.getHeight() * (480f / bmp.getWidth())));
                bmp = Bitmap.createScaledBitmap(bmp, 480, h, true);
            }
            File dst = new File(thumbsDir(c), id + ".jpg");
            File tmp = new File(thumbsDir(c), id + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
            } finally {
                out.close();
            }
            if (!tmp.renameTo(dst)) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                if (!tmp.renameTo(dst)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        } catch (OutOfMemoryError e) {
            return false;
        }
    }
}
