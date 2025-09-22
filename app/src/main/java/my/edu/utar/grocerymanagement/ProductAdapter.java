package my.edu.utar.grocerymanagement;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.firebase.firestore.DocumentSnapshot;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ProductAdapter extends RecyclerView.Adapter<ProductAdapter.VH> {

    private final Context ctx;
    private final List<DocumentSnapshot> items;
    private final SimpleDateFormat ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    public ProductAdapter(Context ctx, List<DocumentSnapshot> items) {
        this.ctx = ctx;
        this.items = items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_card, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DocumentSnapshot doc = items.get(position);

        String name = doc.getString("name");
        h.tvName.setText(name == null ? "—" : name);

        // Qty: sum of all batches qty
        int totalQty = 0;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> batches = (List<Map<String, Object>>) doc.get("batches");
        if (batches != null) {
            for (Map<String, Object> b : batches) {
                Object qObj = b.get("quantity");
                if (qObj instanceof Number) totalQty += ((Number) qObj).intValue();
            }
        }
        h.tvQty.setText("Qty: " + totalQty);

        // ======= IMAGE: local -> remote -> category placeholder =======
        String category = doc.getString("category");
        int placeholder = getCategoryPlaceholder(category);

        String localPath = doc.getString("imageLocalPath");
        String remoteUrl = doc.getString("imageUrl"); // might be "default", "local", "placeholder:Cat", or a real URL

        File localFile = (!TextUtils.isEmpty(localPath)) ? new File(localPath) : null;
        boolean hasLocal = localFile != null && localFile.exists();
        boolean hasRemote = remoteUrl != null
                && !remoteUrl.equalsIgnoreCase("default")
                && !remoteUrl.equalsIgnoreCase("local")
                && !remoteUrl.startsWith("placeholder:");

        if (hasLocal) {
            Glide.with(ctx)
                    .load(localFile)
                    .placeholder(placeholder).error(placeholder).fallback(placeholder)
                    .into(h.ivImage);
        } else if (hasRemote) {
            Glide.with(ctx)
                    .load(remoteUrl)
                    .placeholder(placeholder).error(placeholder).fallback(placeholder)
                    .into(h.ivImage);
        } else {
            h.ivImage.setImageResource(placeholder);
        }

        // ======= Expiry presentation (B1/B2 logic) =======
        h.tvExpirePrimary.setVisibility(View.VISIBLE);
        h.tvExpireSecondary.setVisibility(View.GONE);
        h.tvExpirePrimary.setTextColor(Color.parseColor("#444444"));
        h.tvExpireSecondary.setTextColor(Color.parseColor("#444444"));

        if (batches == null || batches.isEmpty()) {
            h.tvExpirePrimary.setText("—");
        } else {
            List<Map<String, Object>> sorted = new java.util.ArrayList<>(batches);
            Collections.sort(sorted, Comparator.comparing(b -> {
                String d = (String) b.get("expiryDate");
                try {
                    return ymd.parse(d == null ? "" : d);
                } catch (Exception e) {
                    return null;
                }
            }, (a, b) -> {
                if (a == null && b == null) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                return a.compareTo(b);
            }));

            Calendar today = Calendar.getInstance();
            zeroTime(today);

            int seenValid = 0;
            Integer b1Days = null, b2Days = null;
            boolean b1Expired = false, b2Exists = false;

            for (Map<String, Object> b : sorted) {
                int qty = getInt(b.get("quantity"));
                if (qty <= 0) continue;
                String dateStr = (String) b.get("expiryDate");
                if (TextUtils.isEmpty(dateStr)) continue;

                Integer days = daysLeft(dateStr, today);
                if (days == null) continue;

                seenValid++;
                if (seenValid == 1) {
                    b1Days = days;
                    b1Expired = (days < 0);
                } else if (seenValid == 2) {
                    b2Days = days;
                    b2Exists = true;
                    break;
                }
            }

            if (seenValid == 0) {
                h.tvExpirePrimary.setText("—");
            } else {
                int dLeft = (b1Days == null) ? 0 : b1Days;

                if (dLeft <= 0) {
                    // B1 expired (includes "today")
                    h.tvExpirePrimary.setText("B1: Expired");
                    h.tvExpirePrimary.setTextColor(Color.RED);

                    if (b2Exists && b2Days != null) {
                        h.tvExpireSecondary.setVisibility(View.VISIBLE);
                        if (b2Days <= 0) {
                            h.tvExpireSecondary.setText("B2: Expired");
                            h.tvExpireSecondary.setTextColor(Color.RED);
                        } else {
                            h.tvExpireSecondary.setText("B2: Expires in: " + b2Days + " d");
                            if (b2Days <= 3) h.tvExpireSecondary.setTextColor(Color.RED);
                        }
                    } else {
                        h.tvExpireSecondary.setVisibility(View.GONE);
                    }
                } else {
                    // B1 in the future
                    h.tvExpirePrimary.setText("Expires in: " + dLeft + " d");
                    if (dLeft <= 3) h.tvExpirePrimary.setTextColor(Color.RED);
                    h.tvExpireSecondary.setVisibility(View.GONE);
                }
            }
        }

        // click → open detail
        h.itemView.setOnClickListener(v -> {
            Intent i = new Intent(ctx, ProductDetail.class);
            i.putExtra("productId", doc.getId());
            ctx.startActivity(i);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivImage;
        TextView tvName, tvQty, tvExpirePrimary, tvExpireSecondary;

        VH(@NonNull View itemView) {
            super(itemView);
            ivImage = itemView.findViewById(R.id.productImage);
            tvName  = itemView.findViewById(R.id.productName);
            tvQty   = itemView.findViewById(R.id.productQty);
            tvExpirePrimary   = itemView.findViewById(R.id.productExpireIn);
            tvExpireSecondary = itemView.findViewById(R.id.productExpireSecondary);
        }
    }

    private static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private Integer daysLeft(String ymdDate, Calendar today) {
        try {
            Calendar exp = Calendar.getInstance();
            exp.setTime(ymd.parse(ymdDate));
            zeroTime(exp);
            long diffMs = exp.getTimeInMillis() - today.getTimeInMillis();
            return (int) TimeUnit.MILLISECONDS.toDays(diffMs); // can be negative
        } catch (ParseException e) {
            return null;
        }
    }

    private static int getInt(Object n) {
        return (n instanceof Number) ? ((Number) n).intValue() : 0;
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
}
