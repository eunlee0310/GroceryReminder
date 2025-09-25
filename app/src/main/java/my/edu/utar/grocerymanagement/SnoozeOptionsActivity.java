package my.edu.utar.grocerymanagement;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class SnoozeOptionsActivity extends Activity {

    private String notificationType;
    private int notificationId;
    private ArrayList<String> items;

    private static final String PREF_SNOOZE_FREQUENCIES = "snooze_frequencies";
    private static final String PREF_SAVED_SNOOZES = "UserPrefs";

    // Default snoozes (always injected if missing)
    private static final LinkedHashMap<String, String> DEFAULT_SNOOZES = new LinkedHashMap<>();
    static {
        DEFAULT_SNOOZES.put(String.valueOf(TimeUnit.MINUTES.toMillis(15)), "15 min");
        DEFAULT_SNOOZES.put(String.valueOf(TimeUnit.MINUTES.toMillis(30)), "30 min");
        DEFAULT_SNOOZES.put(String.valueOf(TimeUnit.HOURS.toMillis(1)), "1 hr");
    }

    // Allowed window for snooze END time
    private static final int WINDOW_START_HOUR = 7;   // inclusive
    private static final int WINDOW_END_HOUR   = 21;  // exclusive (must end before 21:00)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        notificationType = getIntent().getStringExtra("notificationType");
        notificationId = getIntent().getIntExtra("notificationId", 0);
        items = getIntent().getStringArrayListExtra("items");

        ensureDefaults();
        showTopSnoozeDialog();
    }

    // ================================
    // Main dialog (Top 3 + “Others…”)
    // ================================
    private void showTopSnoozeDialog() {
        Set<String> saved = getSavedSnoozes();
        List<String> filtered = filterShowable(saved); // ✨ filter past/invalid

        // Sort by frequency (desc)
        filtered.sort((a, b) -> {
            String keyA = a.split("\\|")[1];
            String keyB = b.split("\\|")[1];
            return Integer.compare(getSnoozeFrequency(keyB), getSnoozeFrequency(keyA));
        });

        List<String> top3 = filtered.subList(0, Math.min(3, filtered.size()));
        List<String> others = filtered.size() > 3 ? filtered.subList(3, filtered.size()) : Collections.emptyList();

        List<String> displayList = new ArrayList<>();
        List<String> valueList = new ArrayList<>();

        for (String entry : top3) {
            String[] parts = entry.split("\\|", 2);
            displayList.add(parts[0]);
            valueList.add(entry);
        }

        displayList.add("Others…");
        valueList.add("OTHERS");

        new AlertDialog.Builder(this)
                .setTitle("Snooze Options")
                .setItems(displayList.toArray(new String[0]), (dialog, which) -> {
                    String value = valueList.get(which);
                    if (value.equals("OTHERS")) {
                        showOtherSnoozeOptions(others);
                    } else {
                        handleSnoozeSelection(value);
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> finish())
                .show();
    }

    // ================================
    // Others dialog (+ customs)
    // ================================
    private void showOtherSnoozeOptions(List<String> othersFiltered) {
        List<String> displayList = new ArrayList<>();
        List<String> valueList = new ArrayList<>();

        displayList.add("Custom Duration…");
        valueList.add("DURATION_CUSTOM");

        displayList.add("Custom Time…");
        valueList.add("TIME_CUSTOM");

        for (String entry : othersFiltered) {
            String[] parts = entry.split("\\|", 2);
            displayList.add(parts[0]);
            valueList.add(entry);
        }

        new AlertDialog.Builder(this)
                .setTitle("Other Snooze Options")
                .setItems(displayList.toArray(new String[0]), (dialog, which) -> {
                    String value = valueList.get(which);
                    if (value.equals("DURATION_CUSTOM")) {
                        showCustomDurationDialog();
                    } else if (value.equals("TIME_CUSTOM")) {
                        showCustomTimePicker();
                    } else {
                        handleSnoozeSelection(value);
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> finish())
                .show();
    }

    // ================================
    // Custom duration
    // ================================
    private void showCustomDurationDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Snooze Name (e.g., Short Nap)");
        layout.addView(nameInput);

        LinearLayout pickersLayout = new LinearLayout(this);
        pickersLayout.setOrientation(LinearLayout.HORIZONTAL);
        pickersLayout.setGravity(Gravity.CENTER);

        final NumberPicker hourPicker = new NumberPicker(this);
        hourPicker.setMinValue(0);
        hourPicker.setMaxValue(5);

        final NumberPicker minutePicker = new NumberPicker(this);
        final int[] allowedMinutes = {0, 10, 20, 30, 40, 50};
        String[] displayMinutes = {"00", "10", "20", "30", "40", "50"};
        minutePicker.setMinValue(0);
        minutePicker.setMaxValue(allowedMinutes.length - 1);
        minutePicker.setDisplayedValues(displayMinutes);

        pickersLayout.addView(hourPicker, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pickersLayout.addView(minutePicker, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        layout.addView(pickersLayout);

        new AlertDialog.Builder(this)
                .setTitle("Custom Snooze Duration")
                .setView(layout)
                .setPositiveButton("OK", (dialog, which) -> {
                    String userTitle = nameInput.getText().toString().trim();
                    int hours = hourPicker.getValue();
                    int minutes = allowedMinutes[minutePicker.getValue()];

                    long durationMillis = TimeUnit.HOURS.toMillis(hours) + TimeUnit.MINUTES.toMillis(minutes);
                    if (durationMillis <= 0) {
                        Toast.makeText(this, "Duration must be > 0", Toast.LENGTH_SHORT).show();
                        showCustomDurationDialog();
                        return;
                    }

                    long end = System.currentTimeMillis() + durationMillis;
                    if (!endsWithinWindow(end)) {
                        new AlertDialog.Builder(this)
                                .setTitle("Invalid Snooze End")
                                .setMessage("Snooze must end between 7:00 AM and 9:00 PM.")
                                .setPositiveButton("OK", (d, w) -> showCustomDurationDialog())
                                .show();
                        return;
                    }

                    String finalTitle = userTitle.isEmpty() ? formatDuration(durationMillis) : userTitle;
                    saveNewSnooze(finalTitle, String.valueOf(durationMillis));
                    handleSnoozeSelection(finalTitle + "|" + durationMillis);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ================================
    // Custom absolute time
    // ================================
    private void showCustomTimePicker() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Snooze Name (e.g., Dinner)");
        layout.addView(nameInput);

        Calendar now = Calendar.getInstance();
        TimePickerDialog tpd = new TimePickerDialog(this,
                (view, hour, minute) -> {
                    // Validate window first
                    if (hour < WINDOW_START_HOUR || hour >= WINDOW_END_HOUR) {
                        new AlertDialog.Builder(this)
                                .setTitle("Invalid Snooze Time")
                                .setMessage("Please select a time between 7:00 AM and 9:00 PM.")
                                .setPositiveButton("OK", (d, w) -> showCustomTimePicker())
                                .show();
                        return;
                    }

                    long targetToday = getTodayMillisForTime(hour, minute);
                    if (targetToday <= System.currentTimeMillis()) {
                        new AlertDialog.Builder(this)
                                .setTitle("Time Already Passed")
                                .setMessage("Please select a time later today.")
                                .setPositiveButton("OK", (d, w) -> showCustomTimePicker())
                                .show();
                        return;
                    }

                    String timeLabel = String.format(Locale.getDefault(), "%02d:%02d", hour, minute);
                    String finalTitle = nameInput.getText().toString().trim();
                    if (finalTitle.isEmpty()) finalTitle = timeLabel;

                    saveNewSnooze(finalTitle, timeLabel);
                    handleSnoozeSelection(finalTitle + "|" + timeLabel);
                },
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE),
                true);

        tpd.show();
    }

    // ================================
    // Handle any snooze selection
    // ================================
    private void handleSnoozeSelection(String entry) {
        String[] parts = entry.split("\\|", 2);
        String key = parts[1];
        incrementSnoozeFrequency(key);

        if (key.contains(":")) {
            // Absolute time like "13:30" — validate again (race-safe)
            String[] hm = key.split(":");
            int h = Integer.parseInt(hm[0]);
            int m = Integer.parseInt(hm[1]);

            // Window check
            if (h < WINDOW_START_HOUR || h >= WINDOW_END_HOUR) {
                new AlertDialog.Builder(this)
                        .setTitle("Invalid Snooze Time")
                        .setMessage("Please select a time between 7:00 AM and 9:00 PM.")
                        .setPositiveButton("OK", (d, w) -> showTopSnoozeDialog())
                        .show();
                return;
            }

            long targetToday = getTodayMillisForTime(h, m);
            if (targetToday <= System.currentTimeMillis()) {
                new AlertDialog.Builder(this)
                        .setTitle("Time Already Passed")
                        .setMessage("Please select a time later today.")
                        .setPositiveButton("OK", (d, w) -> showTopSnoozeDialog())
                        .show();
                return;
            }

            // Schedule by absolute time → convert to duration
            long duration = targetToday - System.currentTimeMillis();
            snoozeByDuration(duration);

        } else {
            // Duration in millis
            long durationMillis = Long.parseLong(key);
            long end = System.currentTimeMillis() + durationMillis;
            if (!endsWithinWindow(end)) {
                new AlertDialog.Builder(this)
                        .setTitle("Invalid Snooze End")
                        .setMessage("Snooze must end between 7:00 AM and 9:00 PM.")
                        .setPositiveButton("OK", (d, w) -> showTopSnoozeDialog())
                        .show();
                return;
            }
            snoozeByDuration(durationMillis);
        }
    }

    // ================================
    // Scheduling helpers
    // ================================
    private void snoozeByDuration(long durationMillis) {
        // Validated already; just send to NotificationService
        NotificationManagerCompat.from(this).cancel(notificationId);

        Intent serviceIntent = new Intent(this, NotificationService.class);
        serviceIntent.setAction("my.edu.utar.grocerymanagement.SNOOZE_TO_TIME");
        serviceIntent.putExtra("notificationType", notificationType);
        serviceIntent.putExtra("notificationId", notificationId);
        serviceIntent.putStringArrayListExtra("items", items);
        serviceIntent.putExtra("snoozeTimeMillis", durationMillis);
        sendBroadcast(serviceIntent);

        finish();
    }

    // ================================
    // Persistence + defaults
    // ================================
    private void ensureDefaults() {
        SharedPreferences prefs = getSharedPreferences(PREF_SAVED_SNOOZES, MODE_PRIVATE);
        Set<String> saved = new HashSet<>(prefs.getStringSet("savedSnoozeTimes", new HashSet<>()));
        boolean changed = false;

        for (Map.Entry<String, String> def : DEFAULT_SNOOZES.entrySet()) {
            boolean exists = false;
            for (String entry : saved) {
                if (entry.split("\\|", 2)[1].equals(def.getKey())) {
                    exists = true; break;
                }
            }
            if (!exists) {
                saved.add(def.getValue() + "|" + def.getKey());
                changed = true;
            }
        }

        if (changed) prefs.edit().putStringSet("savedSnoozeTimes", saved).apply();
    }

    private Set<String> getSavedSnoozes() {
        SharedPreferences prefs = getSharedPreferences(PREF_SAVED_SNOOZES, MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet("savedSnoozeTimes", new HashSet<>()));
    }

    private void saveNewSnooze(String title, String value) {
        SharedPreferences prefs = getSharedPreferences(PREF_SAVED_SNOOZES, MODE_PRIVATE);
        Set<String> saved = new HashSet<>(prefs.getStringSet("savedSnoozeTimes", new HashSet<>()));

        for (String entry : saved) {
            String[] parts = entry.split("\\|", 2);
            String existingTitle = parts[0];
            String existingValue = parts[1];

            if (existingTitle.equalsIgnoreCase(title) || existingValue.equals(value)) {
                Toast.makeText(this, "Using existing snooze: " + title, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        saved.add(title + "|" + value);
        prefs.edit().putStringSet("savedSnoozeTimes", saved).apply();
    }

    private void incrementSnoozeFrequency(String key) {
        SharedPreferences prefs = getSharedPreferences(PREF_SNOOZE_FREQUENCIES, MODE_PRIVATE);
        int current = prefs.getInt(key, 0);
        prefs.edit().putInt(key, current + 1).apply();
    }

    private int getSnoozeFrequency(String key) {
        SharedPreferences prefs = getSharedPreferences(PREF_SNOOZE_FREQUENCIES, MODE_PRIVATE);
        return prefs.getInt(key, 0);
    }

    // ================================
    // Filtering + time helpers
    // ================================
    private List<String> filterShowable(Set<String> saved) {
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>();

        for (String entry : saved) {
            String[] parts = entry.split("\\|", 2);
            if (parts.length < 2) continue;
            String key = parts[1];

            if (key.contains(":")) {
                // Absolute time "HH:mm"
                String[] hm = key.split(":");
                if (hm.length != 2) continue;
                int h, m;
                try {
                    h = Integer.parseInt(hm[0]);
                    m = Integer.parseInt(hm[1]);
                } catch (Exception e) { continue; }

                long targetToday = getTodayMillisForTime(h, m);
                if (targetToday <= now) continue;                 // past → hide
                if (!endsWithinWindow(targetToday)) continue;      // outside window → hide

                out.add(entry);
            } else {
                // Duration in millis
                long duration;
                try { duration = Long.parseLong(key); }
                catch (Exception e) { continue; }

                long end = now + duration;
                if (!endsWithinWindow(end)) continue;              // would end outside window → hide

                out.add(entry);
            }
        }
        return out;
    }

    private boolean endsWithinWindow(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        int h = c.get(Calendar.HOUR_OF_DAY);
        return h >= WINDOW_START_HOUR && h < WINDOW_END_HOUR;
    }

    private long getTodayMillisForTime(int hour, int minute) {
        Calendar target = Calendar.getInstance();
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        return target.getTimeInMillis();
    }

    private String formatDuration(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        if (hours > 0 && minutes > 0) return hours + " hrs " + minutes + " min";
        else if (hours > 0) return hours + " hrs";
        else return minutes + " min";
    }
}
