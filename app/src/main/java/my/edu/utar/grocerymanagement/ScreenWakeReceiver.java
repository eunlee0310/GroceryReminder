package my.edu.utar.grocerymanagement;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.Calendar;

public class ScreenWakeReceiver extends BroadcastReceiver {
    private static final String TAG = "ScreenWakeReceiver";
    private static final String PREF_WAKE_THROTTLE = "wake_throttle";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (!Intent.ACTION_USER_PRESENT.equals(intent.getAction())) return; // only after user unlocks

        // Respect 7–21 window
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 7 || hour >= 21) {
            Log.d(TAG, "Outside 7–21 window → skip wake check");
            return;
        }

        // 30s throttle to avoid double fires
        long now = System.currentTimeMillis();
        SharedPreferences sp = context.getSharedPreferences(PREF_WAKE_THROTTLE, Context.MODE_PRIVATE);
        long last = sp.getLong("last", 0L);
        if (now - last < 30_000L) {
            Log.d(TAG, "Throttled wake check");
            return;
        }
        sp.edit().putLong("last", now).apply();

        // Kick the same auto path your alarms use
        Intent i = new Intent(context, NotificationService.class);
        i.setAction("my.edu.utar.grocerymanagement.CHECK_NOTIFICATIONS");
        i.putExtra("type", "screen_wake");
        i.putExtra("fromSnooze", false);
        context.sendBroadcast(i);

        Log.i(TAG, "🚀 Wake check sent on unlock");
    }
}
