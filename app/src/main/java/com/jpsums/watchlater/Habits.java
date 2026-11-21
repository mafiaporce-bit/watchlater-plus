package com.jpsums.watchlater;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

/**
 * When you usually open WatchLater+, so the smart reminder can come around that time.
 * Kept on the phone only (files/habits.json): a weight per hour of the day, with older
 * opens slowly counting less so it follows changes in your routine (new shift pattern).
 */
public final class Habits {

    private static final String FILE = "habits.json";
    private static final Object LOCK = new Object();
    /** Each new open makes all earlier ones count a little less. */
    static final double FADE = 0.97;
    /** Opens closer together than this count once. */
    private static final long GAP = 30L * 60 * 1000;

    private Habits() { }

    /** The hour (0-23) you open the app most, or -1 while there is not enough to go on. Plain Java, testable. */
    public static int bestHour(double[] w, int opens) {
        if (w == null || w.length != 24 || opens < 5) return -1;
        // look at each hour together with its neighbours, so 8:50 and 9:10 opens count as the same habit
        int best = -1;
        double top = 0, total = 0;
        for (double v : w) total += v;
        for (int h = 0; h < 24; h++) {
            double s = w[h] + 0.5 * w[(h + 23) % 24] + 0.5 * w[(h + 1) % 24];
            if (s > top) {
                top = s;
                best = h;
            }
        }
        if (best < 0 || total <= 0) return -1;
        // a real habit: that time of day holds a clear share of all opens
        return top / total >= 0.3 ? best : -1;
    }

    /** Adds one open at this hour (plain Java, testable). */
    public static void add(double[] w, int hour) {
        for (int i = 0; i < 24; i++) w[i] *= FADE;
        w[hour] += 1;
    }

    private static JSONObject read(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return new JSONObject();
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[(int) Math.min(f.length(), 20000)];
                int n = in.read(b);
                return new JSONObject(new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8));
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static double[] weights(JSONObject o) {
        double[] w = new double[24];
        JSONArray a = o.optJSONArray("w");
        for (int i = 0; a != null && i < 24 && i < a.length(); i++) w[i] = a.optDouble(i, 0);
        return w;
    }

    /** The app came to the front. */
    public static void recordOpen(Context context) {
        Context c = context.getApplicationContext();
        synchronized (LOCK) {
            try {
                JSONObject o = read(c);
                long now = System.currentTimeMillis();
                if (now - o.optLong("last") < GAP) return;
                double[] w = weights(o);
                add(w, Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
                JSONArray a = new JSONArray();
                for (double v : w) a.put(Math.round(v * 1000) / 1000.0);
                o.put("w", a);
                o.put("n", o.optInt("n") + 1);
                o.put("last", now);
                FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), FILE));
                try {
                    out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
            } catch (JSONException | java.io.IOException e) {
                // only the habit is lost, never anything else
            }
        }
    }

    /** The hour you usually open the app, or -1 while it is still learning. */
    public static int usualHour(Context c) {
        synchronized (LOCK) {
            JSONObject o = read(c.getApplicationContext());
            return bestHour(weights(o), o.optInt("n"));
        }
    }

    /** How many opens it has seen so far (for "still learning"). */
    public static int opens(Context c) {
        synchronized (LOCK) {
            return read(c.getApplicationContext()).optInt("n");
        }
    }
}
