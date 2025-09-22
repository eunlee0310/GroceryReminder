package my.edu.utar.grocerymanagement;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BackgroundWorker extends Worker {

    private final FirebaseFirestore db;
    private String uid;
    private static final String TAG = "BackgroundWorker";

    public BackgroundWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        db = FirebaseFirestore.getInstance();
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        if (uid == null) {
            Log.e(TAG, "User not authenticated, skipping background work.");
            return Result.failure();
        }

        try {
            // 🔹 1. Update Firestore product metrics
            List<DocumentSnapshot> products = Tasks.await(
                    db.collection("users").document(uid)
                            .collection("grocery_items")
                            .get()
            ).getDocuments();

            for (DocumentSnapshot doc : products) {
                updateProductMetrics(doc);
            }

            // 🔹 2. Trigger NotificationService check
            Context context = getApplicationContext();
            Intent intent = new Intent(context, NotificationService.class);
            intent.setAction("my.edu.utar.grocerymanagement.CHECK_NOTIFICATIONS");
            intent.putExtra("fromSnooze", false);
            context.sendBroadcast(intent);

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Background worker failed: " + e.getMessage(), e);
            return Result.retry();
        }
    }


    private void updateProductMetrics(DocumentSnapshot doc) {
        Map<String, Object> updates = new HashMap<>();

        // Get totalConsumed, totalDays, and lastUpdated from Firestore
        Long storedTotalConsumed = doc.getLong("totalConsumed");
        int totalConsumed = (storedTotalConsumed != null) ? storedTotalConsumed.intValue() : 0;
        int totalDays = doc.getLong("totalDays") != null ? doc.getLong("totalDays").intValue() : 1; // Default to 1
        Date lastUpdatedDate = doc.getDate("lastUpdated"); // Timestamp for totalDays calculation

        // --- Logic to manage totalDays based on totalConsumed ---
        if (totalConsumed > 0) { // Only update totalDays if consumption has occurred
            if (lastUpdatedDate == null) {
                // This means it's the first time totalConsumed went > 0 and was processed, or lastUpdated was missing.
                // Initialize totalDays to 1 and lastUpdated to now.
                totalDays = 1;
                lastUpdatedDate = new Date();
                updates.put("totalDays", totalDays);
                updates.put("lastUpdated", lastUpdatedDate);
            } else {
                Calendar now = Calendar.getInstance();
                now.set(Calendar.HOUR_OF_DAY, 0); now.set(Calendar.MINUTE, 0); now.set(Calendar.SECOND, 0); now.set(Calendar.MILLISECOND, 0);

                Calendar lastUpdatedCal = Calendar.getInstance();
                lastUpdatedCal.setTime(lastUpdatedDate);
                lastUpdatedCal.set(Calendar.HOUR_OF_DAY, 0); lastUpdatedCal.set(Calendar.MINUTE, 0); lastUpdatedCal.set(Calendar.SECOND, 0); lastUpdatedCal.set(Calendar.MILLISECOND, 0);

                if (now.after(lastUpdatedCal)) {
                    long diffMillis = now.getTimeInMillis() - lastUpdatedCal.getTimeInMillis();
                    int daysPassed = (int) TimeUnit.MILLISECONDS.toDays(diffMillis);

                    if (daysPassed > 0) {
                        totalDays += daysPassed;
                        lastUpdatedDate = new Date(); // Update local variable for immediate use
                        updates.put("totalDays", totalDays);
                        updates.put("lastUpdated", lastUpdatedDate);
                    }
                }
            }
        } else {
            // If totalConsumed is 0, ensure totalDays is 1 and lastUpdated is null
            if (totalDays != 1 || lastUpdatedDate != null) {
                totalDays = 1;
                lastUpdatedDate = null; // Reset if no consumption
                updates.put("totalDays", totalDays);
                updates.put("lastUpdated", lastUpdatedDate);
            }
        }
        // --- End totalDays logic ---

        // Always calculate and put ACR into updates
        updates.put("ACR", (totalConsumed > 0 && totalDays > 0) ? (float) totalConsumed / totalDays : 0f);

        // Recalculate and add ECR, baseRate to updates
        List<Map<String, Object>> batches = (List<Map<String, Object>>) doc.get("batches");
        if (batches != null && !batches.isEmpty()) {
            float[] rates = calculateUsageRates(batches);
            updates.put("ECR", rates[0]);
            updates.put("baseRate", rates[1]);
        } else {
            updates.put("ECR", 0f); // Default to 0 if no batches
            updates.put("baseRate", 0f);
        }

        // Only update Firestore if there are changes to be made
        if (!updates.isEmpty()) {
            doc.getReference().update(updates)
                    .addOnFailureListener(e -> Log.e(TAG, "Failed to update document: " + doc.getId() + ", Error: " + e.getMessage()));
        }
    }

    private float[] calculateUsageRates(List<Map<String, Object>> batches) {
        float maxRate = 0f;                 // ECR candidate from individual batches
        int totalQuantityNonExpired = 0;    // ✅ only non-expired qty
        int lastDaysLeft = Integer.MIN_VALUE;

        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);

        for (Map<String, Object> batch : batches) {
            try {
                String expiryDateString = (String) batch.get("expiryDate");
                if (expiryDateString == null) continue;

                Date expiry = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(expiryDateString);
                Calendar expiryCal = Calendar.getInstance();
                expiryCal.setTime(expiry);
                expiryCal.set(Calendar.HOUR_OF_DAY, 0);
                expiryCal.set(Calendar.MINUTE, 0);
                expiryCal.set(Calendar.SECOND, 0);
                expiryCal.set(Calendar.MILLISECOND, 0);

                long diff = expiryCal.getTimeInMillis() - today.getTimeInMillis();
                int daysLeft = (int) TimeUnit.MILLISECONDS.toDays(diff);
                int qty = ((Long) batch.get("quantity")).intValue();

                if (qty > 0 && daysLeft >= 0) { // ✅ exclude expired from BOTH
                    totalQuantityNonExpired += qty;

                    lastDaysLeft = Math.max(lastDaysLeft, daysLeft);
                    float rate = (float) qty / (daysLeft > 0 ? daysLeft : 1);
                    maxRate = Math.max(maxRate, rate);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error calculating rates for batch: " + e.getMessage(), e);
            }
        }

        float baseRate = (lastDaysLeft > 0) ? (totalQuantityNonExpired / (float) lastDaysLeft) : 0f;
        float ECR = Math.max(maxRate, baseRate);
        return new float[]{ECR, baseRate};
    }

}