package my.edu.utar.grocerymanagement;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class SnoozeSettingActivity extends AppCompatActivity {

    private LinearLayout snoozeContainer;
    private FloatingActionButton btnAddSnooze;
    private SharedPreferences prefs;
    private List<String> snoozeEntries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_snooze_setting);

        prefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        snoozeContainer = findViewById(R.id.snoozeContainer);
        btnAddSnooze = findViewById(R.id.btnAddSnooze);

        // ✅ Ensure defaults exist
        Set<String> saved = new HashSet<>(prefs.getStringSet("savedSnoozeTimes", new HashSet<>()));
        if (saved.isEmpty()) {
            saved.add("15 min|" + TimeUnit.MINUTES.toMillis(15));
            saved.add("30 min|" + TimeUnit.MINUTES.toMillis(30));
            saved.add("1 hr|" + TimeUnit.HOURS.toMillis(1));
            prefs.edit().putStringSet("savedSnoozeTimes", saved).apply();
        }

        snoozeEntries = new ArrayList<>(prefs.getStringSet("savedSnoozeTimes", new HashSet<>()));

        loadRows();

        btnAddSnooze.setOnClickListener(v -> showAddChoiceDialog());
    }

    /** ------------------------
     *  LOAD & DISPLAY
     *  ------------------------ */
    private void loadRows() {
        snoozeContainer.removeAllViews();
        for (int i = 0; i < snoozeEntries.size(); i++) {
            addRow(i);
        }
    }

    private void addRow(int index) {
        String entry = snoozeEntries.get(index);
        String[] parts = entry.split("\\|");
        String title = parts[0];
        String rawValue = parts[1];

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);
        row.setClickable(true);            // make parent clickable for edit
        row.setLongClickable(false);

        // Title (weight 1)
        TextView txtTitle = new TextView(this);
        txtTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        txtTitle.setSingleLine(true);
        txtTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txtTitle.setText(title);

        // Time (weight 1)
        TextView txtTime = new TextView(this);
        txtTime.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        txtTime.setSingleLine(true);
        txtTime.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txtTime.setText(formatSnoozeValue(rawValue));

        // Delete (fixed 40dp)
        ImageButton btnDelete = new ImageButton(this);
        LinearLayout.LayoutParams lpDel = new LinearLayout.LayoutParams(dp(40), dp(40));
        btnDelete.setLayoutParams(lpDel);
        btnDelete.setBackgroundResource(android.R.color.transparent);
        btnDelete.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        btnDelete.setContentDescription("Delete snooze");
        btnDelete.setClickable(true);
        btnDelete.setFocusable(false);          // IMPORTANT: keep NOT focusable
        btnDelete.setFocusableInTouchMode(false);

        // Stop parent from intercepting this touch so row's onClick won't fire
        btnDelete.setOnTouchListener((v, ev) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });

        btnDelete.setImageResource(android.R.drawable.ic_menu_delete);

        // Clicks
        row.setOnClickListener(v -> showEditChoiceDialog(index, title, rawValue));
        btnDelete.setOnClickListener(v -> {
            snoozeEntries.remove(index);
            saveChanges();
            loadRows();
        });

        row.addView(txtTitle);
        row.addView(txtTime);
        row.addView(btnDelete);
        snoozeContainer.addView(row);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private boolean isDuplicate(String title, String value, int ignoreIndex) {
        for (int i = 0; i < snoozeEntries.size(); i++) {
            if (i == ignoreIndex) continue;
            String[] parts = snoozeEntries.get(i).split("\\|");
            String existingTitle = parts[0];
            String existingValue = parts[1];

            if (existingTitle.equalsIgnoreCase(title)) {
                Toast.makeText(this, "A snooze with the same name already exists.", Toast.LENGTH_SHORT).show();
                return true;
            }
            if (existingValue.equals(value)) {
                Toast.makeText(this, "A snooze with the same duration/time already exists.", Toast.LENGTH_SHORT).show();
                return true;
            }
        }
        return false;
    }

    private void showAddChoiceDialog() {
        String[] options = {"Duration", "Specific Time"};
        new AlertDialog.Builder(this)
                .setTitle("Add Snooze Option")
                .setItems(options, (d, which) -> {
                    if (which == 0) showAddDurationDialog();
                    else showAddTimeDialog();
                })
                .show();
    }

    private void showAddDurationDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Snooze Name (e.g., Short Nap)");
        layout.addView(nameInput);

        final NumberPicker hourPicker = new NumberPicker(this);
        hourPicker.setMinValue(0);
        hourPicker.setMaxValue(5);
        hourPicker.setValue(0);

        final NumberPicker minutePicker = new NumberPicker(this);
        final int[] allowedMinutes = {0, 10, 20, 30, 40, 50};
        String[] displayMinutes = {"00", "10", "20", "30", "40", "50"};
        minutePicker.setMinValue(0);
        minutePicker.setMaxValue(allowedMinutes.length - 1);
        minutePicker.setDisplayedValues(displayMinutes);

        layout.addView(hourPicker);
        layout.addView(minutePicker);

        new AlertDialog.Builder(this)
                .setTitle("Add Duration Snooze")
                .setView(layout)
                .setPositiveButton("Save", (dialog, w) -> {
                    String name = nameInput.getText().toString().trim();
                    int hours = hourPicker.getValue();
                    int minutes = allowedMinutes[minutePicker.getValue()];

                    if (hours == 0 && minutes == 0) {
                        Toast.makeText(this, "Duration must be > 0", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    long totalMillis = TimeUnit.HOURS.toMillis(hours) + TimeUnit.MINUTES.toMillis(minutes);

                    if (name.isEmpty()) {
                        if (hours > 0 && minutes > 0) name = hours + "hrs " + minutes + "min";
                        else if (hours > 0) name = hours + "hrs";
                        else name = minutes + "min";
                    }

                    if (isDuplicate(name, String.valueOf(totalMillis), -1)) return;

                    snoozeEntries.add(name + "|" + totalMillis);
                    saveChanges();
                    loadRows();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddTimeDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add Snooze Time");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Snooze Name (e.g., Dinner)");
        layout.addView(nameInput);

        final TextView timeText = new TextView(this);
        timeText.setText("Tap to select time");
        timeText.setPadding(0, 20, 0, 20);
        timeText.setOnClickListener(v -> {
            Calendar now = Calendar.getInstance();
            new TimePickerDialog(this, (view, hour, minute) -> {
                if (hour < 7 || hour >= 21) {
                    Toast.makeText(this,
                            "Please choose a time between 7:00 AM and 9:00 PM",
                            Toast.LENGTH_SHORT).show();
                    showAddTimeDialog();
                    return;
                }
                timeText.setText(String.format(Locale.getDefault(), "%02d:%02d", hour, minute));
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show();
        });
        layout.addView(timeText);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            String time = timeText.getText().toString();

            if (time.equals("Tap to select time")) {
                Toast.makeText(this, "Please select a time", Toast.LENGTH_SHORT).show();
                return;
            }

            if (name.isEmpty()) name = time;

            if (isDuplicate(name, time, -1)) return;

            snoozeEntries.add(name + "|" + time);
            saveChanges();
            loadRows();
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    /** ------------------------
     *  EDIT
     *  ------------------------ */
    private void showEditChoiceDialog(int pos, String oldTitle, String oldValue) {
        String[] options = {"Duration", "Specific Time"};
        new AlertDialog.Builder(this)
                .setTitle("Edit Snooze Option")
                .setItems(options, (d, which) -> {
                    if (which == 0) showEditDurationDialog(pos, oldTitle, oldValue);
                    else showEditTimeDialog(pos, oldTitle, oldValue);
                })
                .show();
    }

    private void showEditDurationDialog(int pos, String oldTitle, String oldRawValue) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText nameInput = new EditText(this);
        nameInput.setText(oldTitle);
        layout.addView(nameInput);

        final NumberPicker hourPicker = new NumberPicker(this);
        hourPicker.setMinValue(0);
        hourPicker.setMaxValue(5);

        final NumberPicker minutePicker = new NumberPicker(this);
        final int[] allowedMinutes = {0, 10, 20, 30, 40, 50};
        String[] displayMinutes = {"00", "10", "20", "30", "40", "50"};
        minutePicker.setMinValue(0);
        minutePicker.setMaxValue(allowedMinutes.length - 1);
        minutePicker.setDisplayedValues(displayMinutes);

        layout.addView(hourPicker);
        layout.addView(minutePicker);

        new AlertDialog.Builder(this)
                .setTitle("Edit Duration Snooze")
                .setView(layout)
                .setPositiveButton("Save", (dialog, w) -> {
                    String newTitle = nameInput.getText().toString().trim();
                    int hours = hourPicker.getValue();
                    int minutes = allowedMinutes[minutePicker.getValue()];

                    long durationMillis = TimeUnit.HOURS.toMillis(hours) + TimeUnit.MINUTES.toMillis(minutes);

                    if (durationMillis <= 0) {
                        Toast.makeText(this, "Duration must be > 0", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (isDuplicate(newTitle, String.valueOf(durationMillis), pos)) return;

                    snoozeEntries.set(pos, newTitle + "|" + durationMillis);
                    saveChanges();
                    loadRows();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditTimeDialog(int pos, String oldTitle, String oldTime) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit Snooze Time");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText nameInput = new EditText(this);
        nameInput.setText(oldTitle);
        layout.addView(nameInput);

        final TextView timeText = new TextView(this);
        timeText.setText(oldTime);
        timeText.setPadding(0, 20, 0, 20);

        timeText.setOnClickListener(v -> {
            String[] parts = oldTime.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            new TimePickerDialog(this, (view, h, m) -> {
                if (h < 7 || h >= 21) {
                    Toast.makeText(this,
                            "Please choose a time between 7:00 AM and 9:00 PM",
                            Toast.LENGTH_SHORT).show();
                    showEditTimeDialog(pos, oldTitle, oldTime);
                    return;
                }
                timeText.setText(String.format(Locale.getDefault(), "%02d:%02d", h, m));
            }, hour, minute, true).show();
        });
        layout.addView(timeText);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newTitle = nameInput.getText().toString().trim();
            String newTime = timeText.getText().toString();

            if (newTitle.isEmpty()) newTitle = newTime;

            if (isDuplicate(newTitle, newTime, pos)) return;

            snoozeEntries.set(pos, newTitle + "|" + newTime);
            saveChanges();
            loadRows();
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    /** ------------------------
     *  SAVE + FORMAT
     *  ------------------------ */
    private void saveChanges() {
        Set<String> saveSet = new HashSet<>(snoozeEntries);
        prefs.edit().putStringSet("savedSnoozeTimes", saveSet).apply();
    }

    private String formatSnoozeValue(String raw) {
        try {
            long millis = Long.parseLong(raw);
            long hours = TimeUnit.MILLISECONDS.toHours(millis);
            long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;

            if (hours > 0 && minutes > 0) return hours + "hrs " + minutes + "min";
            else if (hours > 0) return hours + "hrs";
            else return minutes + "min";
        } catch (NumberFormatException e) {
            return raw; // fallback for HH:mm time
        }
    }
}
