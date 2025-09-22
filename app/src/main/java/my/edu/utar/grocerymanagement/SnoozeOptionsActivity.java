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

import java.util.*;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        notificationType = getIntent().getStringExtra("notificationType");
        notificationId = getIntent().getIntExtra("notificationId", 0);
        items = getIntent().getStringArrayListExtra("items");

        ensureDefaults();
        showTopSnoozeDialog();
    }

    /** --------------------
     *  MAIN TOP 3 + OTHERS
     *  ------------------- */
    private void showTopSnoozeDialog() {
        Set<String> saved = getSavedSnoozes();

        // Sort by frequency
        List<String> sorted = new ArrayList<>(saved);
        sorted.sort((a, b) -> {
            String keyA = a.split("\\|")[1];
            String keyB = b.split("\\|")[1];
            return Integer.compare(getSnoozeFrequency(keyB), getSnoozeFrequency(keyA));
        });

        List<String> top3 = sorted.subList(0, Math.min(3, sorted.size()));
        List<String> others = sorted.size() > 3 ? sorted.subList(3, sorted.size()) : Collections.emptyList();

        List<String> displayList = new ArrayList<>();
        List<String> valueList = new ArrayList<>();

        for (String entry : top3) {
            String[] parts = entry.split("\\|");
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

    /** --------------------
     *  OTHERS LIST
     *  ------------------- */
    private void showOtherSnoozeOptions(List<String> others) {
        List<String> displayList = new ArrayList<>();
        List<String> valueList = new ArrayList<>();

        displayList.add("Custom Duration…");
        valueList.add("DURATION_CUSTOM");

        displayList.add("Custom Time…");
        valueList.add("TIME_CUSTOM");

        for (String entry : others) {
            String[] parts = entry.split("\\|");
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

    /** --------------------
     *  CUSTOM PICKERS
     *  ------------------- */
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

                    if (hours == 0 && minutes == 0) {
                        Toast.makeText(this, "Duration must be > 0", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    long durationMillis = TimeUnit.HOURS.toMillis(hours) + TimeUnit.MINUTES.toMillis(minutes);
                    String finalTitle = userTitle.isEmpty() ? formatDuration(durationMillis) : userTitle;

                    saveNewSnooze(finalTitle, String.valueOf(durationMillis));
                    handleSnoozeSelection(finalTitle + "|" + durationMillis);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

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
                    // ⚠️ Validate chosen time
                    if (hour < 7 || hour >= 21) {
                        new AlertDialog.Builder(this)
                                .setTitle("Invalid Snooze Time")
                                .setMessage("Please select a time between 7:00 AM and 9:00 PM.")
                                .setPositiveButton("OK", (d, w) -> showCustomTimePicker()) // reopen time picker
                                .show();
                        return;
                    }

                    String time = String.format(Locale.getDefault(), "%02d:%02d", hour, minute);
                    String finalTitle = nameInput.getText().toString().trim();
                    if (finalTitle.isEmpty()) finalTitle = time;

                    saveNewSnooze(finalTitle, time);
                    handleSnoozeSelection(finalTitle + "|" + time);
                },
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE),
                true);

        tpd.show();
    }

    /** --------------------
     *  HELPERS
     *  ------------------- */
    private void handleSnoozeSelection(String entry) {
        String[] parts = entry.split("\\|");
        String key = parts[1];
        incrementSnoozeFrequency(key);

        if (key.contains(":")) {
            long targetMillis = getMillisForTime(key);
            snoozeByAbsoluteTime(targetMillis);
        } else {
            long durationMillis = Long.parseLong(key);
            snoozeByDuration(durationMillis);
        }
    }

    private void saveNewSnooze(String title, String value) {
        SharedPreferences prefs = getSharedPreferences(PREF_SAVED_SNOOZES, MODE_PRIVATE);
        Set<String> saved = new HashSet<>(prefs.getStringSet("savedSnoozeTimes", new HashSet<>()));

        for (String entry : saved) {
            String[] parts = entry.split("\\|");
            String existingTitle = parts[0];
            String existingValue = parts[1];

            if (existingTitle.equalsIgnoreCase(title) || existingValue.equals(value)) {
                // ✅ Already exists → just use it, don’t save duplicate
                // (optional) show quick feedback
                Toast.makeText(this, "Using existing snooze: " + title, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // ✅ Only save if it’s new
        saved.add(title + "|" + value);
        prefs.edit().putStringSet("savedSnoozeTimes", saved).apply();
    }



    private void ensureDefaults() {
        SharedPreferences prefs = getSharedPreferences(PREF_SAVED_SNOOZES, MODE_PRIVATE);
        Set<String> saved = new HashSet<>(prefs.getStringSet("savedSnoozeTimes", new HashSet<>()));
        boolean changed = false;

        for (Map.Entry<String, String> def : DEFAULT_SNOOZES.entrySet()) {
            boolean exists = false;
            for (String entry : saved) {
                if (entry.split("\\|")[1].equals(def.getKey())) {
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

    private void snoozeByDuration(long durationMillis) {
        long triggerTime = System.currentTimeMillis() + durationMillis;

        // ⏰ Check if snooze end time is valid (7:00–21:00)
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(triggerTime);
        int hour = cal.get(Calendar.HOUR_OF_DAY);

        if (hour < 7 || hour >= 21) {
            new AlertDialog.Builder(this)
                    .setTitle("Invalid Snooze Time")
                    .setMessage("Snooze must end between 7:00 AM and 9:00 PM.")
                    .setPositiveButton("OK", (d, w) -> showTopSnoozeDialog()) // reopen snooze options
                    .show();
            return;
        }


        // ✅ Proceed if valid
        NotificationManagerCompat.from(this).cancel(notificationId);

        Intent serviceIntent = new Intent(this, NotificationService.class);
        serviceIntent.setAction("my.edu.utar.grocerymanagement.SNOOZE_TO_TIME");
        serviceIntent.putExtra("notificationType", notificationType);
        serviceIntent.putExtra("notificationId", notificationId);
        serviceIntent.putStringArrayListExtra("items", items);
        serviceIntent.putExtra("snoozeTimeMillis", durationMillis);
        sendBroadcast(serviceIntent);

        // Only close if valid snooze
        finish();
    }

    private void snoozeByAbsoluteTime(long targetMillis) {
        long duration = targetMillis - System.currentTimeMillis();
        if (duration <= 0) duration = 5 * 60 * 1000;
        snoozeByDuration(duration);
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

    private long getMillisForTime(String time) {
        try {
            String[] parts = time.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            Calendar target = Calendar.getInstance();
            target.set(Calendar.HOUR_OF_DAY, hour);
            target.set(Calendar.MINUTE, minute);
            target.set(Calendar.SECOND, 0);
            if (target.getTimeInMillis() <= System.currentTimeMillis()) {
                target.add(Calendar.DAY_OF_MONTH, 1);
            }
            return target.getTimeInMillis();
        } catch (Exception e) {
            return System.currentTimeMillis() + 15 * 60 * 1000;
        }
    }

    private String formatDuration(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        if (hours > 0 && minutes > 0) return hours + " hrs " + minutes + " min";
        else if (hours > 0) return hours + " hrs";
        else return minutes + " min";
    }
}
