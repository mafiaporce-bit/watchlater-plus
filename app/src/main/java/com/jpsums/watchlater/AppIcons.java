package com.jpsums.watchlater;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The real app icons (Facebook, Instagram, TikTok...) taken from the apps installed on this
 * phone, saved as small pictures in files/icons so the list can show where each video came from.
 * An app that is not installed gets a simple stand-in badge in the page instead.
 */
public final class AppIcons {

    /** page key -> the apps that count for it (first one installed wins) */
    private static final String[][] APPS = {
            {"fb", "com.facebook.katana", "com.facebook.lite"},
            {"ig", "com.instagram.android", "com.instagram.lite"},
            {"tt", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go"},
            {"yt", "com.google.android.youtube"},
            {"x", "com.twitter.android"},
    };

    private AppIcons() { }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "icons");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** Makes the icon pictures that are missing or out of date; returns {"fb":<version>,...} for those that exist. */
    public static synchronized String build(Context c) {
        JSONObject out = new JSONObject();
        PackageManager pm = c.getPackageManager();
        for (String[] row : APPS) {
            String key = row[0];
            File f = new File(dir(c), key + ".png");
            boolean done = false;
            for (int i = 1; i < row.length && !done; i++) {
                try {
                    PackageInfo info = pm.getPackageInfo(row[i], 0);
                    long stamp = info.lastUpdateTime;
                    if (!f.exists() || f.lastModified() < stamp) {
                        Drawable d = pm.getApplicationIcon(row[i]);
                        if (!save(d, f)) continue;
                    }
                    out.put(key, f.lastModified());
                    done = true;
                } catch (PackageManager.NameNotFoundException e) {
                    // not installed: try the next one
                } catch (JSONException e) {
                    done = true;
                }
            }
            if (!done && f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete(); // app removed: fall back to the stand-in badge
            }
        }
        return out.toString();
    }

    private static boolean save(Drawable d, File f) {
        try {
            int size = 96;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(cv);
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
            } finally {
                o.close();
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return tmp.renameTo(f);
        } catch (Exception e) {
            return false;
        }
    }
}
