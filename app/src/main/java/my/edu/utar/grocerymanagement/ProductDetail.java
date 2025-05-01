package my.edu.utar.grocerymanagement;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;

import java.text.SimpleDateFormat;
import java.util.*;

import my.edu.utar.grocerymanagement.addItem.AddItemActivity;

public class ProductDetail extends AppCompatActivity {

    ImageView imagePreview;
    TextView nameText, categoryText, notesText, quantityDisplay;
    TextView actualRateText, baseRateText, estimatedRateText;
    LinearLayout batchListLayout;
    Button editBtn, deleteBtn;
    ImageButton backBtn;
    ImageButton decreaseBtn;

    List<Map<String, Object>> batchList = new ArrayList<>();
    FirebaseFirestore db = FirebaseFirestore.getInstance();
    String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
    String productId;

    private int totalConsumed = 0;
    private int totalDays = 1;
    private long lastConsumptionTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_product_detail);

        imagePreview = findViewById(R.id.imagePreview);
        nameText = findViewById(R.id.nameText);
        categoryText = findViewById(R.id.categoryText);
        notesText = findViewById(R.id.notesText);
        quantityDisplay = findViewById(R.id.quantityDisplay);
        actualRateText = findViewById(R.id.actualRateText);
        baseRateText = findViewById(R.id.baseRateText);
        estimatedRateText = findViewById(R.id.estimatedRateText);
        batchListLayout = findViewById(R.id.batchListLayout);
        editBtn = findViewById(R.id.editBtn);
        deleteBtn = findViewById(R.id.deleteBtn);
        backBtn = findViewById(R.id.backBtn);
        decreaseBtn = findViewById(R.id.decreaseBtn);

        productId = getIntent().getStringExtra("productId");

        loadProduct();

        backBtn.setOnClickListener(v -> finish());

        editBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, AddItemActivity.class);
            i.putExtra("productId", productId);
            startActivity(i);
        });

        deleteBtn.setOnClickListener(v -> {
            db.collection("users").document(uid).collection("grocery_items").document(productId).delete()
                    .addOnSuccessListener(unused -> {
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(this, MainActivity.class));
                        finish();
                    });
        });

        decreaseBtn.setOnClickListener(v -> {
            if (batchList.isEmpty()) {
                Toast.makeText(this, "No more quantity to consume", Toast.LENGTH_SHORT).show();
                return;
            }

            for (int i = 0; i < batchList.size(); i++) {
                Map<String, Object> batch = batchList.get(i);
                int qty = ((Long) batch.get("quantity")).intValue();

                if (qty > 0) {
                    batch.put("quantity", qty - 1);
                    trackConsumption();

                    if (qty - 1 <= 0) batchList.remove(i);
                    updateBatchInFirestore();
                    return;
                }
            }

            Toast.makeText(this, "All batches are empty", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProduct();
    }

    void loadProduct() {
        SharedPreferences prefs = getSharedPreferences("consumption_history", MODE_PRIVATE);
        totalConsumed = prefs.getInt("totalConsumed_" + productId, 0);
        totalDays = prefs.getInt("totalDays_" + productId, 1);

        db.collection("users").document(uid).collection("grocery_items").document(productId)
                .get().addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;

                    long currentTime = System.currentTimeMillis();
                    long firstConsumptionTime = prefs.getLong("firstConsumptionTime_" + productId, 0);
                    checkAndUpdateDays(firstConsumptionTime, currentTime);

                    nameText.setText(doc.getString("name"));
                    categoryText.setText("Category: " + doc.getString("category"));
                    notesText.setText(doc.getString("notes"));

                    String img = doc.getString("imageUrl");
                    Glide.with(this).load(img.equals("default") ? R.drawable.ic_grocery : img).into(imagePreview);

                    Date lastUsedDate = doc.getDate("lastUsed");
                    TextView lastUsedText = findViewById(R.id.lastUsedText);
                    lastUsedText.setText(lastUsedDate != null ?
                            "Last Used: " + new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(lastUsedDate)
                            : "Last Used: Never");

                    loadBatchList(doc);

                    float calculatedRate = (totalDays > 0) ? (float) totalConsumed / totalDays : 0f;
                    actualRateText.setText(String.format("Actual Rate: %.2f", calculatedRate));
                    triggerSimpleNotificationIfNeeded(); // 🚨 Show alert if needed
                    checkForgottenStockReminder(doc);
                    checkExpiredItemReminder(doc);

                });
    }

    private void trackConsumption() {
        SharedPreferences prefs = getSharedPreferences("consumption_history", MODE_PRIVATE);
        long currentTime = System.currentTimeMillis();
        long firstConsumptionTime = prefs.getLong("firstConsumptionTime_" + productId, 0);

        if (firstConsumptionTime == 0) {
            firstConsumptionTime = currentTime;
            prefs.edit().putLong("firstConsumptionTime_" + productId, firstConsumptionTime).apply();
        }

        totalConsumed++;
        lastConsumptionTime = currentTime;
        checkAndUpdateDays(firstConsumptionTime, currentTime);

        prefs.edit()
                .putInt("totalConsumed_" + productId, totalConsumed)
                .putInt("totalDays_" + productId, totalDays)
                .putLong("lastConsumptionTime_" + productId, lastConsumptionTime)
                .apply();

        float rate = (float) totalConsumed / totalDays;
        updateConsumptionRateInFirestore(rate);
    }

    private void checkAndUpdateDays(long firstTime, long now) {
        Calendar startCal = Calendar.getInstance();
        startCal.setTimeInMillis(firstTime);
        startCal.set(Calendar.HOUR_OF_DAY, 0);
        startCal.set(Calendar.MINUTE, 0);
        startCal.set(Calendar.SECOND, 0);
        startCal.set(Calendar.MILLISECOND, 0);

        Calendar nowCal = Calendar.getInstance();
        nowCal.setTimeInMillis(now);
        nowCal.set(Calendar.HOUR_OF_DAY, 0);
        nowCal.set(Calendar.MINUTE, 0);
        nowCal.set(Calendar.SECOND, 0);
        nowCal.set(Calendar.MILLISECOND, 0);

        long diffMillis = nowCal.getTimeInMillis() - startCal.getTimeInMillis();
        long daysElapsed = diffMillis / (1000 * 60 * 60 * 24);

        totalDays = (int) Math.max(1, daysElapsed + 1);
    }

    private void updateConsumptionRateInFirestore(float rate) {
        db.collection("users")
                .document(uid)
                .collection("grocery_items")
                .document(productId)
                .update("consumptionRate", rate,
                        "totalConsumed", totalConsumed,
                        "totalDays", totalDays,
                        "lastUsed", new Date())
                .addOnSuccessListener(unused ->
                        actualRateText.setText(String.format("Actual Rate: %.2f", rate)));
    }

    private void loadBatchList(DocumentSnapshot doc) {
        batchList.clear();
        batchListLayout.removeAllViews();
        List<Map<String, Object>> batches = (List<Map<String, Object>>) doc.get("batches");

        if (batches != null) {
            batchList.addAll(batches);
            batchList.sort(Comparator.comparing(batch -> {
                try {
                    return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            .parse((String) batch.get("expiryDate"));
                } catch (Exception e) {
                    return new Date(Long.MAX_VALUE);
                }
            }));

            int totalQty = 0;
            for (int i = 0; i < batchList.size(); i++) {
                Map<String, Object> b = batchList.get(i);
                int qty = ((Long) b.get("quantity")).intValue();
                totalQty += qty;

                TextView t = new TextView(this);
                t.setText("Batch " + (i + 1) + ": Expiry " + b.get("expiryDate") + " | Qty: " + qty);
                batchListLayout.addView(t);
            }

            quantityDisplay.setText("Quantity: " + totalQty);
            calculateAndDisplayUsageRates(); // Add this line

        } else {
            quantityDisplay.setText("Quantity: 0");
        }
    }

    void calculateAndDisplayUsageRates() {
        int totalQuantity = 0;
        float estimatedRate = 0;
        int lastDaysLeft = Integer.MIN_VALUE;

        Calendar todayCalendar = Calendar.getInstance();
        todayCalendar.set(Calendar.HOUR_OF_DAY, 0);
        todayCalendar.set(Calendar.MINUTE, 0);
        todayCalendar.set(Calendar.SECOND, 0);
        todayCalendar.set(Calendar.MILLISECOND, 0);

        for (Map<String, Object> batch : batchList) {
            int qty = ((Long) batch.get("quantity")).intValue();
            String expiryDateString = (String) batch.get("expiryDate");
            try {
                Date expiryDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(expiryDateString);

                Calendar expiryCalendar = Calendar.getInstance();
                expiryCalendar.setTime(expiryDate);
                long diffInMillis = expiryCalendar.getTimeInMillis() - todayCalendar.getTimeInMillis();
                int daysLeft = (int) (diffInMillis / (1000 * 60 * 60 * 24));

                if (qty > 0) {
                    totalQuantity += qty;

                    if (daysLeft >= 0) {
                        lastDaysLeft = Math.max(lastDaysLeft, daysLeft);
                        float batchRate = (float) qty / (daysLeft > 0 ? daysLeft : 1);
                        estimatedRate = Math.max(estimatedRate, batchRate);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        float baseRate = (lastDaysLeft > 0) ? (totalQuantity / (float) lastDaysLeft) : 0;

        baseRateText.setText(String.format("Base Rate: %.2f", baseRate));
        estimatedRateText.setText(String.format("Estimated Rate: %.2f", estimatedRate));
    }

    void updateBatchInFirestore() {
        Map<String, Object> updateMap = new HashMap<>();
        updateMap.put("batches", batchList);
        updateMap.put("lastUsed", new Date());
        updateMap.put("totalConsumed", totalConsumed);
        updateMap.put("totalDays", totalDays);
        updateMap.put("consumptionRate", (float) totalConsumed / totalDays);

        db.collection("users").document(uid)
                .collection("grocery_items").document(productId)
                .update(updateMap)
                .addOnSuccessListener(unused -> {
                    loadProduct();
                    Toast.makeText(this, "Batch updated", Toast.LENGTH_SHORT).show();
                });
    }

    private void triggerSimpleNotificationIfNeeded() {
        float acr = (totalDays > 0) ? (float) totalConsumed / totalDays : 0;
        float ecr = calculateEstimatedRateForReminder();

        if (acr < ecr) {
            sendReminderNotification(
                    "Low Consumption Reminder",
                    "You’re consuming \"" + nameText.getText() + "\" too slowly.");
        }
    }


    private float calculateEstimatedRateForReminder() {
        int totalQty = 0;
        float maxRate = 0;

        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);

        for (Map<String, Object> batch : batchList) {
            int qty = ((Long) batch.get("quantity")).intValue();
            String expiryDate = (String) batch.get("expiryDate");
            try {
                Date expiry = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(expiryDate);
                long diff = expiry.getTime() - today.getTimeInMillis();
                int daysLeft = (int) (diff / (1000 * 60 * 60 * 24));

                if (qty > 0 && daysLeft > 0) {
                    float rate = qty / (float) daysLeft;
                    maxRate = Math.max(maxRate, rate);
                    totalQty += qty;
                }
            } catch (Exception ignored) {}
        }

        float base = (totalQty > 0) ? totalQty / (float) 7 : 0; // Assume 7 days for testing
        return Math.max(maxRate, base);
    }

    private void sendReminderNotification(String title, String message) {
        String channelId = "grocery_reminder";
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Grocery Reminders", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Reminders for low consumption or expiry");
            manager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_grocery)  // use your existing app icon
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        manager.notify(new Random().nextInt(), builder.build());
    }

    private void checkForgottenStockReminder(DocumentSnapshot doc) {
        String itemName = doc.getString("name");
        Date lastUsed = doc.getDate("lastUsed");
        long now = System.currentTimeMillis();
        boolean shouldNotify = false;

        if (lastUsed == null) {
            // Check if item was created more than 15 days ago
            Date created = doc.getDate("createdAt");
            if (created != null) {
                long daysSinceCreated = (now - created.getTime()) / (1000 * 60 * 60 * 24);
                if (daysSinceCreated >= 15) shouldNotify = true;
            }
        } else {
            long daysSinceUsed = (now - lastUsed.getTime()) / (1000 * 60 * 60 * 24);
            if (daysSinceUsed >= 15) shouldNotify = true;
        }

        if (shouldNotify) {
            sendReminderNotification(
                    "Forgotten Stock: " + itemName,
                    "You haven’t used \"" + itemName + "\" in over 15 days."
            );
        }
    }

    private void checkExpiredItemReminder(DocumentSnapshot doc) {
        String itemName = doc.getString("name");
        List<Map<String, Object>> batches = (List<Map<String, Object>>) doc.get("batches");

        if (batches == null || batches.isEmpty()) return;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        long today = System.currentTimeMillis();

        for (Map<String, Object> batch : batches) {
            String expiryStr = (String) batch.get("expiryDate");
            try {
                Date expiry = sdf.parse(expiryStr);
                if (expiry != null && expiry.getTime() < today) {
                    sendReminderNotification(
                            "Expired Item Detected",
                            "Your item \"" + itemName + "\" has expired. Please throw it away."
                    );
                    return; // Only notify once per item
                }
            } catch (Exception ignored) {}
        }
    }


}
