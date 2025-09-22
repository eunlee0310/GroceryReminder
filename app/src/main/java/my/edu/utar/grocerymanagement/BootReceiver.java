package my.edu.utar.grocerymanagement;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    private static final String PREF_SNOOZE = "active_snooze";
    private static final String PREF_NOTIFY = "notify_state";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {

            Log.i(TAG, "✅ Device rebooted → restoring notification schedules");

            SharedPreferences snoozePrefs = context.getSharedPreferences(PREF_SNOOZE, Context.MODE_PRIVATE);
            SharedPreferences notifyPrefs = context.getSharedPreferences(PREF_NOTIFY, Context.MODE_PRIVATE);

            long now = System.currentTimeMillis();

            // Restore snooze if active
            boolean isSnoozed = snoozePrefs.getBoolean("is_snoozed", false);
            long nextSnoozeAt = snoozePrefs.getLong("snooze_next_at", 0);
            String snoozeType = snoozePrefs.getString("last_type", "expired");

            if (isSnoozed && nextSnoozeAt > now) {
                NotificationService.scheduleAlarm(context, snoozeType, nextSnoozeAt, true);
                Log.i(TAG, "⏸ Restored snooze alarm for type=" + snoozeType + " at " + nextSnoozeAt);
            }

            // Restore auto-resend if last notify exists
            long lastNotifyTime = notifyPrefs.getLong("last_notify_time", 0);
            if (!isSnoozed && lastNotifyTime > 0) {
                long nextRetry = lastNotifyTime + NotificationService.AUTO_RESEND_INTERVAL;
                if (nextRetry > now) {
                    NotificationService.scheduleAlarm(context, "expired", nextRetry, false);
                    Log.i(TAG, "⏰ Restored auto-resend alarm at " + nextRetry);
                }
            }
            NotificationService.scheduleMorningCheck(context);
        }
    }
}
