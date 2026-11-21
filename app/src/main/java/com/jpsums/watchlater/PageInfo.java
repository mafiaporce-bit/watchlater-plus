package com.jpsums.watchlater;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the title, description and preview picture a web page advertises about itself
 * (the same "link preview" tags chat apps use). Plain Java so it can be tested on its own.
 */
public final class PageInfo {

    public String title = "";
    public String desc = "";
    public String image = "";
    /** A second place to look for the picture (TikTok's player page), tried if the first one fails. */
    public String alt = "";
    /** True when a site's info service described a creator's profile rather than a video. */
    public boolean profile = false;

    private static final Pattern META = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile(
            "([a-zA-Z:_-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STATS = Pattern.compile(
            "^[\\d.,]+\\s*[kmb]?\\s+(views?|reactions?|likes?|comments?|shares?|plays?)\\b.*$");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");

    public static PageInfo parse(String html) {
        PageInfo p = new PageInfo();
        if (html == null) return p;
        Map<String, String> meta = new HashMap<String, String>();
        Matcher m = META.matcher(html);
        while (m.find()) {
            String key = null, content = null;
            Matcher a = ATTR.matcher(m.group());
            while (a.find()) {
                String n = a.group(1).toLowerCase(Locale.ROOT);
                String v = a.group(3) != null ? a.group(3) : a.group(4) != null ? a.group(4) : a.group(5);
                if (n.equals("property") || n.equals("name")) key = v.toLowerCase(Locale.ROOT);
                else if (n.equals("content")) content = v;
            }
            if (key != null && content != null && !content.trim().isEmpty() && !meta.containsKey(key)) {
                meta.put(key, decode(content).trim());
            }
        }
        String pageTitle = "";
        Matcher t = TITLE.matcher(html);
        if (t.find()) pageTitle = decode(t.group(1));

        p.title = cleanTitle(first(meta.get("og:title"), meta.get("twitter:title"), pageTitle));
        p.desc = squash(first(meta.get("og:description"), meta.get("twitter:description"), meta.get("description")));
        if (p.desc.length() > 300) p.desc = p.desc.substring(0, 300);
        p.image = first(meta.get("og:image:secure_url"), meta.get("og:image"), meta.get("twitter:image"));
        if (!p.image.startsWith("https://") && !p.image.startsWith("http://")) p.image = "";
        if (p.title.isEmpty()) {
            // a login wall or an error page: its picture and text say nothing about the video
            p.image = "";
            p.desc = "";
        }
        return p;
    }

    private static String first(String... c) {
        for (String s : c) if (s != null && !s.trim().isEmpty()) return s.trim();
        return "";
    }

    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** Makes a page title readable: drops view counts, the site name, and login-wall text. */
    public static String cleanTitle(String raw) {
        String s = squash(raw);
        if (s.isEmpty()) return "";
        s = s.replaceAll("(?i)\\s+[-|•·]\\s+(YouTube|Facebook|TikTok|Instagram)\\s*$", "");
        List<String> keep = new ArrayList<String>();
        for (String part : s.split("\\s+\\|\\s+")) {
            String low = part.toLowerCase(Locale.ROOT).trim();
            if (low.isEmpty()) continue;
            if (STATS.matcher(low).matches()) continue;
            if (low.equals("facebook") || low.equals("tiktok") || low.equals("instagram") || low.equals("youtube")
                    || low.equals("reels") || low.equals("video") || low.equals("watch") || low.equals("facebook watch")
                    || low.equals("tiktok - make your day")) continue;
            keep.add(part.trim());
        }
        StringBuilder b = new StringBuilder();
        for (String k : keep) {
            if (b.length() > 0) b.append(" · ");
            b.append(k);
        }
        s = b.toString();
        String low = s.toLowerCase(Locale.ROOT);
        boolean wall = low.contains("log in") || low.contains("log into") || low.contains("sign up")
                || low.contains("login •") || low.equals("login") || low.equals("discover popular videos");
        boolean shortErr = low.length() < 45 && (low.contains("page not found") || low.contains("not available")
                || low.contains("isn't available") || low.contains("error") || low.contains("just a moment")
                || low.contains("access denied") || low.contains("security check") || low.contains("redirecting"));
        if (low.isEmpty() || wall || shortErr) return "";
        if (s.length() > 160) s = s.substring(0, 157).trim() + "...";
        return s;
    }

