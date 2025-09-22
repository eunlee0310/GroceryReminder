package my.edu.utar.grocerymanagement;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ProductDetail extends AppCompatActivity {

    ImageView imagePreview;
    TextView nameText, categoryText, notesText, quantityDisplay;
    TextView rateHintText;
    LinearLayout batchListLayout;
    ImageButton backBtn, editBtn, deleteBtn;
    ImageButton decreaseBtn;

    // 🔹 Chart bits
    private RatesBulletView ratesChart;
    private float chartACR = 0f, chartECR = 0f;

    List<Map<String, Object>> batchList = new ArrayList<>();
    FirebaseFirestore db = FirebaseFirestore.getInstance();
    String uid;
    String productId;

    private int totalConsumed = 0;
    private int totalDays = 1;

    private final SimpleDateFormat ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_product_detail);

        // Auth guard
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        uid = user.getUid();

        imagePreview = findViewById(R.id.imagePreview);
        nameText = findViewById(R.id.nameText);
        categoryText = findViewById(R.id.categoryText);
        notesText = findViewById(R.id.notesText);
        quantityDisplay = findViewById(R.id.quantityDisplay);
        batchListLayout = findViewById(R.id.batchListLayout);
        editBtn = findViewById(R.id.editBtn);
        deleteBtn = findViewById(R.id.deleteBtn);
        backBtn = findViewById(R.id.backBtn);
        decreaseBtn = findViewById(R.id.decreaseBtn);
        rateHintText = findViewById(R.id.rateHintText);

        // 🔹 Add the bullet chart view
        FrameLayout chartHost = findViewById(R.id.chartHost);
        ratesChart = new RatesBulletView(this);
        chartHost.addView(
                ratesChart,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

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
                    })
                    .addOnFailureListener(e ->
                            Toast.makeText(this, "Delete failed: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                    );
        });

        decreaseBtn.setOnClickListener(v -> {
            if (batchList.isEmpty()) {
                Toast.makeText(this, "No more quantity to consume", Toast.LENGTH_SHORT).show();
                return;
            }

            // Find the first batch with quantity > 0
            Map<String, Object> targetBatch = null;
            int targetBatchIndex = -1;
            for (int i = 0; i < batchList.size(); i++) {
                Map<String, Object> batch = batchList.get(i);
                int qty = ((Number) batch.get("quantity")).intValue();
                if (qty > 0) {
                    targetBatch = batch;
                    targetBatchIndex = i;
                    break;
                }
            }

            if (targetBatch == null) {
                Toast.makeText(this, "All batches are empty", Toast.LENGTH_SHORT).show();
                return;
            }

            int currentQty = ((Number) targetBatch.get("quantity")).intValue();
            targetBatch.put("quantity", currentQty - 1);

            // Increment totalConsumed
            totalConsumed++;

            if (currentQty - 1 <= 0) {
                // If quantity becomes 0, remove the batch
                batchList.remove(targetBatchIndex);
            }

            // Prepare update map for Firestore
            Map<String, Object> updateMap = new HashMap<>();
            updateMap.put("batches", batchList);
            updateMap.put("lastUsed", new Date());
            updateMap.put("totalConsumed", totalConsumed);

            db.collection("users").document(uid)
                    .collection("grocery_items").document(productId)
                    .update(updateMap)
                    .addOnSuccessListener(unused -> {
                        loadProduct();
                        Toast.makeText(this, "Product updated", Toast.LENGTH_SHORT).show();
                    })
                    .addOnFailureListener(e ->
                            Toast.makeText(this, "Failed to update product: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                    );
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProduct(); // Reload data when returning to this activity
    }

    void loadProduct() {
        db.collection("users").document(uid).collection("grocery_items").document(productId)
                .get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) {
                        Toast.makeText(this, "Product not found.", Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }

                    // Retrieve current values from Firestore
                    Long storedTotalConsumed = doc.getLong("totalConsumed");
                    totalConsumed = (storedTotalConsumed != null) ? storedTotalConsumed.intValue() : 0;
                    totalDays = doc.getLong("totalDays") != null ? doc.getLong("totalDays").intValue() : 1; // Default to 1
                    Date lastUpdatedDate = doc.getDate("lastUpdated"); // For totalDays tracking

                    Map<String, Object> updatesForFirestore = new HashMap<>();

                    // --- Manage totalDays based on totalConsumed ---
                    if (totalConsumed > 0) {
                        if (lastUpdatedDate == null) {
                            totalDays = 1;
                            lastUpdatedDate = new Date();
                            updatesForFirestore.put("totalDays", totalDays);
                            updatesForFirestore.put("lastUpdated", lastUpdatedDate);
                        } else {
                            Calendar now = Calendar.getInstance();
                            zeroTime(now);

                            Calendar lastUpdatedCal = Calendar.getInstance();
                            lastUpdatedCal.setTime(lastUpdatedDate);
                            zeroTime(lastUpdatedCal);

                            if (now.after(lastUpdatedCal)) {
                                long diffMillis = now.getTimeInMillis() - lastUpdatedCal.getTimeInMillis();
                                int daysPassed = (int) TimeUnit.MILLISECONDS.toDays(diffMillis);

                                if (daysPassed > 0) {
                                    totalDays += daysPassed;
                                    lastUpdatedDate = new Date();
                                    updatesForFirestore.put("totalDays", totalDays);
                                    updatesForFirestore.put("lastUpdated", lastUpdatedDate);
                                }
                            }
                        }
                    } else {
                        // If totalConsumed is 0, ensure totalDays is 1 and lastUpdated is null
                        if (totalDays != 1 || lastUpdatedDate != null) {
                            totalDays = 1;
                            updatesForFirestore.put("totalDays", totalDays);
                            updatesForFirestore.put("lastUpdated", null);
                        }
                    }

                    // Always recalc ACR
                    float acrNow = (totalConsumed > 0 && totalDays > 0) ? (float) totalConsumed / totalDays : 0f;
                    updatesForFirestore.put("ACR", acrNow);

                    if (!updatesForFirestore.isEmpty()) {
                        doc.getReference().update(updatesForFirestore)
                                .addOnFailureListener(e ->
                                        Toast.makeText(this, "Failed to auto-update metrics: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                                );
                    }

                    // --- Bind UI ---
                    String name = doc.getString("name");
                    String category = doc.getString("category");
                    nameText.setText(name);
                    categoryText.setText("Category: " + category);
                    notesText.setText(doc.getString("notes"));

                    // 🔹 Category-based placeholder for image
                    int ph = getCategoryPlaceholder(category);

                    // Prefer local image, then real remote URL, else category placeholder
                    String localPath = doc.getString("imageLocalPath");
                    String remoteUrl = doc.getString("imageUrl"); // might be "default", "local", "placeholder:Cat", or a real URL

                    File localFile = (localPath != null && !localPath.isEmpty()) ? new File(localPath) : null;
                    boolean hasLocal = localFile != null && localFile.exists();
                    boolean hasRemote = remoteUrl != null
                            && !remoteUrl.equalsIgnoreCase("default")
                            && !remoteUrl.startsWith("placeholder:")
                            && !remoteUrl.equalsIgnoreCase("local");

                    if (hasLocal) {
                        Glide.with(this)
                                .load(localFile)
                                .placeholder(ph).error(ph).fallback(ph)
                                .into(imagePreview);
                    } else if (hasRemote) {
                        Glide.with(this)
                                .load(remoteUrl)
                                .placeholder(ph).error(ph).fallback(ph)
                                .into(imagePreview);
                    } else {
                        imagePreview.setImageResource(ph); // show category icon
                    }

                    Date lastUsedDateFirebase = doc.getDate("lastUsed");
                    TextView lastUsedText = findViewById(R.id.lastUsedText);
                    lastUsedText.setText(
                            lastUsedDateFirebase != null
                                    ? "Last Used: " + ymd.format(lastUsedDateFirebase)
                                    : "Last Used: Never"
                    );

                    // Load batches and calculate rates (will also update ECR + chart)
                    loadBatchList(doc);

                    // Labels + chart ACR
                    chartACR = acrNow;

                    // If ratesChart already exists, push current values (ECR may update right after)
                    if (ratesChart != null) ratesChart.setRates(chartACR, chartECR);
                    updateHint(chartACR, chartECR);

                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to load product: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
    }

    private void loadBatchList(DocumentSnapshot doc) {
        batchList.clear();
        batchListLayout.removeAllViews();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> batches = (List<Map<String, Object>>) doc.get("batches");

        if (batches == null || batches.isEmpty()) {
            quantityDisplay.setText("Quantity: 0");
            calculateAndDisplayUsageRates(); // reset rates
            return;
        }

        batchList.addAll(batches);

        // Sort by earliest expiry first (invalid/missing dates go to end)
        batchList.sort(Comparator.comparing(batch -> {
            try {
                String expiryDateString = (String) batch.get("expiryDate");
                return ymd.parse(expiryDateString);
            } catch (Exception e) {
                return new Date(Long.MAX_VALUE);
            }
        }));

        // Normalize "today" to local midnight for stable comparisons
        Calendar today = Calendar.getInstance();
        zeroTime(today);
        long todayMidnight = today.getTimeInMillis();

        int totalQty = 0;

        for (int i = 0; i < batchList.size(); i++) {
            Map<String, Object> b = batchList.get(i);

            // Qty safe read
            int qty = 0;
            Object qObj = b.get("quantity");
            if (qObj instanceof Number) qty = ((Number) qObj).intValue();
            totalQty += qty;

            String expStr = (String) b.get("expiryDate");
            boolean expired = false;

            if (expStr != null && !expStr.trim().isEmpty()) {
                try {
                    Date expDate = ymd.parse(expStr);
                    if (expDate != null) {
                        Calendar expCal = Calendar.getInstance();
                        expCal.setTime(expDate);
                        zeroTime(expCal);
                        long expMidnight = expCal.getTimeInMillis();

                        // ✅ Count "today" as expired to match NotificationService
                        expired = (expMidnight <= todayMidnight);
                    }
                } catch (Exception ignored) {
                    // keep expired=false if parse fails
                }
            }

            TextView t = new TextView(this);

            StringBuilder line = new StringBuilder();
            line.append("Batch ").append(i + 1)
                    .append(": Expiry ").append(expStr != null ? expStr : "—")
                    .append(" | Qty: ").append(qty);
            if (expired) line.append(" (Expired)");

            t.setText(line.toString());
            t.setPadding(0, dp(4), 0, dp(4));
            if (expired) {
                t.setTextColor(Color.parseColor("#D32F2F")); // stronger red
            }
            batchListLayout.addView(t);
        }

        quantityDisplay.setText("Quantity: " + totalQty);
        calculateAndDisplayUsageRates();
    }

    void calculateAndDisplayUsageRates() {
        int totalQuantityNonExpired = 0;   // only non-expired qty
        float ECR = 0f;
        int lastDaysLeft = Integer.MIN_VALUE;

        Calendar todayCalendar = Calendar.getInstance();
        zeroTime(todayCalendar);

        for (Map<String, Object> batch : batchList) {
            try {
                String expiryDateString = (String) batch.get("expiryDate");
                if (expiryDateString == null) continue;

                Date expiryDate = ymd.parse(expiryDateString);

                Calendar expiryCalendar = Calendar.getInstance();
                expiryCalendar.setTime(expiryDate);
                zeroTime(expiryCalendar);

                long diffInMillis = expiryCalendar.getTimeInMillis() - todayCalendar.getTimeInMillis();
                int daysLeft = (int) TimeUnit.MILLISECONDS.toDays(diffInMillis);
                int qty = ((Number) batch.get("quantity")).intValue();

                if (qty > 0 && daysLeft >= 0) {
                    totalQuantityNonExpired += qty;

                    lastDaysLeft = Math.max(lastDaysLeft, daysLeft);
                    float batchRate = (float) qty / (daysLeft > 0 ? daysLeft : 1);
                    ECR = Math.max(ECR, batchRate);
                }
            } catch (Exception ignored) {}
        }

        float baseRate = (lastDaysLeft > 0) ? (totalQuantityNonExpired / (float) lastDaysLeft) : 0f;
        ECR = Math.max(ECR, baseRate);

        db.collection("users")
                .document(uid)
                .collection("grocery_items")
                .document(productId)
                .update("ECR", ECR);

        chartECR = ECR;
        if (ratesChart != null) ratesChart.setRates(chartACR, chartECR);
        updateHint(chartACR, chartECR);

    }

    // optional tiny helper for padding consistency
    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private int getCategoryPlaceholder(String category) {
        switch (category.toLowerCase(Locale.ROOT)) {
            case "fruits":      return R.drawable.fruits;
            case "vegetables":  return R.drawable.vegetables;
            case "meat":        return R.drawable.meat;
            case "dairy":       return R.drawable.dairy;
            case "bakery":      return R.drawable.bakery;
            case "canned":      return R.drawable.canned;
            case "snacks":      return R.drawable.snacks;
            case "beverages":   return R.drawable.beverages;
            case "frozen":      return R.drawable.frozen;
            case "condiments":  return R.drawable.condiments;
            default:            return R.drawable.other;
        }
    }

    // ===============================
    // 🔹 Bullet chart custom view
    // ===============================
    public static class RatesBulletView extends View {
        private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ecrPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint acrPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tmpRect   = new RectF();

        private float acr = 0f, ecr = 0f;

        public RatesBulletView(Context c) { super(c); init(); }
        public RatesBulletView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }
        public RatesBulletView(Context c, @Nullable AttributeSet a, int s) { super(c, a, s); init(); }

        private void init() {
            axisPaint.setColor(Color.parseColor("#444444"));
            axisPaint.setStrokeWidth(dp(1));

            gridPaint.setColor(Color.parseColor("#E0E0E0"));
            gridPaint.setStrokeWidth(dp(1));

            ecrPaint.setColor(Color.parseColor("#90CAF9"));   // target bar
            ecrPaint.setStyle(Paint.Style.FILL);

            acrPaint.setColor(Color.parseColor("#1E88E5"));   // marker (will flip green/red)
            acrPaint.setStrokeWidth(dp(3));

            textPaint.setColor(Color.parseColor("#555555"));
            textPaint.setTextSize(dp(12));
        }

        public void setRates(float acr, float ecr) {
            this.acr = Math.max(0f, acr);
            this.ecr = Math.max(0f, ecr);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);

            // Symmetric padding and centered content region
            float padL = dp(20), padR = dp(20), padT = dp(16), padB = dp(34);
            float w = getWidth(), h = getHeight();

            // Limit the drawing width and center it (so chart appears centered even if view is match_parent)
            float maxContentW = dp(360);
            float contentW = Math.min(w - padL - padR, maxContentW);
            float left = (w - contentW) / 2f;
            float right = left + contentW;

            // Put legend higher; push bar lower for more breathing room
            float legendY = padT + dp(2);
            float barTop = padT + dp(40);
            float barH   = dp(22);
            float barBottom = barTop + barH;

            // Scale/grid
            float maxVal = Math.max(Math.max(acr, ecr), 1f);
            int steps = 4;
            for (int i = 0; i <= steps; i++) {
                float ratio = i / (float) steps;
                float x = left + ratio * (right - left);
                c.drawLine(x, barTop - dp(12), x, barBottom + dp(12), gridPaint);

                String label = String.format(Locale.getDefault(), "%.2f", ratio * maxVal);
                float tw = textPaint.measureText(label);
                c.drawText(label, x - tw/2f, h - dp(10), textPaint);
            }
            c.drawLine(left, barBottom + dp(14), right, barBottom + dp(14), axisPaint);

            // ECR bar
            float xECR = left + (ecr / maxVal) * (right - left);
            tmpRect.set(left, barTop, xECR, barBottom);
            c.drawRoundRect(tmpRect, dp(6), dp(6), ecrPaint);

            // ACR marker (red if below target, green if above/equal)
            float xACR = left + (acr / maxVal) * (right - left);
            acrPaint.setColor(acr < ecr ? Color.parseColor("#E53935") : Color.parseColor("#43A047"));
            c.drawLine(xACR, barTop - dp(10), xACR, barBottom + dp(10), acrPaint);

            // Legend (moved higher)
            c.drawRect(left, legendY - dp(8), left + dp(16), legendY + dp(2), ecrPaint);
            String ecrTxt = "ECR (target): " + String.format(Locale.getDefault(), "%.2f", ecr);
            c.drawText(ecrTxt, left + dp(22), legendY + dp(4), textPaint);

            float mX = left + dp(170);
            c.drawLine(mX, legendY - dp(4), mX + dp(18), legendY - dp(4), acrPaint);
            String acrTxt = "ACR (actual): " + String.format(Locale.getDefault(), "%.2f", acr);
            c.drawText(acrTxt, mX + dp(24), legendY + dp(2), textPaint);

            // Inline value labels with extra spacing
            String ecrVal = String.format(Locale.getDefault(), "%.2f", ecr);
            String acrVal = String.format(Locale.getDefault(), "%.2f", acr);
            float ecrTw = textPaint.measureText(ecrVal);
            c.drawText(ecrVal, Math.max(left, xECR - ecrTw), barTop - dp(14), textPaint);
            c.drawText(acrVal, xACR - textPaint.measureText(acrVal)/2f, barBottom + dp(26), textPaint);
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    }

    private void updateHint(float acr, float ecr) {
        if (rateHintText == null) return;

        if (ecr <= 0f) {
            rateHintText.setText("No plan yet. Add non-expired stock or set expiry dates.");
            rateHintText.setTextColor(Color.parseColor("#555555"));
            return;
        }

        String targetPhrase = simpleTargetPhrase(ecr);

        if (acr >= ecr || Math.abs(acr - ecr) < 0.01f) {
            // On track
            rateHintText.setText("On track. Keep " + targetPhrase + " to finish on time.");
            rateHintText.setTextColor(Color.parseColor("#2E7D32")); // green
        } else {
            // Behind
            rateHintText.setText("Behind schedule. Aim for " + targetPhrase + " to finish on time.");
            rateHintText.setTextColor(Color.parseColor("#C62828")); // red
        }
    }

    private String simpleTargetPhrase(float perDay) {
        if (perDay <= 0f) return "some each day";
        if (perDay >= 1f) {
            int d = Math.max(1, Math.round(perDay));
            return d + " " + (d == 1 ? "item" : "items") + " per day";
        } else {
            int n = Math.max(2, Math.round(1f / perDay)); // e.g., 0.3/day -> 1 item every ~3 days
            return "1 item every " + n + " days";
        }
    }


}
