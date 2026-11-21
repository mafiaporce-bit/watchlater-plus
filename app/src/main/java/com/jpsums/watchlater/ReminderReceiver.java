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
    }
}
