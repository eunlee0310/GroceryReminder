package my.edu.utar.grocerymanagement;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

public class ConfirmNameCategoryDialog {

    public interface OnConfirmListener {
        void onConfirm(String name, String category, boolean updateCatalog);
    }

    public static void show(Context ctx,
                            String barcode,
                            String name,
                            String category,
                            OnConfirmListener onConfirm,
                            Runnable onCancel) {

        View v = LayoutInflater.from(ctx)
                .inflate(R.layout.activity_confirm_name_category_dialog, null, false);

        EditText nameInput        = v.findViewById(R.id.nameEdit);
        Spinner categorySpinner   = v.findViewById(R.id.categorySpinner);
        CheckBox updateCatalogChk = v.findViewById(R.id.updateCatalogCheck);

        // Free-text name (editable)
        nameInput.setText(name);

        // ✅ Same categories as AddItemActivity
        String[] categories = {"Fruits", "Vegetables", "Meat", "Dairy", "Bakery", "Canned", "Snacks", "Beverages", "Frozen", "Condiments", "Other"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                ctx, android.R.layout.simple_spinner_dropdown_item, categories);
        categorySpinner.setAdapter(adapter);

        // Preselect
        int sel = 0;
        if (!TextUtils.isEmpty(category)) {
            for (int i = 0; i < categories.length; i++) {
                if (categories[i].equalsIgnoreCase(category)) { sel = i; break; }
            }
        }
        categorySpinner.setSelection(sel);

        new AlertDialog.Builder(ctx)
                .setTitle("Confirm product")
                .setMessage("Barcode: " + barcode + "\nIs the name & category correct?")
                .setView(v)
                .setPositiveButton("Yes", (d, w) -> {
                    String finalName = nameInput.getText().toString().trim();
                    String finalCategory = String.valueOf(categorySpinner.getSelectedItem());
                    if (TextUtils.isEmpty(finalName)) {
                        Toast.makeText(ctx, "Name can’t be empty.", Toast.LENGTH_SHORT).show();
                        if (onCancel != null) onCancel.run();
                        return;
                    }
                    if (onConfirm != null) {
                        // ⬇️ Pass user’s choice for updating shared catalog
                        onConfirm.onConfirm(finalName, finalCategory, updateCatalogChk.isChecked());
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> { if (onCancel != null) onCancel.run(); })
                .show();
    }
}
