package my.edu.utar.grocerymanagement.addItem;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.*;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import com.google.firebase.storage.FirebaseStorage;

import my.edu.utar.grocerymanagement.R;
import my.edu.utar.grocerymanagement.model.GroceryBatch;

import java.text.SimpleDateFormat;
import java.util.*;

public class AddItemActivity extends AppCompatActivity {

    ImageView imagePreview;
    Button uploadImageBtn, addBatchBtn, saveAllBtn;
    EditText nameInput, expiryInput, quantityInput, detailNote;
    Spinner categorySpinner;
    LinearLayout batchListContainer;
    ImageButton backBtn;

    ArrayList<GroceryBatch> batchList = new ArrayList<>();
    Uri selectedImageUri;
    String uploadedImageUrl = null;
    boolean isEditMode = false;
    String productId = null;
    String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

    Calendar calendar = Calendar.getInstance();
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    FirebaseFirestore db = FirebaseFirestore.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(my.edu.utar.grocerymanagement.R.layout.activity_add_item);

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

        String[] categories = {"Fruits", "Vegetables", "Canned", "Snacks", "Dairy", "Meat", "Beverages", "Other"};
        categorySpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, categories));

        backBtn.setOnClickListener(v -> finish());
        expiryInput.setOnClickListener(v -> showDatePicker());
        uploadImageBtn.setOnClickListener(v -> chooseImage());

        addBatchBtn.setOnClickListener(v -> {
            String date = expiryInput.getText().toString();
            String qtyStr = quantityInput.getText().toString();
            if (date.isEmpty() || qtyStr.isEmpty()) {
                Toast.makeText(this, "Enter expiry date and quantity", Toast.LENGTH_SHORT).show();
                return;
            }
            int qty = Integer.parseInt(qtyStr);
            GroceryBatch newBatch = new GroceryBatch(date, qty);
            batchList.add(newBatch);

            TextView batchView = createBatchView(date, qty, batchList.size() - 1);
            batchListContainer.addView(batchView);

            expiryInput.setText("");
            quantityInput.setText("");
        });

        saveAllBtn.setOnClickListener(v -> {
            if (selectedImageUri != null && (uploadedImageUrl == null || uploadedImageUrl.equals("default"))) {
                saveAllBtn.setEnabled(false);
                uploadImageToFirebase();
            } else {
                saveProduct();
            }
        });

        productId = getIntent().getStringExtra("productId");
        if (productId != null) {
            isEditMode = true;
            loadProductForEdit(productId);
        }
    }

    void showDatePicker() {
        new DatePickerDialog(this, (v, yr, mo, day) -> {
            calendar.set(yr, mo, day);
            expiryInput.setText(sdf.format(calendar.getTime()));
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    void chooseImage() {
        Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(i, 1001);
    }

    @Override
    protected void onActivityResult(int reqCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(reqCode, resultCode, data);
        if (reqCode == 1001 && resultCode == RESULT_OK && data != null) {
            selectedImageUri = data.getData();
            Glide.with(this).load(selectedImageUri).into(imagePreview);
        }
    }

    void uploadImageToFirebase() {
        if (selectedImageUri == null) return;

        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String filename = "user_images/" + uid + "/" + UUID.randomUUID() + ".jpg";

        FirebaseStorage.getInstance().getReference()
                .child(filename)
                .putFile(selectedImageUri)
                .addOnSuccessListener(taskSnapshot ->
                        taskSnapshot.getStorage().getDownloadUrl()
                                .addOnSuccessListener(uri -> {
                                    uploadedImageUrl = uri.toString();
                                    Toast.makeText(this, "Image uploaded", Toast.LENGTH_SHORT).show();
                                    saveProduct();
                                }))
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    saveAllBtn.setEnabled(true);
                });
    }

    void saveProduct() {
        String name = nameInput.getText().toString();
        String category = categorySpinner.getSelectedItem().toString();
        String notes = detailNote.getText().toString();

        if (name.isEmpty() || batchList.isEmpty()) {
            Toast.makeText(this, "Enter name and at least one batch", Toast.LENGTH_SHORT).show();
            return;
        }

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
        data.put("imageUrl", uploadedImageUrl == null ? "default" : uploadedImageUrl);
        data.put("batches", mappedBatches);
        data.put("notes", notes);
        data.put("createdAt", new Date());

        if (isEditMode) {
            db.collection("users").document(uid).collection("grocery_items").document(productId).set(data)
                    .addOnSuccessListener(d -> {
                        Toast.makeText(this, "Product updated!", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    });
        } else {
            db.collection("users")
                    .document(uid)
                    .collection("grocery_items")
                    .add(data)
                    .addOnSuccessListener(d -> {
                        Toast.makeText(this, "Product saved!", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    });
        }
    }

    void loadProductForEdit(String id) {
        db.collection("users").document(uid).collection("grocery_items").document(id).get()
                .addOnSuccessListener(doc -> {
            if (!doc.exists()) return;

            nameInput.setText(doc.getString("name"));
            detailNote.setText(doc.getString("notes"));
            uploadedImageUrl = doc.getString("imageUrl");

            Glide.with(this)
                    .load(uploadedImageUrl.equals("default") ? R.drawable.ic_grocery : uploadedImageUrl)
                    .into(imagePreview);

            String cat = doc.getString("category");
            ArrayAdapter<String> adapter = (ArrayAdapter<String>) categorySpinner.getAdapter();
            int pos = adapter.getPosition(cat);
            categorySpinner.setSelection(pos);

            List<Map<String, Object>> batches = (List<Map<String, Object>>) doc.get("batches");
            batchList.clear();
            batchListContainer.removeAllViews();
            if (batches != null) {
                for (Map<String, Object> b : batches) {
                    String exp = (String) b.get("expiryDate");
                    int qty = ((Long) b.get("quantity")).intValue();
                    batchList.add(new GroceryBatch(exp, qty));
                    TextView batchView = createBatchView(exp, qty, batchList.size() - 1);
                    batchListContainer.addView(batchView);
                }
            }
        });
    }

    TextView createBatchView(String expiry, int qty, int index) {
        TextView batchView = new TextView(this);
        batchView.setText("Expiry: " + expiry + " | Qty: " + qty);
        batchView.setPadding(8, 8, 8, 8);
        batchView.setOnClickListener(vv -> {
            GroceryBatch selected = batchList.get(index);
            expiryInput.setText(selected.expiryDate);
            quantityInput.setText(String.valueOf(selected.quantity));
            batchList.remove(index);
            batchListContainer.removeView(batchView);
        });
        return batchView;
    }
}
