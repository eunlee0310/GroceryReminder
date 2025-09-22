package my.edu.utar.grocerymanagement;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ProductListFragment extends Fragment {

    private EditText searchBar;
    private Spinner filterSpinner;
    private RecyclerView recyclerView;
    private FloatingActionButton fab;

    private ProductAdapter adapter;
    private final ArrayList<DocumentSnapshot> allProducts = new ArrayList<>();
    private final ArrayList<DocumentSnapshot> filtered = new ArrayList<>();
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    // Static categories (always available in the spinner)
    private final List<String> ALL_CATEGORIES = Arrays.asList(
            "All", "Fruits", "Vegetables", "Meat", "Dairy", "Bakery",
            "Canned", "Snacks", "Beverages", "Frozen", "Condiments", "Other"
    );

    private String currentQuery = "";
    private String currentCategory = "All";

    private final SimpleDateFormat ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_product_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        searchBar     = v.findViewById(R.id.searchBar);
        filterSpinner = v.findViewById(R.id.filter);
        recyclerView  = v.findViewById(R.id.recyclerView);
        fab           = v.findViewById(R.id.fabAdd);

        recyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        adapter = new ProductAdapter(requireContext(), filtered);
        recyclerView.setAdapter(adapter);

        fab.setOnClickListener(_v ->
                startActivity(new Intent(requireContext(), AddItemActivity.class)));

        // Normal spinner adapter, but right-align the "selected" (closed) view text
        ArrayAdapter<String> catAdapter = new ArrayAdapter<String>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                ALL_CATEGORIES
        ) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);

                // Make the TextView fill the spinner so END gravity takes effect
                tv.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                tv.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);           // stick near arrow
                tv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setPadding(dp(8), 0, dp(8), 0);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);                 // smaller if you like
                return tv;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                // Keep dropdown rows default left-aligned (or copy the alignment logic here too)
                return super.getDropDownView(position, convertView, parent);
            }
        };
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filterSpinner.setAdapter(catAdapter);

        int keepIdx = Math.max(ALL_CATEGORIES.indexOf(currentCategory), 0);
        filterSpinner.setSelection(keepIdx, false);

        filterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentCategory = ALL_CATEGORIES.get(position);
                applyFilters();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        searchBar.addTextChangedListener(new TextWatcher() {
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                currentQuery = s.toString();
                applyFilters();
            }
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void afterTextChanged(Editable s) {}
        });

        // (Optional) Android 13+ notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
        }

        loadProducts();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadProducts();
    }

    private void loadProducts() {
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
                    sortFilteredByExpiry();
                    adapter.notifyDataSetChanged();
                    applyFilters(); // re-apply current filter/search
                });
    }

    /** Apply text + category filters, then sort by earliest expiry. */
    private void applyFilters() {
        String q = currentQuery == null ? "" : currentQuery.trim().toLowerCase(Locale.getDefault());
        String cat = currentCategory == null ? "All" : currentCategory;

        filtered.clear();
        for (DocumentSnapshot d : allProducts) {
            String name = d.getString("name");
            String itemCat = d.getString("category");

            boolean matchesQuery = name != null && name.toLowerCase(Locale.getDefault()).contains(q);
            boolean matchesCategory = "All".equalsIgnoreCase(cat)
                    || (itemCat != null && itemCat.equalsIgnoreCase(cat));

            if (matchesQuery && matchesCategory) {
                filtered.add(d);
            }
        }
        sortFilteredByExpiry();
        adapter.notifyDataSetChanged();
    }

    /** Sort ascending by earliest days left to expiry (considering only qty > 0). */
    private void sortFilteredByExpiry() {
        Collections.sort(filtered, Comparator.comparingInt(this::earliestDaysLeft));
    }

    /**
     * Returns the minimum days left among all batches with quantity > 0.
     * If there is no valid batch, returns Integer.MAX_VALUE so it sinks to the bottom.
     * Negative values (already expired) will sort to the top.
     */
    private int earliestDaysLeft(DocumentSnapshot d) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> batches = (List<Map<String, Object>>) d.get("batches");
        if (batches == null || batches.isEmpty()) return Integer.MAX_VALUE;

        int nearest = Integer.MAX_VALUE;

        Calendar today = Calendar.getInstance();
        zeroTime(today);

        for (Map<String, Object> b : batches) {
            try {
                Object qObj = b.get("quantity");
                int qty = (qObj instanceof Number) ? ((Number) qObj).intValue() : 0;
                if (qty <= 0) continue;

                String dateStr = (String) b.get("expiryDate");
                if (dateStr == null) continue;

                Date exp = ymd.parse(dateStr);
                if (exp == null) continue;

                Calendar expCal = Calendar.getInstance();
                expCal.setTime(exp);
                zeroTime(expCal);

                long diffMs = expCal.getTimeInMillis() - today.getTimeInMillis();
                int days = (int) TimeUnit.MILLISECONDS.toDays(diffMs);

                if (days < nearest) nearest = days;
            } catch (ParseException ignored) {}
        }

        return nearest;
    }

    private void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
