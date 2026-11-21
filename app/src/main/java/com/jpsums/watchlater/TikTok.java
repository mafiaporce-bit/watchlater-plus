package com.jpsums.watchlater;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading TikTok links and pages: the video number inside a link, and the cover picture and
 * caption inside TikTok's pages. Plain Java on purpose (no Android classes) so it can be tested
 * on its own. The web version's helper (netlify/shared/preview-core.mjs) does the same.
 */
public final class TikTok {

    private TikTok() { }

    private static final Pattern ID = Pattern.compile(
            "/(?:video|photo|v)/(\\d{8,25})|[?&](?:item_id|share_item_id|aweme_id)=(\\d{8,25})");
    private static final Pattern USER = Pattern.compile("/@([\\w.\\-]+)");
    private static final Pattern PAGE = Pattern.compile(
            "tiktok\\.com(?:\\\\?/|\\\\u002F)@([\\w.\\-]+)(?:\\\\?/|\\\\u002F)(?:video|photo)(?:\\\\?/|\\\\u002F)(\\d{8,25})");
    /** Where TikTok's pages keep the cover picture, best first. */
    private static final Pattern[] COVERS = {
            Pattern.compile("\"(?:originCover|coversOrigin)\"\\s*:\\s*\\[?\\s*\"(https?:[^\"]+)\""),
            Pattern.compile("\"(?:cover|covers)\"\\s*:\\s*\\[?\\s*\"(https?:[^\"]+)\""),
            Pattern.compile("\"(?:thumbnailUrl|thumbnail_url|poster)\"\\s*:\\s*\\[?\\s*\"(https?:[^\"]+)\""),
            Pattern.compile("\"(?:imageURL|displayImage|imagePostCover|shareCover)\"\\s*:\\s*\\{[^{}]{0,400}?\"urlList\"\\s*:\\s*\\[\\s*\"(https?:[^\"]+)\""),
            Pattern.compile("\"shareCover\"\\s*:\\s*\\[\\s*(?:\"\"\\s*,\\s*)?\"(https?:[^\"]+)\""),
            Pattern.compile("\"(?:dynamicCover|coversDynamic)\"\\s*:\\s*\\[?\\s*\"(https?:[^\"]+)\""),
    };
    private static final Pattern[] DESC = {
            Pattern.compile("\"desc\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){3,400})\""),
            Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){3,400})\""),
    };
    private static final Pattern AUTHOR = Pattern.compile("\"(?:nickname|authorName)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){1,80})\"");
    private static final Pattern TAIL = Pattern.compile("(\\d{8,25})/?$");

    /** The video number in a TikTok link, or "". */
    public static String id(String url) {
        if (url == null) return "";
        Matcher m = ID.matcher(url);
        if (!m.find()) return "";
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    /** The creator's name in a TikTok link (without the @), or "". */
    public static String user(String url) {
        if (url == null) return "";
        Matcher m = USER.matcher(url);
        return m.find() ? m.group(1) : "";
    }

    /** The number a link ends with (TikTok's player page: .../embed/v2/123...). */
    public static String tail(String url) {
        if (url == null) return "";
        Matcher m = TAIL.matcher(url);
        return m.find() ? m.group(1) : "";
    }

    /** {creator, video number} named inside a TikTok page, or null. */
    public static String[] pageVideo(String html) {
        if (html == null) return null;
        Matcher m = PAGE.matcher(html);
        return m.find() ? new String[]{m.group(1), m.group(2)} : null;
    }

    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** Cover picture and caption out of a TikTok page (the video page or the player page). */
    public static PageInfo fromHtml(String html, String vid) {
        PageInfo p = new PageInfo();
        if (html == null || html.isEmpty()) return p;
        // start reading at this video's own entry when the page lists more than one
        int at = vid == null || vid.isEmpty() ? -1 : html.indexOf("\"id\":\"" + vid + "\"");
        String own = at > 0 ? html.substring(at) : html;
        String[] parts = at > 0 ? new String[]{own, html} : new String[]{html};
        for (String part : parts) {
            for (Pattern re : COVERS) {
                Matcher m = re.matcher(part);
                if (!m.find()) continue;
                String u = PageInfo.jsonUnescape(m.group(1));
                if (u.startsWith("https://")) {
                    p.image = u;
                    break;
                }
            }
            if (!p.image.isEmpty()) break;
        }
        for (Pattern re : DESC) {
            Matcher d = re.matcher(own);
            if (d.find()) {
                p.title = PageInfo.cleanTitle(PageInfo.jsonUnescape(d.group(1)));
                if (!p.title.isEmpty()) break;
            }
        }
        Matcher a = AUTHOR.matcher(own);
        if (a.find()) p.desc = squash(PageInfo.jsonUnescape(a.group(1)));
        if (p.title.isEmpty() && !p.desc.isEmpty() && !p.image.isEmpty()) p.title = "Video by " + p.desc;
        PageInfo og = PageInfo.parse(html);
        if (!og.title.isEmpty() && !og.title.toLowerCase(java.util.Locale.ROOT).startsWith("tiktok")) {
            if (p.title.isEmpty()) p.title = og.title;
            if (p.desc.isEmpty()) p.desc = og.desc;
            if (p.image.isEmpty()) p.image = og.image;
        } else if (p.image.isEmpty() && !og.image.isEmpty()) {
            p.image = og.image;
        }
        return p;
    }
}
