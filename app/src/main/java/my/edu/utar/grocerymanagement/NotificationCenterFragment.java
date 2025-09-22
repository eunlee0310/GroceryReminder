package my.edu.utar.grocerymanagement;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NotificationCenterFragment extends Fragment {

    private static final String PREF_SEEN   = "seen_notifications";
    private static final String PREF_SNOOZE = "active_snooze";
    private static final String PREF_NOTIFY = "notify_state";
    private static final String PREF_COUNT  = "notification_count";
    private static final String PREF_ITEMS  = "attention_items";

    private Switch switchSeen;
    private TextView tvSituation, tvNextNotify;
    private Button btnSnooze;

    private TextView tvExpiredItems, tvLowItems, tvForgottenItems;

    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    // ===== ADDED: listen for changes in attention_items so UI updates immediately =====
    private SharedPreferences itemsPrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener itemsChanged =
            (sp, key) -> {
                if (!isAdded()) return;
                if (key == null || "expired".equals(key) || "low".equals(key) || "forgotten".equals(key)) {
                    refreshUI(); // refresh when NotificationService writes new lists
                }
            };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inf.inflate(R.layout.activity_notification_center, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        switchSeen     = v.findViewById(R.id.switchSeen);
        tvSituation    = v.findViewById(R.id.tvSituation);
        tvNextNotify   = v.findViewById(R.id.tvNextNotify);
        btnSnooze      = v.findViewById(R.id.btnSnooze);

        tvExpiredItems   = v.findViewById(R.id.tvExpiredItems);
        tvLowItems       = v.findViewById(R.id.tvLowItems);
        tvForgottenItems = v.findViewById(R.id.tvForgottenItems);

        // Enable links
        tvExpiredItems.setMovementMethod(LinkMovementMethod.getInstance());
        tvLowItems.setMovementMethod(LinkMovementMethod.getInstance());
        tvForgottenItems.setMovementMethod(LinkMovementMethod.getInstance());

        // ADDED: get prefs once so we can register the listener in onStart
        itemsPrefs = requireContext().getSharedPreferences(PREF_ITEMS, 0);

        switchSeen.setOnCheckedChangeListener((b, isChecked) -> {
            if (b.isPressed()) handleSeenToggle(isChecked);
        });

        btnSnooze.setOnClickListener(_v -> {
            Intent snoozeIntent = new Intent(requireContext(), SnoozeOptionsActivity.class);
            snoozeIntent.putExtra("notificationType", "all");
            snoozeIntent.putExtra("notificationId", 9999);
            startActivity(snoozeIntent);
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        // ADDED: start listening for attention_items updates
        if (itemsPrefs != null) {
            itemsPrefs.registerOnSharedPreferenceChangeListener(itemsChanged);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        // ADDED: avoid leaks
        if (itemsPrefs != null) {
            itemsPrefs.unregisterOnSharedPreferenceChangeListener(itemsChanged);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Kick a recompute (async); UI will refresh again when prefs change.
        Intent i = new Intent(requireContext(), NotificationService.class);
        i.setAction("my.edu.utar.grocerymanagement.CHECK_NOTIFICATIONS");
        i.putExtra("force", true);   // bypass time/screen/daily-limit guards
        i.putExtra("uiOnly", true);  // recompute & persist lists, but DO NOT show a notification
        requireContext().sendBroadcast(i);

        // Initial draw from current prefs (may be "yesterday" for a moment at 00:00)
        refreshUI();
    }

    private void refreshUI() {
        String today = dateFmt.format(new Date());

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean outOfWindow = (hour < 7 || hour >= 21);

        SharedPreferences seenPrefs   = requireContext().getSharedPreferences(PREF_SEEN, 0);
        SharedPreferences snoozePrefs = requireContext().getSharedPreferences(PREF_SNOOZE, 0);
        SharedPreferences notifyPrefs = requireContext().getSharedPreferences(PREF_NOTIFY, 0);
        SharedPreferences countPrefs  = requireContext().getSharedPreferences(PREF_COUNT, 0);
        SharedPreferences userPrefs   = requireContext().getSharedPreferences("UserPrefs", 0);
        SharedPreferences itemsPrefs  = requireContext().getSharedPreferences(PREF_ITEMS, 0);

        boolean seenToday = today.equals(seenPrefs.getString("last_seen_date", ""));
        boolean isSnoozedToday = snoozePrefs.getBoolean("is_snoozed", false)
                && today.equals(snoozePrefs.getString("snooze_date", ""));

        int userLimit  = userPrefs.getInt("maxNotificationsPerDay", 5);
        int todayCount = countPrefs.getInt(today, 0);
        boolean limit  = todayCount >= userLimit;

        switchSeen.setChecked(seenToday);

        String situation;
        String nextStr;

        if (outOfWindow) {
            situation = "Quiet hours (9pm - 7am)";
            nextStr = "—";
            btnSnooze.setEnabled(false);
        } else if (seenToday) {
            situation = "Seen";
            nextStr = "—";
            btnSnooze.setEnabled(false);
        } else if (isSnoozedToday) { // snoozed overrides daily limit
            int retry = snoozePrefs.getInt("snooze_retry_count", -1);
            long nextAt = snoozePrefs.getLong("snooze_next_at", 0L);
            if (retry == -1) {
                situation = "Snoozed";
            } else if (retry < NotificationService.MAX_SNOOZE_RETRIES) {
                situation = "Snoozed (retry: " + retry + "/" + NotificationService.MAX_SNOOZE_RETRIES + ")";
            } else {
                situation = "Snoozed (retry: " +
                        NotificationService.MAX_SNOOZE_RETRIES + "/" + NotificationService.MAX_SNOOZE_RETRIES + ")";
            }
            if (nextAt > 0) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(nextAt);
                int h = c.get(Calendar.HOUR_OF_DAY);
                nextStr = (h >= 7 && h < 21) ? timeFmt.format(new Date(nextAt)) : "—";
            } else {
                nextStr = "—";
            }
            btnSnooze.setEnabled(true);
        } else if (limit) {
            situation = "Max notification limit reached";
            nextStr = "—";
            btnSnooze.setEnabled(true);
        } else {
            situation = "2 hrs Auto Retry (" + todayCount + "/" + userLimit + ")";
            long lastNotify = notifyPrefs.getLong("last_notify_time", 0L);
            long nextAt = (lastNotify > 0) ? (lastNotify + NotificationService.AUTO_RESEND_INTERVAL) : 0L;
            if (nextAt > 0) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(nextAt);
                int h = c.get(Calendar.HOUR_OF_DAY);
                nextStr = (h >= 7 && h < 21) ? timeFmt.format(new Date(nextAt)) : "—";
            } else {
                nextStr = "—";
            }
            btnSnooze.setEnabled(true);
        }

        tvSituation.setText(situation);
        tvNextNotify.setText(nextStr);

        // Lists
        String expiredCSV   = itemsPrefs.getString("expired", "");
        String lowCSV       = itemsPrefs.getString("low", "");
        String forgottenCSV = itemsPrefs.getString("forgotten", "");
        setClickableItemList(tvExpiredItems,   expiredCSV);
        setClickableItemList(tvLowItems,       lowCSV);
        setClickableItemList(tvForgottenItems, forgottenCSV);
    }

    /**
     * Renders a bullet list in a TextView where each item is clickable.
     * CSV source is from SharedPreferences ("name1,name2,...").
     */
    private void setClickableItemList(TextView tv, String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            tv.setText("—");
            return;
        }

        String[] names = csv.split(",");
        SpannableStringBuilder ssb = new SpannableStringBuilder();

        for (int i = 0; i < names.length; i++) {
            final String name = names[i].trim();
            if (name.isEmpty()) continue;

            int start = ssb.length();
            ssb.append("• ").append(name);
            int end = ssb.length();

            ssb.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    openProductByName(name);
                }
            }, start + 2, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            if (i < names.length - 1) ssb.append("\n");
        }

        tv.setText(ssb);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
    }

    /**
     * Finds a product by name in the user's "grocery_items" and opens AddItemActivity for that item.
     * If multiple matches exist, shows a chooser dialog.
     */
    private void openProductByName(String productName) {
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid()
                : null;

        if (uid == null) {
            Toast.makeText(requireContext(), "Please sign in first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (productName == null || productName.trim().isEmpty()) {
            Toast.makeText(requireContext(), "Invalid product name.", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("users").document(uid)
                .collection("grocery_items")
                .whereEqualTo("name", productName)
                .limit(10)
                .get()
                .addOnSuccessListener(snap -> {
                    List<DocumentSnapshot> docs = snap.getDocuments();
                    if (docs.isEmpty()) {
                        Toast.makeText(requireContext(), "Product not found: " + productName, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (docs.size() == 1) {
                        launchProductDetail(docs.get(0).getId());
                        return;
                    }

                    List<String> labels = new ArrayList<>();
                    for (DocumentSnapshot d : docs) {
                        String category = safeStr(d.getString("category"));
                        String barcode  = safeStr(d.getString("barcode"));
                        String label = productName +
                                (category.isEmpty() ? "" : " • " + category) +
                                (barcode.isEmpty()  ? "" : " • " + barcode);
                        labels.add(label);
                    }

                    new AlertDialog.Builder(requireContext())
                            .setTitle("Open \"" + productName + "\"")
                            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                                String productId = docs.get(which).getId();
                                launchProductDetail(productId);
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(requireContext(), "Open failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void launchProductDetail(String productId) {
        Intent i = new Intent(requireContext(), ProductDetail.class);
        i.putExtra("productId", productId);
        startActivity(i);
    }

    private static String safeStr(String s) { return s == null ? "" : s.trim(); }

    private void handleSeenToggle(boolean seenOn) {
        String today = dateFmt.format(new Date());
        SharedPreferences seenPrefs = requireContext().getSharedPreferences(PREF_SEEN, 0);

        if (seenOn) {
            seenPrefs.edit().putString("last_seen_date", today).apply();
            Intent mark = new Intent(requireContext(), NotificationService.class);
            mark.setAction("my.edu.utar.grocerymanagement.MARK_SEEN");
            requireContext().sendBroadcast(mark);
            Toast.makeText(requireContext(), "Marked as Seen", Toast.LENGTH_SHORT).show();
        } else {
            seenPrefs.edit().remove("last_seen_date").apply();
            Intent check = new Intent(requireContext(), NotificationService.class);
            check.setAction("my.edu.utar.grocerymanagement.CHECK_NOTIFICATIONS");
            requireContext().sendBroadcast(check);
            Toast.makeText(requireContext(), "Seen cleared", Toast.LENGTH_SHORT).show();
        }
        refreshUI();
    }
}
