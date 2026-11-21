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
}
