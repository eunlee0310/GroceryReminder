package my.edu.utar.grocerymanagement;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.text.SimpleDateFormat;
import java.util.*;
import my.edu.utar.grocerymanagement.addItem.AddItemActivity;
import android.Manifest;


public class MainActivity extends AppCompatActivity {
    EditText searchBar;
    RecyclerView recyclerView;
    FloatingActionButton fab;
    ProductAdapter adapter;
    ArrayList<DocumentSnapshot> allProducts = new ArrayList<>();
    ArrayList<DocumentSnapshot> filtered = new ArrayList<>();
    FirebaseFirestore db = FirebaseFirestore.getInstance();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        searchBar = findViewById(R.id.searchBar);
        recyclerView = findViewById(R.id.recyclerView);
        fab = findViewById(R.id.fabAdd);

        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new ProductAdapter(this, filtered);
        recyclerView.setAdapter(adapter);

        fab.setOnClickListener(v -> startActivity(new Intent(this, AddItemActivity.class)));

        searchBar.addTextChangedListener(new TextWatcher() {
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                filterProducts(s.toString());
            }
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void afterTextChanged(Editable s) {}
        });

        loadProducts();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProducts();
    }

    void loadProducts() {
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        db.collection(FirestoreCollection.USERS)
                .document(uid)
                .collection("grocery_items")
                .get()
                .addOnSuccessListener(value -> {
                    allProducts.clear();
                    filtered.clear();

                    for (DocumentSnapshot d : value.getDocuments()) {
                        allProducts.add(d);
                        filtered.add(d);
                    }

                    adapter.notifyDataSetChanged();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to load products", Toast.LENGTH_SHORT).show();
                    Log.e("DEBUG_LOAD", "Error: " + e.getMessage());
                });
    }

    void filterProducts(String query) {
        filtered.clear();
        for (DocumentSnapshot d : allProducts) {
            String name = d.getString("name");
            if (name != null && name.toLowerCase().contains(query.toLowerCase())) {
                filtered.add(d);
            }
        }
        adapter.notifyDataSetChanged();
    }
}