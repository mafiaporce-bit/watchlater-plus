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
                    String link = it.optString("url");
                    if (id.isEmpty()) continue;
                    boolean tried = e != null && e.has("a");
                    boolean retry = tried && e.optInt("m", 1) < METHOD && !isYouTube(link) && (!e.has("t") || !e.has("i"));
                    if (tried && !retry) continue;
                    fetch(c, id, link, it.optString("title"), null);
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

    /** Bump when the way of fetching gets better, so videos that got nothing before are tried again. */
    private static final int METHOD = 4;

    private static final String HTML = "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.5";
    private static final String UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    private static void run(Context c, String id, String url, String knownTitle, Listener once) {
        PageInfo info;
        StringBuilder log = new StringBuilder();
        boolean yt = isYouTube(url);
        try {
            // YouTube already shares its title, and its thumbnail needs no fetching
            if (yt && !knownTitle.trim().isEmpty()) {
                info = new PageInfo();
                log.append("YouTube: title came with the share, picture comes from YouTube");
            } else {
                info = load(url, log);
            }
        } catch (IOException e) {
            // offline or unreachable: leave it for next time, but say why
            note(c, id, "No connection when it tried (" + e.getClass().getSimpleName() + ")");
            return;
        } catch (Exception e) {
            info = new PageInfo();
            log.append("; problem: ").append(e.getClass().getSimpleName());
        }

        boolean thumb = false;
        if (!yt && !info.image.isEmpty()) {
            thumb = saveThumb(c, id, info.image);
            log.append(thumb ? "; picture saved" : "; picture could not be downloaded");
        }
        if (!yt && !thumb && !info.alt.isEmpty()) {
            // TikTok: the picture link from its info did not work, take the cover from its player page
            try {
                Resp player = http(info.alt, 900000, true, HTML);
                String cover = TikTok.fromHtml(player.text(), TikTok.tail(info.alt)).image;
                if (!cover.isEmpty() && !cover.equals(info.image)) {
                    thumb = saveThumb(c, id, cover);
                    log.append(thumb ? "; cover saved" : "; cover could not be downloaded");
                } else {
                    log.append("; no cover in player");
                }
            } catch (Exception e) {
                log.append("; player not reachable");
            }
        }

        boolean learnedAlready = false;
        synchronized (LOCK) {
            try {
                JSONObject all = readLocked(c);
                JSONObject e = all.optJSONObject(id);
                if (e == null) e = new JSONObject();
                learnedAlready = e.optBoolean("l");
                e.put("a", System.currentTimeMillis());
                e.put("m", METHOD);
                e.put("g", log.length() > 400 ? log.substring(0, 400) : log.toString());
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

    /** Writes only the "what happened" line of an entry (shown under the video's menu in the app). */
    private static void note(Context c, String id, String text) {
        synchronized (LOCK) {
            try {
                JSONObject all = readLocked(c);
                JSONObject e = all.optJSONObject(id);
                if (e == null) e = new JSONObject();
                e.put("g", text);
                all.put(id, e);
                writeLocked(c, all);
            } catch (JSONException ex) {
                // ignore
            }
        }
        Listener app = appListener;
        if (app != null) app.onPreview(id, "", "");
    }

    /** A picture that came with the share itself (some apps attach one). */
    public static void thumbFromShare(final Context context, final String rawId, final byte[] raw) {
        final Context c = context.getApplicationContext();
        final String id = safe(rawId);
        if (id.isEmpty() || raw == null) return;
        POOL.execute(() -> {
            if (!saveThumbBytes(c, id, raw)) return;
            synchronized (LOCK) {
                try {
                    JSONObject all = readLocked(c);
                    JSONObject e = all.optJSONObject(id);
                    if (e == null) e = new JSONObject();
                    e.put("i", 1);
                    all.put(id, e);
                    writeLocked(c, all);
                } catch (JSONException ex) {
                    // ignore
                }
            }
            Listener app = appListener;
            if (app != null) app.onPreview(id, "", "");
        });
    }

    /* ---------- where to look, per site ---------- */

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

    private static boolean on(String host, String site) {
        return host.equals(site) || host.endsWith("." + site);
    }

    private static boolean isYouTube(String url) {
        String h = host(url);
        return on(h, "youtube.com") || h.equals("youtu.be");
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String found(PageInfo p) {
        return (p.title.isEmpty() ? " no title" : " title") + (p.image.isEmpty() ? ", no picture" : ", picture");
    }

    private static void fill(PageInfo into, PageInfo from) {
        if (into.title.isEmpty()) into.title = from.title;
        if (into.desc.isEmpty()) into.desc = from.desc;
        if (into.image.isEmpty()) into.image = from.image;
    }

    private static boolean isLoginWall(String finalUrl) {
        String u = finalUrl == null ? "" : finalUrl.toLowerCase(Locale.ROOT);
        return u.contains("/login") || u.contains("login.php") || u.contains("/checkpoint") || u.contains("/accounts/login");
    }

    private static PageInfo load(String url, StringBuilder log) throws IOException {
        String h = host(url);
        if (isYouTube(url)) {
            Resp r = http("https://www.youtube.com/oembed?format=json&url=" + enc(url), 200000, false, HTML);
            log.append("YouTube info ").append(r.code);
            return oembed(r.text());
        }
        if (on(h, "tiktok.com")) return tiktok(url, log);
        if (on(h, "facebook.com") || on(h, "fb.watch") || on(h, "fb.com") || on(h, "fb.gg")) return facebook(url, log);
        if (on(h, "instagram.com")) return instagram(url, log);
        Resp r = http(url, 600000, false, HTML);
        log.append("page ").append(r.code);
        PageInfo p = r.body == null ? new PageInfo() : PageInfo.parse(r.text());
        log.append(found(p));
        return p;
    }

    private static String noQuery(String url) {
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);
        int h = url.indexOf('#');
        return h >= 0 ? url.substring(0, h) : url;
    }

    /**
     * TikTok share links are short (vt.tiktok.com/...). Asked about the short link itself, TikTok's
     * info service can describe the creator instead of the video ("...'s Creator Profile"). So:
     * follow the link, find the video number, ask about the real video, and if the picture or
     * caption is still missing read TikTok's player page, then the video page itself.
     */
    private static PageInfo tiktok(String url, StringBuilder log) throws IOException {
        PageInfo best = new PageInfo();
        String vid = TikTok.id(url), user = TikTok.user(url);
        String canon = vid.isEmpty() ? "" : noQuery(url);
        Resp page = null;
        if (vid.isEmpty()) {
            page = http(url, 900000, true, HTML);
            log.append("TikTok link ").append(page.code);
            for (String h : page.hops) {
                if (vid.isEmpty()) {
                    vid = TikTok.id(h);
                    if (!vid.isEmpty()) canon = noQuery(h);
                }
                if (user.isEmpty()) user = TikTok.user(h);
            }
            if (vid.isEmpty() && page.body != null) {
                String[] named = TikTok.pageVideo(page.text());
                if (named != null) {
                    vid = named[1];
                    if (user.isEmpty()) user = named[0];
                }
            }
            log.append(vid.isEmpty() ? " (no video number)" : " (video " + vid + ")");
        }

        if (!vid.isEmpty()) {
            String real = canon.matches("(?i)https://[^/]*tiktok\\.com/@[\\w.\\-]+/(?:video|photo)/\\d+") ? canon
                    : "https://www.tiktok.com/@" + (user.isEmpty() ? "tiktok" : user) + "/video/" + vid;
            Resp r = http("https://www.tiktok.com/oembed?url=" + enc(real), 200000, false, HTML);
            PageInfo x = oembed(r.text());
            if (x.profile) x = new PageInfo();   // an answer about the creator, not this video
            log.append("; info ").append(r.code).append(found(x));
            fill(best, x);
            best.alt = "https://www.tiktok.com/embed/v2/" + vid;
            if (best.image.isEmpty() || best.title.isEmpty()) {
                Resp e = http(best.alt, 900000, true, HTML);
                PageInfo y = TikTok.fromHtml(e.text(), vid);
                log.append("; player ").append(e.code).append(found(y));
                fill(best, y);
            }
            if (best.image.isEmpty() || best.title.isEmpty()) {
                boolean same = page != null && page.body != null && vid.equals(TikTok.id(page.finalUrl));
                Resp v = same ? page : http(real, 900000, true, HTML);
                PageInfo y = TikTok.fromHtml(v.text(), vid);
                log.append("; video page ").append(v.code).append(found(y));
                fill(best, y);
            }
            if (!best.title.isEmpty() || !best.image.isEmpty()) return best;
        }

        // not a video (a creator's page, or something new): describe the link as it is
        Resp r = http("https://www.tiktok.com/oembed?url=" + enc(url), 200000, false, HTML);
        PageInfo x = oembed(r.text());
        log.append("; link info ").append(r.code).append(found(x));
        fill(best, x);
        if (best.image.isEmpty() && page != null && page.body != null) {
            PageInfo y = PageInfo.parse(page.text());
            fill(best, y);
            log.append("; from page").append(found(y));
        }
        return best;
    }

    /**
     * Facebook shows a log-in page to anything that looks like a phone without an account.
     * So: 1) the normal public web page, 2) Facebook's own video embed (made for other sites),
     * 3) the phone page as a last try.
     */
    private static PageInfo facebook(String url, StringBuilder log) throws IOException {
        String www = url.replaceFirst("(?i)^https?://(m|mbasic|web|touch|mobile)\\.facebook\\.com", "https://www.facebook.com");
        PageInfo best = new PageInfo();

        Resp r = http(www, 900000, true, HTML);
        boolean wall = isLoginWall(r.finalUrl);
        log.append("Facebook page ").append(r.code).append(wall ? " (asked to log in)" : "");
        if (r.body != null && !wall) {
            PageInfo x = PageInfo.parseFacebook(r.text());
            fill(best, x);
            log.append(found(x));
        }

        if (best.title.isEmpty() || best.image.isEmpty()) {
            String vid = PageInfo.facebookId(url, r.finalUrl);
            String href = !vid.isEmpty() ? "https://www.facebook.com/video.php?v=" + vid : (wall ? www : r.finalUrl);
            Resp e = http("https://www.facebook.com/plugins/video.php?show_text=true&width=500&href=" + enc(href),
                    700000, true, HTML);
            log.append("; embed ").append(e.code).append(vid.isEmpty() ? " (no video number)" : "");
            if (e.body != null) {
                PageInfo x = PageInfo.parseEmbed(e.text());
                fill(best, x);
                log.append(found(x));
            }
        }

        if (best.title.isEmpty() && best.image.isEmpty()) {
            Resp m = http(url, 700000, false, HTML);
            boolean wall2 = isLoginWall(m.finalUrl);
            log.append("; phone page ").append(m.code).append(wall2 ? " (asked to log in)" : "");
            if (m.body != null && !wall2) {
                PageInfo x = PageInfo.parseFacebook(m.text());
                fill(best, x);
                log.append(found(x));
            }
        }
        return best;
    }

    private static PageInfo instagram(String url, StringBuilder log) throws IOException {
        PageInfo best = new PageInfo();
        Resp r = http(url, 900000, true, HTML);
        boolean wall = isLoginWall(r.finalUrl);
        log.append("Instagram page ").append(r.code).append(wall ? " (asked to log in)" : "");
        if (r.body != null && !wall) {
            PageInfo x = PageInfo.parseFacebook(r.text());
            fill(best, x);
            log.append(found(x));
        }
        if (best.title.isEmpty() || best.image.isEmpty()) {
            String embed = PageInfo.instagramEmbed(wall ? url : r.finalUrl);
            if (embed.isEmpty()) embed = PageInfo.instagramEmbed(url);
            if (!embed.isEmpty()) {
                Resp e = http(embed, 700000, false, HTML);
                log.append("; embed ").append(e.code);
                if (e.body != null) {
                    PageInfo x = PageInfo.parseEmbed(e.text());
                    fill(best, x);
                    log.append(found(x));
                }
            }
        }
        return best;
    }

    private static PageInfo oembed(String json) {
        PageInfo p = new PageInfo();
        if (json == null) return p;
        try {
            JSONObject o = new JSONObject(json);
            p.title = PageInfo.cleanTitle(o.optString("title"));
            String author = o.optString("author_name").trim();
            if (p.title.isEmpty() && !author.isEmpty()) p.title = "Video by " + author;
            p.desc = author;
            String img = o.optString("thumbnail_url");
            if (img.startsWith("https://")) p.image = img;
            // TikTok answers about the creator (not the video) for links it does not see as a video
            p.profile = "profile".equals(o.optString("embed_type")) || o.optString("title").endsWith("'s Creator Profile");
        } catch (JSONException e) {
            // not the answer we hoped for
        }
        return p;
    }

    /* ---------- the web request ---------- */

    private static final class Resp {
        int code = 0;
        String finalUrl = "";
        byte[] body;
        final java.util.List<String> hops = new java.util.ArrayList<String>();

        String text() {
            return body == null ? null : new String(body, StandardCharsets.UTF_8);
        }
    }

    private static String phoneAgent() {
        return "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; " + Build.MODEL
                + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
    }

    private static String site(String url) {
        String h = host(url);
        String[] p = h.split("\\.");
        return p.length <= 2 ? h : p[p.length - 2] + "." + p[p.length - 1];
    }

    /**
     * One GET that follows redirects itself (keeping the cookies each step hands out, which
     * Facebook needs). body stays null unless the final answer is a normal 200 page.
     * Throws when there is no connection at all.
     */
    private static Resp http(String url, int maxBytes, boolean desktop, String accept) throws IOException {
        return http(url, maxBytes, desktop, accept, null);
    }

    private static Resp http(String url, int maxBytes, boolean desktop, String accept, String referer) throws IOException {
        Resp r = new Resp();
        String current = url;
        java.util.Map<String, String> cookies = new java.util.LinkedHashMap<String, String>();
        boolean page = accept.startsWith("text/html");
        for (int hop = 0; hop < 8; hop++) {
            if (current.regionMatches(true, 0, "http://", 0, 7)) current = "https://" + current.substring(7);
            r.finalUrl = current;
            r.hops.add(current);
            if (!current.regionMatches(true, 0, "https://", 0, 8)) {
                r.code = -1;
                return r;
            }
            HttpURLConnection con = (HttpURLConnection) new URL(current).openConnection();
            try {
                con.setInstanceFollowRedirects(false);
                con.setConnectTimeout(7000);
                con.setReadTimeout(9000);
                con.setRequestProperty("User-Agent", desktop ? UA_DESKTOP : phoneAgent());
                con.setRequestProperty("Accept", accept);
                con.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                if (referer != null) con.setRequestProperty("Referer", referer);
                if (page) {
                    con.setRequestProperty("Sec-Fetch-Dest", "document");
                    con.setRequestProperty("Sec-Fetch-Mode", "navigate");
                    con.setRequestProperty("Sec-Fetch-Site", "none");
                    con.setRequestProperty("Sec-Fetch-User", "?1");
                    con.setRequestProperty("Upgrade-Insecure-Requests", "1");
                }
                if (!cookies.isEmpty()) {
                    StringBuilder ck = new StringBuilder();
                    for (java.util.Map.Entry<String, String> e : cookies.entrySet()) {
                        if (ck.length() > 0) ck.append("; ");
                        ck.append(e.getKey()).append('=').append(e.getValue());
                    }
                    con.setRequestProperty("Cookie", ck.toString());
                }
                int code = con.getResponseCode();
                r.code = code;
                for (java.util.Map.Entry<String, java.util.List<String>> hd : con.getHeaderFields().entrySet()) {
                    if (hd.getKey() == null || !hd.getKey().equalsIgnoreCase("Set-Cookie")) continue;
                    for (String line : hd.getValue()) {
                        int semi = line.indexOf(';');
                        String pair = semi > 0 ? line.substring(0, semi) : line;
                        int eq = pair.indexOf('=');
                        if (eq > 0) cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                    }
                }
                if (code >= 300 && code < 400) {
                    String loc = con.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) return r;
                    String next = new URL(new URL(current), loc).toString();
                    if (!site(next).equals(site(current))) cookies.clear(); // never carry cookies to another site
                    current = next;
                    continue;
                }
                if (code != 200) return r;
                InputStream in = con.getInputStream();
                try {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while (bo.size() < maxBytes && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    r.body = bo.toByteArray();
                } finally {
                    in.close();
                }
                return r;
            } finally {
                con.disconnect();
            }
        }
        r.code = -2; // went round in circles
        return r;
    }

    /** Downloads the preview picture, shrinks it and keeps it on the phone (the web copies expire). */
    private static boolean saveThumb(Context c, String id, String imageUrl) {
        try {
            // ask for plain picture types: some sites send AVIF/HEIC otherwise, which older phones cannot open
            String accept = "image/webp,image/jpeg,image/png,image/*;q=0.8,*/*;q=0.5";
            String h = host(imageUrl);
            String ref = h.contains("tiktok") ? "https://www.tiktok.com/" : null;
            Resp r = http(imageUrl, 4 * 1024 * 1024, false, accept, ref);
            if (r.body != null && saveThumbBytes(c, id, r.body)) return true;
            Resp r2 = http(imageUrl, 4 * 1024 * 1024, true, accept, ref);
            return r2.body != null && saveThumbBytes(c, id, r2.body);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean saveThumbBytes(Context c, String id, byte[] raw) {
        try {
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
