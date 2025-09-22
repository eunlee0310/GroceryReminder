package my.edu.utar.grocerymanagement;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import my.edu.utar.grocerymanagement.model.GroceryBatch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AddItemActivity extends AppCompatActivity {

    // --- UI ---
    ImageView imagePreview;
    Button uploadImageBtn, addBatchBtn, saveAllBtn;
    EditText nameInput, expiryInput, quantityInput, detailNote;
    Spinner categorySpinner;
    LinearLayout batchListContainer;
    ImageButton backBtn;

    // --- State ---
    ArrayList<GroceryBatch> batchList = new ArrayList<>();
    Uri selectedImageUri;                 // gallery/camera source
    String localImagePath = null;         // LOCAL file path we save
    boolean isEditMode = false;

    // ⬇️ Fix: default to FALSE; only true if explicitly passed
    boolean allowCatalogUpdate = false;

    String productId = null;
    String uid;                           // set in onCreate after auth check

    // Prefill from barcode scanner
    String prefillBarcode = null;
    String prefillName = null;
    String prefillCategory = null;

    // Camera helpers
    private static final int REQ_GALLERY = 1001;
    private static final int REQ_CAMERA  = 1002;
    private Uri pendingCameraUri;

    // Time
    Calendar calendar = Calendar.getInstance();
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    // Firebase
    FirebaseFirestore db = FirebaseFirestore.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(my.edu.utar.grocerymanagement.R.layout.activity_add_item);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        imagePreview = findViewById(R.id.imagePreview);
        uploadImageBtn = findViewById(R.id.uploadImageBtn);
        addBatchBtn = findViewById(R.id.addBatchBtn);
        saveAllBtn = findViewById(R.id.saveAllBtn);
        backBtn = findViewById(R.id.backBtn);
        nameInput = findViewById(R.id.nameInput);
        expiryInput = findViewById(R.id.expiryInput);
        quantityInput = findViewById(R.id.quantityInput);
        detailNote = findViewById(R.id.detailNote);
        categorySpinner = findViewById(R.id.categorySpinner);
        batchListContainer = findViewById(R.id.batchListContainer);

        String[] categories = {"Fruits", "Vegetables", "Meat", "Dairy", "Bakery", "Canned", "Snacks", "Beverages", "Frozen", "Condiments", "Other"};
        categorySpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, categories));

        // ⬇️ Fix: only enable when provided (default false)
        if (getIntent().hasExtra("allowCatalogUpdate")) {
            allowCatalogUpdate = getIntent().getBooleanExtra("allowCatalogUpdate", false);
        }

        backBtn.setOnClickListener(v -> finish());
        expiryInput.setOnClickListener(v -> showDatePicker());

        // Image chooser (camera or gallery)
        uploadImageBtn.setOnClickListener(v -> showImageSourceChooser());

        addBatchBtn.setOnClickListener(v -> {
            String date = expiryInput.getText().toString();
            String qtyStr = quantityInput.getText().toString();
            if (date.isEmpty() || qtyStr.isEmpty()) {
                Toast.makeText(this, "Enter expiry date and quantity", Toast.LENGTH_SHORT).show();
                return;
            }
            int qty;
            try { qty = Integer.parseInt(qtyStr); }
            catch (NumberFormatException e) {
                Toast.makeText(this, "Quantity must be a number", Toast.LENGTH_SHORT).show();
                return;
            }
            GroceryBatch newBatch = new GroceryBatch(date, qty);
            batchList.add(newBatch);
            View row = createBatchView(newBatch);
            batchListContainer.addView(row);

            expiryInput.setText("");
            quantityInput.setText("");
        });

        saveAllBtn.setOnClickListener(v -> saveProduct());

        // --- Determine flow ---
        productId = getIntent().getStringExtra("productId");

        // Always read prefill extras (even in edit mode)
        prefillBarcode  = getIntent().getStringExtra("prefillBarcode");
        prefillName     = getIntent().getStringExtra("prefillName");
        prefillCategory = getIntent().getStringExtra("prefillCategory");

        // If new item → set prefill + show category placeholder immediately
        if (productId == null) {
            if (prefillName != null) nameInput.setText(prefillName);
            if (prefillCategory != null) {
                @SuppressWarnings("unchecked")
                ArrayAdapter<String> adapter = (ArrayAdapter<String>) categorySpinner.getAdapter();
                int pos = adapter.getPosition(prefillCategory);
                if (pos >= 0) categorySpinner.setSelection(pos);
            }
            // Show placeholder for current category
            updateCategoryPlaceholderPreview();
        }

        // When user changes category, update placeholder if no custom image chosen yet
        categorySpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (selectedImageUri == null && (localImagePath == null || localImagePath.isEmpty())) {
                    updateCategoryPlaceholderPreview();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        if (productId != null) {
            isEditMode = true;

            // ⬇️ Extra guard: in edit mode, unless the extra was explicitly provided, never update catalog
            if (!getIntent().hasExtra("allowCatalogUpdate")) {
                allowCatalogUpdate = false;
            }

            loadProductForEdit(productId);
        }
    }

    private int getCategoryPlaceholder(String category) {
        String c = category == null ? "" : category;
        switch (c.toLowerCase(Locale.ROOT)) {
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

    private void updateCategoryPlaceholderPreview() {
        String cat = (categorySpinner.getSelectedItem() == null) ? null : categorySpinner.getSelectedItem().toString();
        imagePreview.setImageResource(getCategoryPlaceholder(cat));
    }

    // ---------- Image picking ----------

    private void showImageSourceChooser() {
        final String[] items = {"Take Photo", "Choose from Gallery"};
        new AlertDialog.Builder(this)
                .setTitle("Add Image")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        // Camera
                        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED) {
                            openCamera();
                        } else {
                            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 42);
                        }
                    } else {
                        // Gallery
                        openGallery();
                    }
                })
                .show();
    }

    private void openGallery() {
        Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(i, REQ_GALLERY);
    }

    private void openCamera() {
        try {
            File dir = new File(getCacheDir(), "images");
            if (!dir.exists()) dir.mkdirs();
            File photo = new File(dir, "cam_" + System.currentTimeMillis() + ".jpg");
            pendingCameraUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    photo
            );
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cam.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (cam.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(cam, REQ_CAMERA);
            } else {
                Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to open camera: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int reqCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(reqCode, resultCode, data);
        if (resultCode != RESULT_OK) return;

        if (reqCode == REQ_GALLERY) {
            if (data != null && data.getData() != null) {
                selectedImageUri = data.getData();
                Glide.with(this).load(selectedImageUri)
                        .placeholder(getCategoryPlaceholder(getSelectedCategory()))
                        .error(getCategoryPlaceholder(getSelectedCategory()))
                        .fallback(getCategoryPlaceholder(getSelectedCategory()))
                        .into(imagePreview);
                saveImageLocally(selectedImageUri); // keep local copy
            }
        } else if (reqCode == REQ_CAMERA) {
            if (pendingCameraUri != null) {
                selectedImageUri = pendingCameraUri;
                pendingCameraUri = null;
                Glide.with(this).load(selectedImageUri)
                        .placeholder(getCategoryPlaceholder(getSelectedCategory()))
                        .error(getCategoryPlaceholder(getSelectedCategory()))
                        .fallback(getCategoryPlaceholder(getSelectedCategory()))
                        .into(imagePreview);
                saveImageLocally(selectedImageUri); // keep local copy
            } else {
                Toast.makeText(this, "Camera image unavailable", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String getSelectedCategory() {
        return categorySpinner.getSelectedItem() == null ? null : categorySpinner.getSelectedItem().toString();
    }

    // ---------- Date picker ----------

    void showDatePicker() {
        new DatePickerDialog(this, (v, yr, mo, day) -> {
            calendar.set(yr, mo, day);
            expiryInput.setText(sdf.format(calendar.getTime()));
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ---------- Save product (local image only) ----------

    void saveProduct() {
        String name = nameInput.getText().toString().trim();
        String category = categorySpinner.getSelectedItem() != null
                ? categorySpinner.getSelectedItem().toString()
                : "";
        String notes = detailNote.getText().toString().trim();
        long createdTime = System.currentTimeMillis();

        if (name.isEmpty() || batchList.isEmpty()) {
            Toast.makeText(this, "Enter name and at least one batch", Toast.LENGTH_SHORT).show();
            return;
        }

        // batches
        List<Map<String, Object>> mappedBatches = new ArrayList<>();
        for (GroceryBatch b : batchList) {
            Map<String, Object> map = new HashMap<>();
            map.put("expiryDate", b.expiryDate);
            map.put("quantity", b.quantity);
            mappedBatches.add(map);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("category", category);
        data.put("notes", notes);
        data.put("batches", mappedBatches);

        // Image fields
        if (!TextUtils.isEmpty(localImagePath)) {
            data.put("imageLocalPath", localImagePath);
            data.put("imageUrl", "local"); // marker
        } else {
            // Mark that we are using a category placeholder (optional)
            data.put("imageUrl", "placeholder:" + category);
            data.put("imageLocalPath", "");
        }

        // Always carry barcode if we have it (new or edit)
        if (prefillBarcode != null && !prefillBarcode.isEmpty()) {
            data.put("barcode", prefillBarcode);
        }

        if (isEditMode) {
            db.collection("users").document(uid).collection("grocery_items")
                    .document(productId)
                    .update(data)
                    .addOnSuccessListener(d -> {
                        if (allowCatalogUpdate) {
                            maybeUpsertCatalog(name, category, true);
                        }
                        Toast.makeText(this, "Product updated!", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    })
                    .addOnFailureListener(e ->
                            Toast.makeText(this, "Update failed: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                    );
        } else {
            // initial tracking fields
            data.put("createdTime", createdTime);
            data.put("lastUsed", new Date(createdTime));
            data.put("totalConsumed", 0);
            data.put("totalDays", 1);
            data.put("ACR", 0.0);
            data.put("ECR", 0.0);

            db.collection("users").document(uid).collection("grocery_items")
                    .add(data)
                    .addOnSuccessListener(d -> {
                        if (allowCatalogUpdate) {
                            maybeUpsertCatalog(name, category, false);
                        }
                        Toast.makeText(this, "Product saved!", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    })
                    .addOnFailureListener(e ->
                            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    void loadProductForEdit(String id) {
        db.collection("users").document(uid).collection("grocery_items").document(id).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;

                    nameInput.setText(doc.getString("name"));
                    detailNote.setText(doc.getString("notes"));

                    String docBarcode = doc.getString("barcode");
                    if (docBarcode != null && !docBarcode.isEmpty()) {
                        prefillBarcode = docBarcode;
                    }

                    // Category first (for correct placeholder)
                    String cat = doc.getString("category");
                    if (cat != null) {
                        @SuppressWarnings("unchecked")
                        ArrayAdapter<String> adapter = (ArrayAdapter<String>) categorySpinner.getAdapter();
                        int pos = adapter.getPosition(cat);
                        if (pos >= 0) categorySpinner.setSelection(pos);
                    }

                    // Load local image if exists; else category placeholder
                    String local = doc.getString("imageLocalPath");
                    localImagePath = local; // keep in memory
                    int ph = getCategoryPlaceholder(cat);

                    if (local != null && !local.isEmpty()) {
                        File imgFile = new File(local);
                        if (imgFile.exists()) {
                            Glide.with(this).load(imgFile).placeholder(ph).error(ph).into(imagePreview);
                        } else {
                            imagePreview.setImageResource(ph);
                        }
                    } else {
                        imagePreview.setImageResource(ph);
                    }

                    // Batches
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> batches = (List<Map<String, Object>>) doc.get("batches");
                    batchList.clear();
                    batchListContainer.removeAllViews();
                    if (batches != null) {
                        for (Map<String, Object> b : batches) {
                            String exp = (String) b.get("expiryDate");
                            Number qNum = (Number) b.get("quantity");
                            int qty = qNum == null ? 0 : qNum.intValue();
                            batchList.add(new GroceryBatch(exp, qty));
                        }
                        batchList.sort(Comparator.comparing(gb -> gb.expiryDate));
                        for (int i = 0; i < batchList.size(); i++) {
                            GroceryBatch gb = batchList.get(i);
                            View row = createBatchView(gb);
                            batchListContainer.addView(row);
                        }
                    }
                });
    }

    View createBatchView(GroceryBatch batch) {
        TextView batchView = new TextView(this);

        String base = "Expiry: " + batch.expiryDate + " | Qty: " + batch.quantity + "  ";
        String hint = "(tap to edit)";
        String remove = "  ✖"; // clickable delete

        SpannableString ss = new SpannableString(base + hint + remove);

        // Hint styling
        ss.setSpan(new android.text.style.ForegroundColorSpan(Color.GRAY),
                base.length(), base.length() + hint.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new android.text.style.RelativeSizeSpan(0.9f),
                base.length(), base.length() + hint.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Clickable "✖"
        int delStart = (base + hint).length();
        int delEnd   = delStart + remove.length();
        ss.setSpan(new ClickableSpan() {
            @Override public void onClick(View widget) {
                // Remove this batch safely using object reference
                batchList.remove(batch);
                // widget is the TextView (row) — remove it from its parent
                if (widget != null && widget.getParent() instanceof ViewGroup) {
                    ((ViewGroup) widget.getParent()).removeView((View) widget);
                }
            }
            @Override public void updateDrawState(TextPaint ds) {
                super.updateDrawState(ds);
                ds.setColor(0xFFE53935);    // red-ish
                ds.setUnderlineText(false); // no underline
            }
        }, delStart, delEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        batchView.setText(ss);
        batchView.setMovementMethod(LinkMovementMethod.getInstance());
        batchView.setLinksClickable(true);
        batchView.setHighlightColor(0);     // no purple tap highlight

        // Tight row: no margins, small internal padding for touch
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = 0; lp.bottomMargin = 0;
        batchView.setLayoutParams(lp);

        int pad = Math.round(getResources().getDisplayMetrics().density * 10);
        batchView.setPadding(pad, pad, pad, pad);
        batchView.setIncludeFontPadding(false);
        batchView.setLineSpacing(0f, 1f);
        batchView.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);

        // Ripple for row tap (edit)
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        batchView.setBackgroundResource(tv.resourceId);

        // Row tap → edit (clicking ✖ is handled by the ClickableSpan above)
        batchView.setOnClickListener(v -> {
            expiryInput.setText(batch.expiryDate);
            quantityInput.setText(String.valueOf(batch.quantity));
            batchList.remove(batch);
            if (v.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.getParent()).removeView(v);
            }
        });

        return batchView;
    }

    // Keep a local copy; store its absolute path for this device only
    void saveImageLocally(Uri imageUri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(imageUri);
            String filename = "grocery_image_" + System.currentTimeMillis() + ".jpg";
            File file = new File(getFilesDir(), filename);

            FileOutputStream outputStream = new FileOutputStream(file);
            byte[] buffer = new byte[1024];
            int len;
            while ((len = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, len);
            }
            outputStream.close();
            inputStream.close();

            localImagePath = file.getAbsolutePath();
            Toast.makeText(this, "Image saved locally!", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Toast.makeText(this, "Failed to save image locally.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Upsert catalog/{barcode} with latest name/category so a future scan can retrieve them.
     * Runs on both CREATE and UPDATE flows (but only if allowCatalogUpdate == true).
     */
    void maybeUpsertCatalog(String name, String category, boolean fromEdit) {
        if (prefillBarcode == null || prefillBarcode.isEmpty()) {
            if (fromEdit) {
                // silent if editing and no barcode on the doc
            }
            return;
        }
        if (name == null || name.trim().isEmpty()) return;
        if (category == null || category.trim().isEmpty()) return;

        Map<String, Object> cat = new HashMap<>();
        cat.put("name", name.trim());
        cat.put("category", category.trim());
        cat.put("updatedBy", uid);
        cat.put("updatedAt", FieldValue.serverTimestamp());

        db.collection("catalog").document(prefillBarcode)
                .set(cat, SetOptions.merge())
                .addOnSuccessListener(unused -> {
                    Toast.makeText(this, "Catalog updated", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Catalog update failed: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
    }
}
