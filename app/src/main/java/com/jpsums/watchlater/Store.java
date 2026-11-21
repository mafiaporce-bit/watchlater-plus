package com.jpsums.watchlater;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one place the saved videos and folders live: a small JSON file inside the app.
 * Both the main screen and the Share popup read and write it through here.
 */
public final class Store {

    private static final String FILE = "watchlater_data.json";
    private static final Object LOCK = new Object();

    private Store() { }

    /** The whole file as text. Creates it with the starter folders the first time. */
    public static String readRaw(Context c) {
        synchronized (LOCK) {
            File f = new File(c.getFilesDir(), FILE);
            if (f.exists()) {
                try {
                    String s = readFile(f);
                    JSONObject d = new JSONObject(s); // make sure it is still valid
                    if (d.optInt("v", 1) < 2) {
                        upgrade(d);
                        s = d.toString();
                        writeLocked(c, s);
                    }
                    return s;
                } catch (IOException | JSONException e) {
                    // keep the broken copy aside instead of losing it
                    //noinspection ResultOfMethodCallIgnored
                    f.renameTo(new File(c.getFilesDir(), FILE + ".bad"));
                }
            }
            String d = defaults();
            writeLocked(c, d);
            return d;
        }
    }

    /** Replaces the file. Ignores anything that is not valid JSON. */
    public static boolean writeRaw(Context c, String json) {
        if (json == null) return false;
        try {
            new JSONObject(json);
        } catch (JSONException e) {
            return false;
        }
        synchronized (LOCK) {
            return writeLocked(c, json);
        }
    }

    private static boolean writeLocked(Context c, String json) {
        File dir = c.getFilesDir();
        File tmp = new File(dir, FILE + ".tmp");
        File dst = new File(dir, FILE);
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (tmp.renameTo(dst)) return true;
            // rename can fail on some phones: write straight to the file instead
            FileOutputStream direct = new FileOutputStream(dst);
            try {
                direct.write(json.getBytes(StandardCharsets.UTF_8));
            } finally {
                direct.close();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String readFile(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    /** Starter folders. Keep the ids the same as defaults() in index.html. */
    private static String defaults() {
        try {
            JSONArray folders = new JSONArray();
            folders.put(withWords(folder("food", "Food", "🍔")));
            folders.put(withWords(folder("singing", "Singing", "🎤")));
            folders.put(withWords(folder("basketball", "Basketball", "🏀")));
            folders.put(withWords(folder("funny", "Funny", "😂")));
            folders.put(folder("other", "Other", "📁"));
            JSONObject settings = new JSONObject();
            settings.put("theme", "auto");
            JSONObject d = new JSONObject();
            d.put("v", 2);
            d.put("folders", folders);
            d.put("items", new JSONArray());
            d.put("settings", settings);
            return d.toString();
        } catch (JSONException e) {
            return "{\"v\":1,\"folders\":[],\"items\":[],\"settings\":{\"theme\":\"auto\"}}";
        }
    }

    static JSONObject folder(String id, String name, String emoji) throws JSONException {
        JSONObject f = new JSONObject();
        f.put("id", id);
        f.put("name", name);
        f.put("emoji", emoji);
        return f;
    }

    /* ---------- Part 2: safe changes, auto-sort words, learning ---------- */

    /** A change to the saved data. Return false to leave the file as it is. */
    public interface Change {
        boolean apply(JSONObject data) throws JSONException;
    }

    /** Read, change and write in one step, so two things saving at once cannot lose each other's work. */
    public static boolean update(Context c, Change change) {
        synchronized (LOCK) {
            try {
                JSONObject d = new JSONObject(readRaw(c));
                if (!change.apply(d)) return false;
                return writeLocked(c, d.toString());
            } catch (JSONException e) {
                return false;
            }
        }
    }

    /** Version 1 files (Part 1) get the starter auto-sort words added to the starter folders. */
    private static void upgrade(JSONObject d) throws JSONException {
        JSONArray folders = d.optJSONArray("folders");
        if (folders != null) {
            for (int i = 0; i < folders.length(); i++) {
                JSONObject f = folders.optJSONObject(i);
                if (f != null && !f.has("words")) withWords(f);
            }
        }
        d.put("v", 2);
    }

    private static JSONObject withWords(JSONObject f) throws JSONException {
        String[] w = Sorter.starterWords(f.optString("id"));
        if (w.length > 0) {
            JSONArray arr = new JSONArray();
            for (String x : w) arr.put(x.trim());
            f.put("words", arr);
        }
        return f;
    }

    static List<Sorter.Folder> sorterFolders(JSONObject data) {
        List<Sorter.Folder> out = new ArrayList<Sorter.Folder>();
        JSONArray folders = data.optJSONArray("folders");
        if (folders == null) return out;
        for (int i = 0; i < folders.length(); i++) {
            JSONObject f = folders.optJSONObject(i);
            if (f == null) continue;
            List<String> words = new ArrayList<String>();
            JSONArray w = f.optJSONArray("words");
            if (w != null) for (int j = 0; j < w.length(); j++) words.add(w.optString(j));
            out.add(new Sorter.Folder(f.optString("id"), f.optString("name"), words));
        }
        return out;
    }

    private static final Object LEARN_LOCK = new Object();

    private static File learnFile(Context c) {
        return new File(c.getFilesDir(), "learned.txt");
    }

    /** Which folder do these words belong in? */
    public static Sorter.Pick suggest(Context c, JSONObject data, String text, String url) {
        Map<String, Map<String, Integer>> learned;
        synchronized (LEARN_LOCK) {
            learned = Sorter.loadLearned(learnFile(c));
        }
        return Sorter.suggest(text, url, sorterFolders(data), learned);
    }

    /** Remember a folder you chose by hand, so next time the app can choose it for you. */
    public static void learn(Context c, String text, String url, String folderId, int weight) {
        if (text == null) text = "";
        if (text.trim().isEmpty() && Sorter.hostToken(url).isEmpty()) return;
        synchronized (LEARN_LOCK) {
            Map<String, Map<String, Integer>> learned = Sorter.loadLearned(learnFile(c));
            Sorter.learn(learned, text, url, folderId, weight);
            Sorter.saveLearned(learnFile(c), learned);
        }
    }

    /** The name shown for a video: your note, else its title, else the fetched title. */
    static String displayName(JSONObject item, JSONObject previews) {
        String s = item.optString("note").trim();
        if (s.isEmpty()) s = item.optString("title").trim();
        if (s.isEmpty() && previews != null) {
            JSONObject p = previews.optJSONObject(item.optString("id"));
            if (p != null) s = p.optString("t").trim();
        }
        if (s.isEmpty()) {
            String h = Sorter.hostToken(item.optString("url"));
            s = h.isEmpty() ? "A saved video" : "Video from " + h.substring(1);
        }
        return s;
    }
}
