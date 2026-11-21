package com.jpsums.watchlater;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Auto-sort: decides which folder a video belongs in from its words. Free and offline.
 * Three things count: the folder's own word list, the folder's name, and words it has
 * learned from the folders you picked by hand.
 * Plain Java on purpose (no Android classes) so it can be tested on its own.
 */
public final class Sorter {

    private Sorter() { }

    public static final class Folder {
        public final String id;
        public final String name;
        public final List<String> words;

        public Folder(String id, String name, List<String> words) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.words = words == null ? new ArrayList<String>() : words;
        }
    }

    /** id is "" when there is no idea at all; sure means "save without asking". */
    public static final class Pick {
        public final String id;
        public final boolean sure;

        Pick(String id, boolean sure) {
            this.id = id;
            this.sure = sure;
        }
    }

    private static final Set<String> STOP = new HashSet<String>(Arrays.asList((
            "the and for with this that you your how from what when why who are was were will can not but all any out "
            + "our his her him she they them their its has have had just now new one two get got see too very more most "
            + "into over here there then than about after before back best top full part official video videos reel reels "
            + "watch short shorts clip clips post story live views view likes like share shares comments comment follow "
            + "facebook youtube tiktok instagram twitter com www http https "
            + "ang mga nang para lang naman yung ito iyan kung hindi ako ikaw siya niya nila namin natin din rin pag may "
            + "wala ano sino saan kasi daw raw pala talaga sobra grabe").split(" ")));

    private static final Set<String> BIG_SITES = new HashSet<String>(Arrays.asList(
            "facebook.com", "fb.watch", "fb.com", "fb.gg", "youtube.com", "youtu.be", "tiktok.com",
            "instagram.com", "x.com", "twitter.com", "t.co"));

    /** Lower-case, everything that is not a letter or digit becomes one space, padded with spaces. */
    public static String norm(String s) {
        if (s == null) return " ";
        String t = s.toLowerCase(Locale.ROOT).replaceAll("https?://\\S+", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return " " + t + " ";
    }

    /** The meaningful words of a text, each once. */
    public static List<String> tokens(String s) {
        Set<String> out = new LinkedHashSet<String>();
        for (String w : norm(s).trim().split(" ")) {
            if (w.length() < 3 || w.length() > 30) continue;
            if (STOP.contains(w)) continue;
            if (w.matches("\\d+")) continue;
            out.add(w);
            if (out.size() >= 40) break;
        }
        return new ArrayList<String>(out);
    }

    /** "@nba.com" for a small site, "" for the big social apps (their name says nothing about the topic). */
    public static String hostToken(String url) {
        if (url == null) return "";
        String u = url.toLowerCase(Locale.ROOT);
        int a = u.indexOf("://");
        if (a < 0) return "";
        String h = u.substring(a + 3);
        int end = h.length();
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                end = i;
                break;
            }
        }
        h = h.substring(0, end);
        for (String p : new String[]{"www.", "m.", "web.", "mobile.", "vm.", "vt."}) {
            if (h.startsWith(p)) {
                h = h.substring(p.length());
                break;
            }
        }
        if (h.isEmpty() || h.indexOf('.') < 0) return "";
        for (String big : BIG_SITES) {
            if (h.equals(big) || h.endsWith("." + big)) return "";
        }
        return "@" + h;
    }

    private static boolean has(String text, String key) {
        String k = norm(key).trim();
        if (k.length() < 2) return false;
        if (text.contains(" " + k + " ")) return true;
        if (k.indexOf(' ') >= 0) return false;
        // simple plurals: "recipe" also matches "recipes", "dish" matches "dishes"
        return text.contains(" " + k + "s ") || text.contains(" " + k + "es ");
    }

    public static Pick suggest(String text, String url, List<Folder> folders,
                               Map<String, Map<String, Integer>> learned) {
        String t = norm(text);
        List<String> toks = tokens(text);
        String ht = hostToken(url);
        if (!ht.isEmpty()) toks.add(ht);
        if (t.trim().isEmpty() && ht.isEmpty()) return new Pick("", false);

        String bestId = "";
        double best = 0, second = 0;
        for (Folder f : folders) {
            if (f == null || f.id == null || "other".equals(f.id)) continue;
            double score = 0;
            Set<String> seen = new HashSet<String>();
            for (String w : f.words) {
                String k = norm(w).trim();
                if (k.isEmpty() || !seen.add(k)) continue;
                if (has(t, k)) score += 3;
            }
            for (String w : norm(f.name).trim().split(" ")) {
                if (w.length() < 3 || STOP.contains(w) || !seen.add(w)) continue;
                if (has(t, w)) score += 3;
            }
            if (learned != null) {
                for (String tok : toks) {
                    Map<String, Integer> m = learned.get(tok);
                    if (m == null) continue;
                    Integer mine = m.get(f.id);
                    if (mine == null || mine < 2) continue;
                    int total = 0;
                    for (Integer v : m.values()) total += v;
                    if (mine * 10 >= total * 6) score += Math.min(mine, 4) * 0.75;
                }
            }
            if (score > best) {
                second = best;
                best = score;
                bestId = f.id;
            } else if (score > second) {
                second = score;
            }
        }
        if (best < 1.5 || best <= second) return new Pick("", false);
        return new Pick(bestId, best >= 3 && best - second >= 1.5);
    }

    /** Remember that a video with these words went into this folder. weight 2 = you corrected the app. */
    public static void learn(Map<String, Map<String, Integer>> learned, String text, String url,
                             String folderId, int weight) {
        if (learned == null || folderId == null || folderId.isEmpty() || "other".equals(folderId)) return;
        List<String> toks = tokens(text);
        if (toks.size() > 25) toks = toks.subList(0, 25);
        String ht = hostToken(url);
        if (!ht.isEmpty()) toks.add(ht);
        for (String tok : toks) {
            Map<String, Integer> m = learned.get(tok);
            if (m == null) {
                m = new HashMap<String, Integer>();
                learned.put(tok, m);
            }
            Integer old = m.get(folderId);
            m.put(folderId, Math.min(99, (old == null ? 0 : old) + Math.max(1, weight)));
        }
        if (learned.size() > 3000) {
            // keep it small: forget words seen only once
            Iterator<Map.Entry<String, Map<String, Integer>>> it = learned.entrySet().iterator();
            while (it.hasNext()) {
                int total = 0;
                for (Integer v : it.next().getValue().values()) total += v;
                if (total <= 1) it.remove();
            }
        }
    }

    /* ---------- the learned words file: one "word <tab> folder <tab> count" per line ---------- */

    public static Map<String, Map<String, Integer>> loadLearned(File f) {
        Map<String, Map<String, Integer>> out = new HashMap<String, Map<String, Integer>>();
        if (f == null || !f.exists()) return out;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t");
                    if (p.length != 3) continue;
                    int n;
                    try {
                        n = Integer.parseInt(p[2]);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (n <= 0 || p[0].isEmpty() || p[1].isEmpty()) continue;
                    Map<String, Integer> m = out.get(p[0]);
                    if (m == null) {
                        m = new HashMap<String, Integer>();
                        out.put(p[0], m);
                    }
                    m.put(p[1], n);
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            // start fresh; only the learning is lost, never the videos
        }
        return out;
    }

    public static boolean saveLearned(File f, Map<String, Map<String, Integer>> learned) {
        File tmp = new File(f.getPath() + ".tmp");
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
            try {
                for (Map.Entry<String, Map<String, Integer>> e : learned.entrySet()) {
                    for (Map.Entry<String, Integer> c : e.getValue().entrySet()) {
                        w.write(e.getKey() + "\t" + c.getKey() + "\t" + c.getValue() + "\n");
                    }
                }
            } finally {
                w.close();
            }
            if (tmp.renameTo(f)) return true;
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return tmp.renameTo(f);
        } catch (IOException e) {
            return false;
        }
    }

    /* ---------- starter word lists for the starter folders (you can edit them in the app) ---------- */

    public static String[] starterWords(String folderId) {
        if ("food".equals(folderId)) {
            return ("food, foodie, recipe, cook, cooking, cooked, kitchen, chef, eat, eating, mukbang, delicious, yummy, "
                    + "tasty, restaurant, street food, bake, baking, cake, dessert, pork, chicken, beef, seafood, rice, "
                    + "noodles, pasta, pizza, burger, bbq, grill, grilled, fried, air fryer, adobo, sinigang, lechon, sisig, "
                    + "ulam, luto, lutong, pagkain, merienda, masarap, sarap, shawarma, biryani, breakfast, lunch, dinner, "
                    + "snack").split(",\\s*");
        }
        if ("singing".equals(folderId)) {
            return ("sing, sings, singing, singer, sang, song, cover, karaoke, videoke, vocal, vocals, acoustic, duet, "
                    + "concert, lyrics, music video, ballad, high note, birit, kanta, kumanta, awit, wish 107, the voice, "
                    + "got talent, choir, live band").split(",\\s*");
        }
        if ("basketball".equals(folderId)) {
            return ("basketball, nba, pba, fiba, uaap, gilas, ginebra, dunk, three pointer, buzzer beater, triple double, "
                    + "playoffs, crossover, layup, hoops, lakers, warriors, celtics, bulls, knicks, mavericks, mavs, nuggets, "
                    + "bucks, suns, clippers, spurs, sixers, lebron, curry, jokic, doncic, luka, giannis, durant, kobe, "
                    + "jordan, wembanyama, kyrie, tatum").split(",\\s*");
        }
        if ("funny".equals(folderId)) {
            return ("funny, comedy, comedian, joke, prank, meme, lol, laugh, laughing, hilarious, fail, bloopers, stand up, "
                    + "standup, skit, parody, tawa, nakakatawa, laptrip, laugh trip, biro").split(",\\s*");
        }
        return new String[0];
    }
}
