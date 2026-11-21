package com.jpsums.watchlater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Wakes up for the daily reminder, and sets it again after the phone restarts or the app updates. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Reminders.ACTION_FIRE.equals(action)) Reminders.show(context, false);
        Reminders.schedule(context);
        if (!Reminders.ACTION_FIRE.equals(action)) {
            // the phone restarted or the app was updated: bring the floating button back if it was on
            try {
                BubbleService.startIfWanted(context.getApplicationContext());
            } catch (Exception e) {
                // opening the app starts it again
            }
        }
    }
}