    /** Turns &amp;amp; &amp;quot; &amp;#39; and friends back into normal characters. */
    public static String decode(String s) {
        if (s == null || s.indexOf('&') < 0) return s == null ? "" : s;
        Matcher m = ENTITY.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), rep = null;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) rep = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) rep = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else if (e.equals("amp")) rep = "&";
                else if (e.equals("quot")) rep = "\"";
                else if (e.equals("apos")) rep = "'";
                else if (e.equals("lt")) rep = "<";
                else if (e.equals("gt")) rep = ">";
                else if (e.equals("nbsp")) rep = " ";
            } catch (IllegalArgumentException ex) {
                rep = null;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rep == null ? m.group() : rep));
        }
        m.appendTail(out);
        return out.toString();
    }

    /* ---------- Facebook / Instagram: their pages hide the preview in other places ---------- */

    private static final Pattern J_THUMB = Pattern.compile(
            "\"(?:preferred_thumbnail|thumbnailImage|previewImage|thumbnail_image|display_resources)\"\\s*:\\s*[\\[{]+"
            + "(?:\\s*\"image\"\\s*:\\s*\\{)?\\s*\"(?:uri|src)\"\\s*:\\s*\"(https:[^\"]+)\"");
    private static final Pattern J_THUMB2 = Pattern.compile(
            "\"(?:first_frame_thumbnail|thumbnail_url|thumbnailUrl|thumbnail_src|display_url)\"\\s*:\\s*\"(https:[^\"]+)\"");
    private static final Pattern J_TEXT = Pattern.compile(
            "\"(?:message|savable_description|title)\"\\s*:\\s*\\{\\s*\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){3,800})\"");
    private static final Pattern POSTER = Pattern.compile("<video\\b[^>]*\\sposter\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BG = Pattern.compile("background-image\\s*:\\s*url\\(\\s*(?:&quot;|[\"'])?([^)\"']+?)(?:&quot;|[\"'])?\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SMALL = Pattern.compile("[/_.]((?:p|s|c)?\\d{2,3})x(\\d{2,3})[/_.]");
    private static final Pattern CAPTION = Pattern.compile(
            "class=\"[^\"]*(?:Caption|userContent)[^\"]*\"[^>]*>(.*?)(?:</div>|<div\\b)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FB_ID = Pattern.compile(
            "(?:/reel/|/reels/|/videos/(?:[^/?#]+/)?|[?&]v=|video_id=|story_fbid=)(\\d{6,})");
    private static final Pattern IG_CODE = Pattern.compile("instagram\\.com/(?:[^/]+/)?(reel|reels|p|tv)/([A-Za-z0-9_-]{5,})");

    /** True for a picture address on Facebook's / Instagram's media servers (not their logo or icons). */
    static boolean isMedia(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        String u = url.toLowerCase(Locale.ROOT);
        if (!(u.contains("fbcdn.net") || u.contains("cdninstagram.com"))) return false;
        return !u.contains("//static.") && !u.contains("rsrc.php") && !u.contains("/emoji");
    }

    /** JSON text (as found inside a page's scripts) back to normal text. */
    public static String jsonUnescape(String s) {
        if (s == null || s.indexOf('\\') < 0) return s == null ? "" : s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                b.append(c);
                continue;
            }
            char n = s.charAt(++i);
            if (n == 'u' && i + 4 < s.length()) {
                try {
                    b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                } catch (NumberFormatException e) {
                    b.append('u');
                }
            } else if (n == 'n' || n == 'r' || n == 't') {
                b.append(' ');
            } else {
                b.append(n);
            }
        }
        return b.toString();
    }

    private static String jsonThumb(String html) {
        for (Pattern pat : new Pattern[]{J_THUMB, J_THUMB2}) {
            Matcher m = pat.matcher(html);
            while (m.find()) {
                String u = decode(jsonUnescape(m.group(1)));
                if (isMedia(u)) return u;
            }
        }
        return "";
    }

    /** A Facebook video page: normal preview tags first, then what its scripts carry. */
    public static PageInfo parseFacebook(String html) {
        PageInfo p = new PageInfo();
        if (html == null) return p;
        // read the tags ourselves: parse() throws the picture away when the title is missing
        PageInfo og = parse(html);
        p.title = og.title;
        p.desc = og.desc;
        if (isMedia(og.image)) p.image = og.image;
        if (p.image.isEmpty()) {
            Matcher m = META.matcher(html);
            while (m.find() && p.image.isEmpty()) {
                String tag = m.group();
                if (!tag.contains("og:image") && !tag.contains("twitter:image")) continue;
                Matcher a = ATTR.matcher(tag);
                while (a.find()) {
                    if (!a.group(1).equalsIgnoreCase("content")) continue;
                    String v = decode(a.group(3) != null ? a.group(3) : a.group(4) != null ? a.group(4) : a.group(5));
                    if (isMedia(v)) p.image = v;
                }
            }
        }
        if (p.image.isEmpty()) p.image = jsonThumb(html);
        if (p.title.isEmpty()) {
            Matcher t = J_TEXT.matcher(html);
            if (t.find()) p.title = cleanTitle(jsonUnescape(t.group(1)));
        }
        return p;
    }

    /** An embed page (Facebook's video plugin, Instagram's /embed): the poster picture and the caption. */
    public static PageInfo parseEmbed(String html) {
        PageInfo p = new PageInfo();
        if (html == null) return p;
        Matcher v = POSTER.matcher(html);
        while (v.find() && p.image.isEmpty()) {
            String u = decode(v.group(1));
            if (isMedia(u)) p.image = u;
        }
        if (p.image.isEmpty()) p.image = jsonThumb(html);
        if (p.image.isEmpty()) {
            Matcher im = IMG.matcher(html);
            while (im.find() && p.image.isEmpty()) {
                String tag = im.group(), src = "", cls = "";
                int w = -1;
                Matcher a = ATTR.matcher(tag);
                while (a.find()) {
                    String n = a.group(1).toLowerCase(Locale.ROOT);
                    String val = a.group(3) != null ? a.group(3) : a.group(4) != null ? a.group(4) : a.group(5);
                    if (n.equals("src")) src = decode(val);
                    else if (n.equals("class")) cls = val.toLowerCase(Locale.ROOT);
                    else if (n.equals("width") || n.equals("height")) {
                        try {
                            w = Math.max(w, Integer.parseInt(val.trim()));
                        } catch (NumberFormatException e) {
                            // not a plain number
                        }
                    }
                }
                if (!isMedia(src) || cls.contains("profile") || cls.contains("avatar")) continue;
                if (w >= 0 && w < 100) continue;          // a small icon or profile picture
                Matcher sm = SMALL.matcher(src);
                if (sm.find()) {
                    try {
                        if (Integer.parseInt(sm.group(2)) < 200) continue;
                    } catch (NumberFormatException e) {
                        // keep it
                    }
                }
                p.image = src;
            }
        }
        if (p.image.isEmpty()) {
            Matcher b = BG.matcher(html);
            while (b.find() && p.image.isEmpty()) {
                String u = decode(b.group(1).trim());
                if (isMedia(u)) p.image = u;
            }
        }
        Matcher c = CAPTION.matcher(html);
        if (c.find()) {
            String text = decode(c.group(1).replaceAll("(?is)<a\\b[^>]*class=\"[^\"]*(?:Username|CaptionUsername)[^\"]*\"[^>]*>.*?</a>", " ")
                    .replaceAll("(?s)<[^>]+>", " "));
            p.title = cleanTitle(text);
        }
        if (p.title.isEmpty()) {
            Matcher t = J_TEXT.matcher(html);
            if (t.find()) p.title = cleanTitle(jsonUnescape(t.group(1)));
        }
        return p;
    }

    /** The video number inside a Facebook link (also from a "log in, then continue to..." address). */
    public static String facebookId(String... urls) {
        for (String u : urls) {
            if (u == null) continue;
            String d = u.replace("%2F", "/").replace("%2f", "/").replace("%3F", "?").replace("%3f", "?")
                    .replace("%3D", "=").replace("%3d", "=").replace("%26", "&");
            Matcher m = FB_ID.matcher(d);
            if (m.find()) return m.group(1);
        }
        return "";
    }

    /** Instagram's embed page for a post or reel link, or "" when the link is something else. */
    public static String instagramEmbed(String url) {
        if (url == null) return "";
        Matcher m = IG_CODE.matcher(url);
        if (!m.find()) return "";
        String kind = m.group(1).startsWith("reel") ? "reel" : m.group(1);
        return "https://www.instagram.com/" + kind + "/" + m.group(2) + "/embed/captioned/";
    }
}
