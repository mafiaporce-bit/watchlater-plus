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
                    new JSONObject(s); // make sure it is still valid
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
            folders.put(folder("food", "Food", "🍔"));
            folders.put(folder("singing", "Singing", "🎤"));
            folders.put(folder("basketball", "Basketball", "🏀"));
            folders.put(folder("funny", "Funny", "😂"));
            folders.put(folder("other", "Other", "📁"));
            JSONObject settings = new JSONObject();
            settings.put("theme", "auto");
            JSONObject d = new JSONObject();
            d.put("v", 1);
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
}
