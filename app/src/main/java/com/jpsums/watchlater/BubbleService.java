package com.jpsums.watchlater;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * The floating WatchLater+ button that stays on top of Facebook, TikTok, YouTube and other apps.
 *
 * It cannot see which video is playing (Android does not let one app read another), so the
 * flow is: in the video app tap Share > Copy link, then tap this button. The button opens the
 * usual save popup, which reads the copied link.
 *
 * Drag it anywhere; it sticks to the nearest side. Press and hold to turn it off.
 */
public class BubbleService extends Service {

    static final String ACTION_STOP = "com.jpsums.watchlater.BUBBLE_OFF";
    private static final String CHANNEL = "bubble";
    private static final int NOTIFY_ID = 42;
    private static final String PREFS = "bubble";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View bubble;
    private WindowManager.LayoutParams lp;
    private int size;

    /* ---------- switches used by the rest of the app ---------- */

    /** Has the phone's "Appear on top" permission been given? */
    static boolean allowed(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** Is the button switched on in the app's Settings? */
    static boolean wanted(Context c) {
        try {
            JSONObject s = new JSONObject(Store.readRaw(c)).optJSONObject("settings");
            return s != null && s.optBoolean("bubble");
        } catch (Exception e) {
            return false;
        }
    }

    static void start(Context c) {
        if (!allowed(c)) return;
        try {
            c.startForegroundService(new Intent(c, BubbleService.class));
        } catch (Exception e) {
            // the phone refused to start it right now; opening the app starts it again
        }
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, BubbleService.class));
        } catch (Exception e) {
            // already gone
        }
    }

    static void startIfWanted(Context c) {
        if (wanted(c) && allowed(c)) start(c);
    }

    /* ---------- the service ---------- */

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        goForeground();
        showBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            turnOff(false);
            return START_NOT_STICKY;
        }
        if (bubble == null) showBubble();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        removeBubble();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // the screen was turned: keep the button on its side and inside the screen
        if (bubble == null || lp == null) return;
        place(prefs().getBoolean("right", true), prefs().getFloat("y", 0.45f));
        try {
            wm.updateViewLayout(bubble, lp);
        } catch (Exception e) {
            // not attached any more
        }
    }

    /** Android asks a service that stays alive to show a notification. Ours also has an off switch. */
    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Floating button",
                    NotificationManager.IMPORTANCE_LOW));
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags);
        PendingIntent off = PendingIntent.getService(this, 4,
                new Intent(this, BubbleService.class).setAction(ACTION_STOP), flags);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(0xFFF5A516)
                .setContentTitle("WatchLater+ button is on")
                .setContentText("Copy a video's link, then tap the button to save it.")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((Icon) null, "Turn off", off).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFY_ID, n);
            }
        } catch (Exception e) {
            stopSelf();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void place(boolean right, float yFraction) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        lp.x = right ? Math.max(0, m.widthPixels - size) : 0;
        int maxY = Math.max(0, m.heightPixels - size - dp(60));
        lp.y = Math.max(dp(40), Math.min(maxY, Math.round(m.heightPixels * yFraction)));
    }

    private void showBubble() {
        if (bubble != null) return;
        if (!allowed(this)) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        size = dp(54);

        FrameLayout root = new FrameLayout(this);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_bubble);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFFFFB22E);
        bg.setStroke(dp(2), 0x55000000);
        icon.setBackground(bg);
        int pad = dp(12);
        icon.setPadding(pad, pad, pad, pad);
        root.addView(icon, new FrameLayout.LayoutParams(size, size));
        root.setAlpha(0.9f);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        place(prefs().getBoolean("right", true), prefs().getFloat("y", 0.45f));

        root.setOnTouchListener(new Drag());
        try {
            wm.addView(root, lp);
            bubble = root;
        } catch (Exception e) {
            bubble = null;
            stopSelf();
        }
    }

    private void removeBubble() {
        if (bubble == null) return;
        try {
            wm.removeView(bubble);
        } catch (Exception e) {
            // already removed
        }
        bubble = null;
    }

    /** Tap = save the copied link. Drag = move. Press and hold = turn the button off. */
    private final class Drag implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean moved, held;
        private final int slop = ViewConfiguration.get(BubbleService.this).getScaledTouchSlop();
        private final Runnable hold = () -> {
            held = true;
            turnOff(true);
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    moved = false;
                    held = false;
                    v.setAlpha(1f);
                    handler.postDelayed(hold, 800);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) > slop) {
                        moved = true;
                        handler.removeCallbacks(hold);
                    }
                    if (moved && !held) {
                        DisplayMetrics m = getResources().getDisplayMetrics();
                        lp.x = Math.max(0, Math.min(m.widthPixels - size, startX + Math.round(dx)));
                        lp.y = Math.max(0, Math.min(m.heightPixels - size, startY + Math.round(dy)));
                        try {
                            wm.updateViewLayout(v, lp);
                        } catch (Exception ex) {
                            // not attached any more
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(hold);
                    v.setAlpha(0.9f);
                    if (held) return true;
                    if (moved) {
                        stick(v);
                    } else {
                        tapped(v);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(hold);
                    v.setAlpha(0.9f);
                    return true;
                default:
                    return false;
            }
        }
    }

    /** After a drag: slide to the nearest side and remember the spot. */
    private void stick(View v) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        boolean right = lp.x + size / 2 >= m.widthPixels / 2;
        float y = m.heightPixels > 0 ? (float) lp.y / m.heightPixels : 0.45f;
        prefs().edit().putBoolean("right", right).putFloat("y", y).apply();
        place(right, y);
        try {
            wm.updateViewLayout(v, lp);
        } catch (Exception e) {
            // not attached any more
        }
    }

    private void tapped(View v) {
        v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
        try {
            startActivity(new Intent(this, ShareActivity.class)
                    .setAction(ShareActivity.ACTION_CLIP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "WatchLater+ could not open. Open the app once and try again.", Toast.LENGTH_LONG).show();
        }
    }

    private void turnOff(boolean say) {
        Store.update(this, data -> {
            JSONObject s = data.optJSONObject("settings");
            if (s == null) {
                s = new JSONObject();
                data.put("settings", s);
            }
            s.put("bubble", false);
            return true;
        });
        if (say) {
            Toast.makeText(this, "Floating button turned off. Turn it on again in WatchLater+ Settings.",
                    Toast.LENGTH_LONG).show();
        }
        removeBubble();
        stopForeground(true);
        stopSelf();
    }
}
