package my.edu.utar.grocerymanagement;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.Source;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class NotificationService extends BroadcastReceiver {
    private static final String TAG = "NotificationService";

    // ===== Channel & IDs =====
    private static final String CHANNEL_ID = "grocery_notifications";
    private static final String NOTIFICATION_GROUP = "grocery_notifications_group";
    private static final String TYPE_GROUP = "group";
    private static final int SUMMARY_NOTIFICATION_ID = 9999;

    // ===== Timing rules =====
    public static final long AUTO_RESEND_INTERVAL  = TimeUnit.HOURS.toMillis(2);
    public static final long SNOOZE_RETRY_INTERVAL = TimeUnit.MINUTES.toMillis(10);
    public static final int  MAX_SNOOZE_RETRIES    = 2;

    // ===== Watchdog (15 min) =====
    private static final long WATCHDOG_INTERVAL = TimeUnit.MINUTES.toMillis(15);
    private static final String ACTION_CHECK        = "my.edu.utar.grocerymanagement.CHECK_NOTIFICATIONS";
    private static final String ACTION_CHECK_RETRY  = "my.edu.utar.grocerymanagement.CHECK_NOTIFICATIONS_RETRY";
    private static final String ACTION_MARK_SEEN    = "my.edu.utar.grocerymanagement.MARK_SEEN";
    private static final String ACTION_SNOOZE_SET   = "my.edu.utar.grocerymanagement.SNOOZE_TO_TIME";
    private static final String ACTION_WATCHDOG     = "my.edu.utar.grocerymanagement.WATCHDOG";

    // ===== SharedPrefs keys =====
    private static final String PREF_NOTIFICATION_COUNT = "notification_count";
    private static final String PREF_CONSECUTIVE_DAYS   = "consecutive_days";
    private static final String PREF_LAST_CHECK_DATE    = "last_check_date";
    private static final String PREF_SNOOZE_RETRIES     = "snooze_retries";
    private static final String PREF_SEEN               = "seen_notifications";
    public  static final String PREF_SNOOZE             = "active_snooze";
    private static final String PREF_LAST_NOTIFY_DATE   = "last_notify_date";

    // ===== Lists =====
    private final List<String> lowConsumptionItems = new ArrayList<>();
    private final List<String> forgottenItems      = new ArrayList<>();
    private final List<String> expiredItems        = new ArrayList<>();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        createNotificationChannel(context);
        final PendingResult pending = goAsync();

        switch (intent.getAction()) {
            case ACTION_CHECK:
            case ACTION_CHECK_RETRY: {
                boolean fromSnooze = intent.getBooleanExtra("fromSnooze", false);
                String snoozeType   = intent.getStringExtra("type");
                boolean force       = intent.getBooleanExtra("force", false);
                boolean uiOnly      = intent.getBooleanExtra("uiOnly", false);

                // regular flows → watchdogAuto=false
                checkNotificationConditions(context, fromSnooze, snoozeType, force, uiOnly, /*watchdogAuto*/ false, pending);

                String reqType = intent.getStringExtra("type");
                if ("daily_kick".equals(reqType)) {
                    scheduleMorningCheck(context);
                }
                scheduleWatchdog(context);
                break;
            }

            case ACTION_MARK_SEEN: {
                markItemSeen(context);
                scheduleWatchdog(context);
                pending.finish();
                break;
            }

            case ACTION_SNOOZE_SET: {
                String type = intent.getStringExtra("notificationType");
                long snoozeTimeMillis = intent.getLongExtra("snoozeTimeMillis", 0);
                handleSpecificSnooze(context, type, snoozeTimeMillis);
                scheduleWatchdog(context);
                pending.finish();
                break;
            }

            case ACTION_WATCHDOG: {
                runWatchdog(context, pending);
                scheduleWatchdog(context);
                break;
            }

            default:
                pending.finish();
        }
    }

    private void checkNotificationConditions(Context context,
                                             boolean fromSnooze,
                                             String snoozeType,
                                             boolean force,
                                             boolean uiOnly,
                                             boolean watchdogAuto,
                                             BroadcastReceiver.PendingResult pending) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = pm != null && pm.isInteractive();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        long now = System.currentTimeMillis();

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        SharedPreferences countPrefs = context.getSharedPreferences(PREF_NOTIFICATION_COUNT, Context.MODE_PRIVATE);
        int todayCount = countPrefs.getInt(today, -1);
        if (todayCount == -1) {
            countPrefs.edit().putInt(today, 0).apply();
            todayCount = 0;
        }

        SharedPreferences snoozePrefs = context.getSharedPreferences(PREF_SNOOZE, Context.MODE_PRIVATE);
        String lastSnoozeDate = snoozePrefs.getString("snooze_date", "");
        if (!today.equals(lastSnoozeDate)) {
            snoozePrefs.edit().putBoolean("is_snoozed", false).apply();
            Log.i(TAG, "⏸ New day → snooze flag cleared automatically");
        }
        boolean snoozed = snoozePrefs.getBoolean("is_snoozed", false);
        long nextAt = snoozePrefs.getLong("snooze_next_at", 0L);

        SharedPreferences userPrefs = context.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE);
        int userLimit = userPrefs.getInt("maxNotificationsPerDay", 3);

        if (!force) {
            // If this is an AUTO check while we're snoozed:
            if (!fromSnooze && snoozed) {
                if (nextAt > 0 && now >= nextAt) {
                    // Snooze time has passed -> promote this AUTO run to a SNOOZE delivery now.
                    fromSnooze = true;
                    Log.d(TAG, "⏩ Auto run after snooze end → promoted to snooze delivery (now)");
                } else {
                    // Still inside the snooze window → just skip; the existing snooze alarm will fire.
                    Log.d(TAG, "⏸ Snooze guard active (auto path) → skip until " + new Date(nextAt));
                    pending.finish();
                    return;
                }
            }

            if (fromSnooze) {
                // Snooze path only respects the daytime window
                if (hour < 7 || hour >= 21) {
                    long retryWhen = now + TimeUnit.MINUTES.toMillis(5);
                    scheduleAlarm(context, snoozeType != null ? snoozeType : "expired", retryWhen, true);
                    Log.d(TAG, "⏸ Snooze skipped (outside 7–21) → retry 5m");
                    pending.finish();
                    return;
                }
            } else {
                // Auto path: time + screen state
                if (hour < 7 || hour >= 21 || !isScreenOn) {
                    long retryWhen = now + TimeUnit.MINUTES.toMillis(5);
                    scheduleAlarm(context, snoozeType != null ? snoozeType : "expired", retryWhen, false);
                    Log.d(TAG, "⏸ Auto-resend skipped (time/screen) → retry 5m");
                    pending.finish();
                    return;
                }
                // daily-limit guard (bypass when watchdogAuto)
                if (!watchdogAuto && todayCount >= userLimit) {
                    Log.d(TAG, "Max notifications reached today (" + userLimit + ")");
                    pending.finish();
                    return;
                }
            }
        }

        // Continue into Firestore async; finish() will be called in callback
        checkGroceryItems(context, fromSnooze, snoozeType, uiOnly, watchdogAuto, pending);
    }

    private void checkGroceryItems(Context context,
                                   boolean fromSnooze,
                                   String snoozeType,
                                   boolean uiOnly,
                                   boolean watchdogAuto,
                                   BroadcastReceiver.PendingResult pending) {
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid()
                : null;

        if (uid == null) {
            Log.w(TAG, "FirebaseAuth currentUser is null (cold start) → quick retry in 1m");
            scheduleAlarm(context, snoozeType != null ? snoozeType : "expired",
                    System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1),
                    fromSnooze);
            pending.finish();
            return;
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("users").document(uid).collection("grocery_items")
                .get(Source.SERVER) // ⬅️ force fresh snapshot to avoid stale lastUsed
                .addOnCompleteListener(task -> {
                    try {
                        if (task.isSuccessful()) {
                            lowConsumptionItems.clear();
                            forgottenItems.clear();
                            expiredItems.clear();

                            long now = System.currentTimeMillis();
                            for (QueryDocumentSnapshot doc : task.getResult()) {
                                checkLowConsumption(context, doc, now);
                                checkForgottenItem(context, doc, now, !uiOnly);
                                checkExpiredItem(context, doc);
                            }
                            sendGroupedNotification(context, fromSnooze, snoozeType, uiOnly, watchdogAuto);
                        } else {
                            Log.e(TAG, "Firestore load failed", task.getException());
                        }
                    } finally {
                        pending.finish();
                    }
                });
    }

    private void checkExpiredItem(Context context, DocumentSnapshot document) {
        String productName = document.getString("name");
        Object batchesObj = document.get("batches");
        if (productName == null || !(batchesObj instanceof List)) return;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> batches = (List<Map<String, Object>>) batchesObj;
        if (batches.isEmpty()) return;

        final SimpleDateFormat ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        long todayMidnight = atLocalMidnight(System.currentTimeMillis());

        for (Map<String, Object> b : batches) {
            Number qNum = (Number) b.get("quantity");
            int qty = qNum != null ? qNum.intValue() : 0;
            if (qty <= 0) continue;

            String expStr = (String) b.get("expiryDate");
            if (expStr == null || expStr.isEmpty()) continue;

            Date expDate = null;
            try { expDate = ymd.parse(expStr); } catch (Exception ignored) {}
            if (expDate == null) continue;

            long expMidnight = atLocalMidnight(expDate.getTime());
            if (expMidnight <= todayMidnight) {
                expiredItems.add(productName);   // ← only the name
                Log.d(TAG, productName + " expired");
                break; // add once per product
            }
        }
    }

    private void checkLowConsumption(Context context, DocumentSnapshot document, long currentTime) {
        String productId = document.getId();
        String productName = document.getString("name");

        // Skip if nearest expiry > 90 days
        Object batchesObj = document.get("batches");
        if (batchesObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> batches = (List<Map<String, Object>>) batchesObj;
            for (Map<String, Object> batch : batches) {
                String expiryDateString = (String) batch.get("expiryDate");
                if (expiryDateString == null) continue;
                try {
                    Date expiryDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(expiryDateString);
                    if (expiryDate == null) continue;
                    long daysToExpiry = (expiryDate.getTime() - currentTime) / (1000 * 60 * 60 * 24);
                    if (daysToExpiry > 90) {
                        Log.d(TAG, productName + ": Skipped low consumption (expiry > 90 days)");
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }

        Double acrDouble = document.getDouble("ACR");
        Double ecrDouble = document.getDouble("ECR");
        double ACR = acrDouble != null ? acrDouble : 0f;
        double ECR = ecrDouble != null ? ecrDouble : 0f;

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        SharedPreferences prefs = context.getSharedPreferences(PREF_CONSECUTIVE_DAYS, Context.MODE_PRIVATE);
        SharedPreferences dayPrefs = context.getSharedPreferences(PREF_LAST_CHECK_DATE, Context.MODE_PRIVATE);

        String lastCheckDate = dayPrefs.getString("last_check_" + productId, "");
        int consecutiveDays = prefs.getInt("low_" + productId, 0);

        if (!today.equals(lastCheckDate)) {
            dayPrefs.edit().putString("last_check_" + productId, today).apply();

            if (ACR < ECR) {
                consecutiveDays++;
                prefs.edit().putInt("low_" + productId, consecutiveDays).apply();
                Log.d(TAG, productName + ": Incremented to " + consecutiveDays + " days");
            } else {
                prefs.edit().putInt("low_" + productId, 0).apply();
                Log.d(TAG, productName + ": Reset counter to 0 (ACR ≥ ECR)");
            }

            if (consecutiveDays == 3) {
                prefs.edit().putInt("low_" + productId, 0).apply();
                Log.d(TAG, productName + ": Reset counter after 3 days");
                return;
            }
        }

        if (consecutiveDays == 2) {
            lowConsumptionItems.add(productName);
            Log.d(TAG, productName + ": Ready for notification (2 consecutive days)");
        }
    }

    private void checkForgottenItem(Context context,
                                    DocumentSnapshot document,
                                    long currentTime,
                                    boolean recordThrottle) {
        String productId = document.getId();
        String productName = document.getString("name");

        int totalQty = 0;
        Object batchesObj = document.get("batches");
        if (batchesObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> batches = (List<Map<String, Object>>) batchesObj;

            boolean hasUnexpiredBatch = false;
            final SimpleDateFormat ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            long todayMidnight = atLocalMidnight(System.currentTimeMillis());

            for (Map<String, Object> batch : batches) {
                Object qObj = batch.get("quantity");
                int qty = (qObj instanceof Number) ? ((Number) qObj).intValue() : 0;
                if (qty <= 0) continue;
                totalQty += qty;

                String expStr = (String) batch.get("expiryDate");
                if (TextUtils.isEmpty(expStr)) {
                    hasUnexpiredBatch = true;
                    continue;
                }
                Date expDate = null;
                try { expDate = ymd.parse(expStr); } catch (Exception ignored) {}
                if (expDate == null) {
                    hasUnexpiredBatch = true;
                    continue;
                }
                long expMidnight = atLocalMidnight(expDate.getTime());
                if (expMidnight > todayMidnight) {
                    hasUnexpiredBatch = true;
                }
            }

            // If nothing in stock or ALL batches expired → do NOT include in forgotten
            if (totalQty <= 0) return;
            if (!hasUnexpiredBatch) {
                Log.d(TAG, productName + ": All batches expired → skip forgotten");
                return;
            }
        } else {
            // No batches info → nothing to do
            return;
        }

        Date lastUsedTime = document.getDate("lastUsed"); // Firestore Timestamp/Date
        if (lastUsedTime == null) return;

        long todayMidnight    = atLocalMidnight(System.currentTimeMillis());
        long lastUsedMidnight = atLocalMidnight(lastUsedTime.getTime());
        long daysUnused       = TimeUnit.MILLISECONDS.toDays(todayMidnight - lastUsedMidnight);

        boolean showThisDay = (daysUnused >= 15) && (daysUnused % 15 == 0);
        if (!showThisDay) {
            Log.d(TAG, productName + ": Forgotten → NOT included (daysUnused=" + daysUnused + ")");
            return;
        }

        SharedPreferences lastNotifPrefs = context.getSharedPreferences("forgotten_notification_dates", Context.MODE_PRIVATE);
        long lastNotifDay = lastNotifPrefs.getLong(productId + "_day", 0L);
        if (lastNotifDay == 0L) {
            long legacy = lastNotifPrefs.getLong(productId, 0L);
            if (legacy > 0L) {
                lastNotifDay = atLocalMidnight(legacy);
                lastNotifPrefs.edit().putLong(productId + "_day", lastNotifDay).apply();
            }
        }

        boolean notifiedToday = (lastNotifDay == todayMidnight);

        forgottenItems.add(productName);
        Log.d(TAG, productName + ": Forgotten → INCLUDED (daysUnused=" + daysUnused + ", modulo=0)");

        if (recordThrottle && !notifiedToday) {
            lastNotifPrefs.edit().putLong(productId + "_day", todayMidnight).apply();
            Log.d(TAG, productName + ": Forgotten → first trigger today (stamp day).");
        }
    }

    private long atLocalMidnight(long timeMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(timeMs);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @SuppressLint("MissingPermission")
    private void sendGroupedNotification(Context context,
                                         boolean fromSnooze,
                                         String snoozeType,
                                         boolean uiOnly,
                                         boolean watchdogAuto) {
        boolean hasExpired   = !expiredItems.isEmpty();
        boolean hasLow       = !lowConsumptionItems.isEmpty();
        boolean hasForgotten = !forgottenItems.isEmpty();

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        // ⬇️ Clear stale lists if date rolled over
        SharedPreferences itemsPrefs = context.getSharedPreferences("attention_items", Context.MODE_PRIVATE);
        String lastDay = itemsPrefs.getString("attention_items_date", "");
        if (!today.equals(lastDay)) {
            itemsPrefs.edit()
                    .remove("expired")
                    .remove("low")
                    .remove("forgotten")
                    .apply();
        }

        itemsPrefs.edit()
                .putString("expired",   TextUtils.join(",", expiredItems))
                .putString("low",       TextUtils.join(",", lowConsumptionItems))
                .putString("forgotten", TextUtils.join(",", forgottenItems))
                .putString("attention_items_date", today)
                .apply();

        if (uiOnly) {
            Log.i(TAG, "UI-only refresh → lists updated without posting a notification");
            return;
        }
        if (!hasExpired && !hasLow && !hasForgotten) return;

        SharedPreferences seenPrefs = context.getSharedPreferences(PREF_SEEN, Context.MODE_PRIVATE);
        if (!fromSnooze && today.equals(seenPrefs.getString("last_seen_date", ""))) {
            Log.d(TAG, "Seen guard (auto) → skip");
            return;
        }

        SharedPreferences snoozePrefs = context.getSharedPreferences(PREF_SNOOZE, Context.MODE_PRIVATE);
        boolean snoozed = snoozePrefs.getBoolean("is_snoozed", false);
        if (!fromSnooze && !watchdogAuto && snoozed) {
            Log.d(TAG, "⏸ Snooze guard active (auto path) → skip");
            return;
        }

        SharedPreferences notifyPrefs = context.getSharedPreferences("notify_state", Context.MODE_PRIVATE);
        String lastNotifyDate = notifyPrefs.getString(PREF_LAST_NOTIFY_DATE, "");
        long lastNotifyTime   = notifyPrefs.getLong("last_notify_time", 0);

        SharedPreferences countPrefs = context.getSharedPreferences(PREF_NOTIFICATION_COUNT, Context.MODE_PRIVATE);
        int todayCount = countPrefs.getInt(today, 0);

        SharedPreferences userPrefs = context.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE);
        int userLimit = userPrefs.getInt("maxNotificationsPerDay", 3);

        long now = System.currentTimeMillis();

        if (!fromSnooze && !watchdogAuto && today.equals(lastNotifyDate)) {
            if (todayCount >= userLimit) {
                Log.d(TAG, "Reached daily notification limit (" + userLimit + ") → skip");
                return;
            }
            long elapsed = now - lastNotifyTime;
            if (elapsed < AUTO_RESEND_INTERVAL) {
                Log.d(TAG, "⏸ Auto cooldown active (" + (elapsed/60000) + " min since last) → skip");
                return;
            }
        }

        boolean shown = createGroupedNotification(context, hasExpired, hasLow, hasForgotten, fromSnooze, watchdogAuto);

        if (shown) {
            if (fromSnooze && !watchdogAuto) {
                Log.i(TAG, "Snooze notification sent");

                int delivered = snoozePrefs.getInt("snooze_retry_count", -1);
                int newDelivered = (delivered < 0) ? 0 : Math.min(delivered + 1, MAX_SNOOZE_RETRIES);

                long nextAt = (newDelivered < MAX_SNOOZE_RETRIES)
                        ? (now + SNOOZE_RETRY_INTERVAL)
                        : 0L;

                snoozePrefs.edit()
                        .putBoolean("is_snoozed", true)
                        .putString("snooze_date", today)
                        .putLong("snooze_next_at", nextAt)
                        .putInt("snooze_retry_count", newDelivered)
                        .apply();

                if (nextAt > 0) {
                    scheduleAlarm(context, "group", nextAt, true);
                }

            } else {
                notifyPrefs.edit()
                        .putString(PREF_LAST_NOTIFY_DATE, today)
                        .putLong("last_notify_time", now)
                        .apply();

                todayCount++;
                countPrefs.edit().putInt(today, todayCount).apply();

                snoozePrefs.edit()
                        .putBoolean("is_snoozed", false)
                        .putString("snooze_date", "")
                        .putLong("snooze_next_at", 0L)
                        .putInt("snooze_retry_count", -1)
                        .apply();

                Log.i(TAG, (watchdogAuto ? "Watchdog(auto) sent" : "Auto notification sent")
                        + " (" + todayCount + " times today)");
            }
        }

        if (!fromSnooze) {
            if (hasExpired)   scheduleSnoozeForType(context, "expired", false);
            if (hasLow)       scheduleSnoozeForType(context, "low_consumption", false);
            if (hasForgotten) scheduleSnoozeForType(context, "forgotten", false);
        }
    }

    @SuppressLint("MissingPermission")
    private boolean createGroupedNotification(Context context,
                                              boolean hasExpired,
                                              boolean hasLow,
                                              boolean hasForgotten,
                                              boolean fromSnooze,
                                              boolean watchdogAuto) {

        // Short, collapsed line
        StringBuilder contentBuilder = new StringBuilder();
        if (hasExpired)   contentBuilder.append("Items Expired");
        if (hasLow)       { if (contentBuilder.length() > 0) contentBuilder.append(", "); contentBuilder.append("Low Consumption Items"); }
        if (hasForgotten) { if (contentBuilder.length() > 0) contentBuilder.append(", "); contentBuilder.append("Forgotten Items"); }

        String title   = "Items Need Your Attention!";
        String content = contentBuilder.toString();

        if (fromSnooze && !watchdogAuto) {
            SharedPreferences snoozePrefs = context.getSharedPreferences(PREF_SNOOZE, Context.MODE_PRIVATE);
            int delivered = snoozePrefs.getInt("snooze_retry_count", -1);
            boolean isLastSnooze = delivered >= (MAX_SNOOZE_RETRIES - 1);
            String prefix = isLastSnooze ? "Last snooze reminder" : "Snooze reminder";
            content = prefix + ": " + content;
        }

        // Build BigText
        StringBuilder big = new StringBuilder();
        StringBuilder typesCSV = new StringBuilder();
        int sections = 0;

        if (hasExpired) {
            sections++;
            big.append("⚠️ Expired Items:\n");
            for (String item : expiredItems) big.append("• ").append(item).append("\n");
            typesCSV.append("expired");
        }
        if (hasLow) {
            if (sections > 0) big.append("\n");
            sections++;
            big.append("📉 Low Consumption Items:\n");
            for (String item : lowConsumptionItems) big.append("• ").append(item).append("\n");
            if (typesCSV.length() > 0) typesCSV.append(",");
            typesCSV.append("low_consumption");
        }
        if (hasForgotten) {
            if (sections > 0) big.append("\n");
            sections++;
            big.append("🗂️ Forgotten Items:\n");
            for (String item : forgottenItems) big.append("• ").append(item).append("\n");
            if (typesCSV.length() > 0) typesCSV.append(",");
            typesCSV.append("forgotten");
        }

        String bigTextStr = big.toString().trim();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app2)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigTextStr))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER);

        if (sections >= 2) {
            builder.setGroup(NOTIFICATION_GROUP).setGroupSummary(true);
        }

        // Tap → open Notification Center
        Intent openIntent = new Intent(context, MainActivity.class)
                .putExtra("open_notifications", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent contentPI = PendingIntent.getActivity(
                context,
                SUMMARY_NOTIFICATION_ID + 100,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        builder.setContentIntent(contentPI);

        String allTypes = typesCSV.toString();

        // Action: Seen
        Intent markIntent = new Intent(context, NotificationService.class)
                .setAction(ACTION_MARK_SEEN)
                .putExtra("notificationType", allTypes);
        PendingIntent markPendingIntent = PendingIntent.getBroadcast(
                context, SUMMARY_NOTIFICATION_ID, markIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(R.drawable.ic_check, "Seen", markPendingIntent);

        // Action: Snooze
        Intent snoozeIntent = new Intent(context, SnoozeOptionsActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("notificationType", allTypes)
                .putExtra("notificationId", SUMMARY_NOTIFICATION_ID);

        ArrayList<String> allItems = new ArrayList<>();
        allItems.addAll(expiredItems);
        allItems.addAll(lowConsumptionItems);
        allItems.addAll(forgottenItems);
        snoozeIntent.putStringArrayListExtra("items", allItems);

        PendingIntent snoozePendingIntent = PendingIntent.getActivity(
                context, SUMMARY_NOTIFICATION_ID + 5000, snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(R.drawable.ic_time, "Snooze", snoozePendingIntent);

        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, builder.build());
            return true;
        }
        return false;
    }

    private void scheduleSnoozeForType(Context context, String type, boolean fromSnooze) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_SNOOZE_RETRIES, Context.MODE_PRIVATE);
        SharedPreferences snoozePrefs = context.getSharedPreferences(PREF_SNOOZE, Context.MODE_PRIVATE);

        if (fromSnooze) {
            int retry = prefs.getInt(type, 0);
            if (retry < MAX_SNOOZE_RETRIES) {
                long when = System.currentTimeMillis() + SNOOZE_RETRY_INTERVAL;
                scheduleAlarm(context, type, when, true);

                prefs.edit().putInt(type, retry + 1).apply();

                snoozePrefs.edit()
                        .putLong("snooze_next_at", when)
                        .apply();

                Log.i(TAG, "Snooze-retry scheduled (next will be retry " + (retry + 1) + "/" + MAX_SNOOZE_RETRIES + ")");
            } else {
                snoozePrefs.edit()
                        .putInt("snooze_retry_count", MAX_SNOOZE_RETRIES)
                        .putLong("snooze_next_at", 0L)
                        .apply();

                Log.i(TAG, "Snooze retries exhausted for type=" + type);
            }
        } else {
            long when = System.currentTimeMillis() + AUTO_RESEND_INTERVAL;
            scheduleAlarm(context, type, when, false);

            snoozePrefs.edit()
                    .putLong("snooze_next_at", when)
                    .apply();

            Log.i(TAG, "Auto-resend scheduled for type=" + type + " at " + new Date(when));
        }
    }

    public static void scheduleAlarm(Context ctx, String type, long at, boolean fromSnooze) {
        Intent i = new Intent(ctx, NotificationService.class)
                .setAction(fromSnooze ? ACTION_CHECK_RETRY : ACTION_CHECK)
                .putExtra("type", type).putExtra("fromSnooze", fromSnooze);
        int req = (type + "_" + (fromSnooze ? "retry" : "auto")).hashCode();
        PendingIntent pi = PendingIntent.getBroadcast(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        boolean canExact = true;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            canExact = am.canScheduleExactAlarms();
        }

        if (canExact) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } else {
            Intent show = new Intent(ctx, MainActivity.class)
                    .setAction(fromSnooze ? "SHOW_SNOOZE_ALARM" : "SHOW_ALARM")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent showPi = PendingIntent.getActivity(
                    ctx, (type + "_show").hashCode(), show,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), pi);
        }
    }

    private void cancelResendAndSnooze(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        String[] types = {"expired", "low_consumption", "forgotten"};
        boolean[] paths = {false, true}; // false=auto-resend, true=retry(fromSnooze)

        for (String t : types) {
            for (boolean fromSnooze : paths) {
                Intent i = new Intent(context, NotificationService.class);
                i.setAction(fromSnooze ? ACTION_CHECK_RETRY : ACTION_CHECK);
                i.putExtra("type", t);
                i.putExtra("fromSnooze", fromSnooze);

                int requestCode = (t + "_" + (fromSnooze ? "retry" : "auto")).hashCode();
                PendingIntent pi = PendingIntent.getBroadcast(
                        context, requestCode, i,
                        PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);

                if (pi != null) {
                    am.cancel(pi);
                    pi.cancel();
                    Log.d(TAG, "Canceled alarm for type=" + t + ", path=" + (fromSnooze ? "retry" : "auto"));
                }
            }
        }

        Intent gi = new Intent(context, NotificationService.class);
        gi.setAction(ACTION_CHECK_RETRY);
        gi.putExtra("type", TYPE_GROUP);
        gi.putExtra("fromSnooze", true);
        PendingIntent gpi = PendingIntent.getBroadcast(
                context, (TYPE_GROUP + "_retry").hashCode(), gi,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (gpi != null) {
            am.cancel(gpi);
            gpi.cancel();
            Log.d(TAG, "Canceled alarm for snooze group");
        }
    }

    private void markItemSeen(Context context) {
        NotificationManagerCompat.from(context).cancel(SUMMARY_NOTIFICATION_ID);
        cancelResendAndSnooze(context);

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        SharedPreferences seenPrefs = context.getSharedPreferences(PREF_SEEN, Context.MODE_PRIVATE);
        seenPrefs.edit().putString("last_seen_date", today).apply();

        SharedPreferences retryPrefs = context.getSharedPreferences(PREF_SNOOZE_RETRIES, Context.MODE_PRIVATE);
        retryPrefs.edit().putInt("group", 0).apply();

        Log.i(TAG, "User pressed Seen → cleared all retries");
    }

    private void handleSpecificSnooze(Context context, String type, long snoozeMillis) {
        if (snoozeMillis <= 0) {
            Log.w(TAG, "handleSpecificSnooze: invalid duration (<=0). Ignoring.");
            return;
        }

        long triggerTime = System.currentTimeMillis() + snoozeMillis;

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(triggerTime);
        int hourOfDay = cal.get(Calendar.HOUR_OF_DAY);
        if (hourOfDay < 7 || hourOfDay >= 21) {
            Log.w(TAG, "handleSpecificSnooze: end time outside 7–21. Ignoring. target=" + new Date(triggerTime));
            return;
        }
        cancelResendAndSnooze(context);

        SharedPreferences retryPrefs = context.getSharedPreferences(PREF_SNOOZE_RETRIES, Context.MODE_PRIVATE);
        retryPrefs.edit()
                .putInt("expired", 0)
                .putInt("low_consumption", 0)
                .putInt("forgotten", 0)
                .apply();

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        SharedPreferences snoozePrefs = context.getSharedPreferences(PREF_SNOOZE, Context.MODE_PRIVATE);
        snoozePrefs.edit()
                .putBoolean("is_snoozed", true)
                .putString("snooze_date", today)
                .putLong("snooze_next_at", triggerTime)
                .putInt("snooze_retry_count", -1)
                .apply();

        scheduleAlarm(context, TYPE_GROUP, triggerTime, true);
        Log.i(TAG, "Snooze set → next at " + new Date(triggerTime));
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Grocery Notifications",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    public static void scheduleMorningCheck(Context context) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(System.currentTimeMillis());
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.HOUR_OF_DAY, 7);
        cal.set(Calendar.MINUTE, 0);

        if (System.currentTimeMillis() >= cal.getTimeInMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        scheduleAlarm(context, "daily_kick", cal.getTimeInMillis(), false);
        Log.i(TAG, "Scheduled morning check at " + new Date(cal.getTimeInMillis()));

        scheduleWatchdog(context);
    }

    public static void scheduleWatchdog(Context context) {
        long when = System.currentTimeMillis() + WATCHDOG_INTERVAL;

        Intent wd = new Intent(context, NotificationService.class).setAction(ACTION_WATCHDOG);
        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                "watchdog".hashCode(),
                wd,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        boolean canExact = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            canExact = am.canScheduleExactAlarms();
        }

        if (canExact) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, when, pi);
            }
        } else {
            Intent showIntent = new Intent(context, MainActivity.class)
                    .setAction("SHOW_WATCHDOG")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent showPi = PendingIntent.getActivity(
                    context, "watchdog_show".hashCode(), showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, showPi), pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, when, pi);
            }
        }
        Log.d(TAG, "Watchdog scheduled for " + new Date(when));
    }

    private void runWatchdog(Context context, PendingResult pending) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        long now = System.currentTimeMillis();

        SharedPreferences seenPrefs   = context.getSharedPreferences(PREF_SEEN, Context.MODE_PRIVATE);
        SharedPreferences snoozePrefs = context.getSharedPreferences(PREF_SNOOZE, Context.MODE_PRIVATE);
        SharedPreferences notifyPrefs = context.getSharedPreferences("notify_state", Context.MODE_PRIVATE);
        SharedPreferences countPrefs  = context.getSharedPreferences(PREF_NOTIFICATION_COUNT, Context.MODE_PRIVATE);
        SharedPreferences userPrefs   = context.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE);

        boolean seenToday = today.equals(seenPrefs.getString("last_seen_date", ""));
        long nextAt       = snoozePrefs.getLong("snooze_next_at", 0L);
        int  lastRetryCnt = snoozePrefs.getInt("snooze_retry_count", -1);
        boolean isSnoozed = snoozePrefs.getBoolean("is_snoozed", false);

        long lastNotifyTime = notifyPrefs.getLong("last_notify_time", 0L);
        String lastNotifyDate = notifyPrefs.getString(PREF_LAST_NOTIFY_DATE, "");

        int todayCount = countPrefs.getInt(today, 0);
        int userLimit  = userPrefs.getInt("maxNotificationsPerDay", 3);
        boolean hitDailyCap = todayCount >= userLimit;

        boolean overdue = nextAt > 0 && now >= nextAt;
        boolean nothingFiredSince = lastNotifyTime < nextAt || !today.equals(lastNotifyDate);
        boolean atLastSnooze = lastRetryCnt >= MAX_SNOOZE_RETRIES;

        boolean bothHardStop = atLastSnooze && hitDailyCap;
        boolean shouldForce = !seenToday && overdue && nothingFiredSince && !bothHardStop;

        Log.d(TAG, "Watchdog check → seenToday=" + seenToday
                + ", overdue=" + overdue
                + ", nothingFiredSince=" + nothingFiredSince
                + ", atLastSnooze=" + atLastSnooze
                + ", hitDailyCap=" + hitDailyCap
                + ", bothHardStop=" + bothHardStop
                + ", isSnoozed=" + isSnoozed
                + ", nextAt=" + new Date(nextAt));

        if (shouldForce) {
            boolean watchdogAuto = !isSnoozed;
            checkNotificationConditions(
                    context,
                    !watchdogAuto,
                    "watchdog",
                    false,
                    false,
                    watchdogAuto,
                    pending
            );
            return;
        }

        pending.finish();
    }
}
