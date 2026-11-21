package com.jpsums.watchlater;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** The daily "you have videos to watch" reminder. */
public final class Reminders {

    static final String ACTION_FIRE = "com.jpsums.watchlater.REMIND";
    private static final String CHANNEL = "reminders";
    private static final int NOTIFY_ID = 41;

    private Reminders() { }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(ACTION_FIRE);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Sets (or clears) the next reminder from the Settings in the app. Safe to call any time. */
    public static void schedule(Context context) {
        Context c = context.getApplicationContext();
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = alarmIntent(c);
            am.cancel(pi);

            JSONObject settings = new JSONObject(Store.readRaw(c)).optJSONObject("settings");
            JSONObject r = settings == null ? null : settings.optJSONObject("remind");
            if (r == null || !r.optBoolean("on")) return;
            int h = Math.max(0, Math.min(23, r.optInt("h", 20)));
            int m = Math.max(0, Math.min(59, r.optInt("m", 0)));
            if (r.optBoolean("smart")) {
                // "when I usually open the app": that hour, once it has learned it (until then, the set time)
                int usual = Habits.usualHour(c);
                if (usual >= 0) {
                    h = usual;
                    m = 0;
                }
            }
            boolean[] days = days(r);
            long now = System.currentTimeMillis();
            for (int add = 0; add <= 7; add++) {
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, add);
                cal.set(Calendar.HOUR_OF_DAY, h);
                cal.set(Calendar.MINUTE, m);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                if (cal.getTimeInMillis() <= now + 30000) continue;
                if (!days[cal.get(Calendar.DAY_OF_WEEK) - 1]) continue;
                // not an "exact" alarm (those need a special permission): it arrives around the set time
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
                return;
            }
        } catch (Exception e) {
            // a reminder problem must never break the app
        }
    }

    /** Which days the reminder may come (0 = Sunday ... 6 = Saturday). None chosen = every day. */
    static boolean[] days(JSONObject r) {
        boolean[] out = new boolean[7];
        JSONArray a = r == null ? null : r.optJSONArray("days");
        boolean any = false;
        for (int i = 0; a != null && i < a.length(); i++) {
            int d = a.optInt(i, -1);
            if (d >= 0 && d <= 6) {
                out[d] = true;
                any = true;
            }
        }
        if (!any) java.util.Arrays.fill(out, true);
        return out;
    }

    /** True when a reminder may come today. */
    static boolean today(Context c) {
        try {
            JSONObject settings = new JSONObject(Store.readRaw(c)).optJSONObject("settings");
            JSONObject r = settings == null ? null : settings.optJSONObject("remind");
            return days(r)[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1];
        } catch (JSONException e) {
            return true;
        }
    }

    public static boolean allowed(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    /** Shows the reminder now. With test=true it shows even when there is nothing to watch. */
    public static void show(Context context, boolean test) {
        Context c = context.getApplicationContext();
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            JSONObject data = new JSONObject(Store.readRaw(c));
            JSONObject previews = new JSONObject(Previews.readRaw(c));
            JSONArray items = data.optJSONArray("items");
            List<JSONObject> waiting = new ArrayList<JSONObject>();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null && !it.optBoolean("watched")) waiting.add(it);
            }
            if (waiting.isEmpty() && !test) return;
            if (!test && !today(c)) return;   // not one of your chosen days

            // newest first
            java.util.Collections.sort(waiting, (a, b) -> Long.compare(b.optLong("addedAt"), a.optLong("addedAt")));
            int n = waiting.size();
            String title = n == 0 ? "Nothing waiting. This is a test 🍿"
                    : n == 1 ? "1 video is waiting for you 🍿"
                    : n + " videos are waiting for you 🍿";
            StringBuilder lines = new StringBuilder();
            for (int i = 0; i < n && i < 4; i++) {
                if (lines.length() > 0) lines.append('\n');
                lines.append("• ").append(Store.displayName(waiting.get(i), previews));
            }
            if (n > 4) lines.append("\nand ").append(n - 4).append(" more");
            String first = n == 0 ? "Reminders are working." : Store.displayName(waiting.get(0), previews);

            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Watch reminders",
                    NotificationManager.IMPORTANCE_DEFAULT));

            Intent open = new Intent(c, MainActivity.class)
                    .putExtra("show", "towatch")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent tap = PendingIntent.getActivity(c, 2, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setColor(0xFFF5A516)
                    .setContentTitle(title)
                    .setContentText(first)
                    .setContentIntent(tap)
                    .setAutoCancel(true);
            if (n > 0) b.setStyle(new Notification.BigTextStyle().bigText(lines.toString()));
            nm.notify(NOTIFY_ID, b.build());
        } catch (JSONException e) {
            // nothing to show
        } catch (Exception e) {
            // a reminder problem must never break the app
        }
    }
}
