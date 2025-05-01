package my.edu.utar.grocerymanagement;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.google.firebase.firestore.DocumentSnapshot;
import java.text.SimpleDateFormat;
import java.util.*;

public class ProductAdapter extends RecyclerView.Adapter<ProductAdapter.ViewHolder> {
    private final Context context;
    private final List<DocumentSnapshot> productList;

    public ProductAdapter(Context ctx, List<DocumentSnapshot> items) {
        context = ctx;
        productList = items;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView image;
        TextView name, qty, expire;

        public ViewHolder(View view) {
            super(view);
            image = view.findViewById(R.id.productImage);
            name = view.findViewById(R.id.productName);
            qty = view.findViewById(R.id.productQty);
            expire = view.findViewById(R.id.productExpireIn);
        }
    }

    @Override
    public ProductAdapter.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_card, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder h, int pos) {
        DocumentSnapshot doc = productList.get(pos);
        String name = doc.getString("name");
        List<Map<String, Object>> batches = (List<Map<String, Object>>) doc.get("batches");

        Log.d("DEBUG_BATCHES", "Batches for " + name + ": " + (batches != null ? batches.size() : "null"));

        int totalQty = 0;
        int nearestDays = Integer.MAX_VALUE;

        if (batches != null) {
            for (Map<String, Object> b : batches) {
                Object qObj = b.get("quantity");
                int qty = (qObj instanceof Long) ? ((Long) qObj).intValue() : (qObj instanceof Double) ? ((Double) qObj).intValue() : 0;
                totalQty += qty;

                try {
                    Date exp = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse((String) b.get("expiryDate"));

                    // Calculate days remaining using Calendar for better control
                    Calendar expCalendar = Calendar.getInstance();
                    expCalendar.setTime(exp);

                    // Set current time to midnight for accurate day calculation
                    Calendar currentCalendar = Calendar.getInstance();
                    currentCalendar.set(Calendar.HOUR_OF_DAY, 0);
                    currentCalendar.set(Calendar.MINUTE, 0);
                    currentCalendar.set(Calendar.SECOND, 0);
                    currentCalendar.set(Calendar.MILLISECOND, 0);

                    int days = (int) ((expCalendar.getTimeInMillis() - currentCalendar.getTimeInMillis()) / (1000 * 60 * 60 * 24));

                    if (days < nearestDays) nearestDays = days;

                } catch (Exception ignored) {}
            }
        }

        h.name.setText(name);

        if (totalQty == 0) {
            h.qty.setText("Qty: 0");
            h.expire.setText("Expires in: -");
            h.itemView.setAlpha(0.4f); // faded
        } else {
            h.qty.setText("Qty: " + totalQty);
            h.expire.setText("Expires in: " + nearestDays + "d");
            h.itemView.setAlpha(1f);
        }

        String img = doc.getString("imageUrl");
        if (img == null || img.equals("default")) {
            h.image.setImageResource(R.drawable.ic_grocery);
        } else {
            Glide.with(context).load(img).placeholder(R.drawable.ic_grocery).into(h.image);
        }

        h.itemView.setOnClickListener(v -> {
            Intent i = new Intent(context, ProductDetail.class);
            i.putExtra("productId", doc.getId());
            context.startActivity(i);
        });

        Log.d("DEBUG_ADAPTER", "Binding product: " + name);
    }

    @Override
    public int getItemCount() {
        return productList.size();
    }
}