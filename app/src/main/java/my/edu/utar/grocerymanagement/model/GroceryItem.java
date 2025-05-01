package my.edu.utar.grocerymanagement.model;

import com.google.firebase.firestore.IgnoreExtraProperties;
import java.util.List;

@IgnoreExtraProperties
public class GroceryItem {
    public String id;
    public String name;
    public String category;
    public String notes;
    public List<GroceryBatch> batches;
    public float consumptionRate; // To keep tracking the usage rate

    public GroceryItem() {} // Needed for Firestore

    public GroceryItem(String id, String name, String category, String notes, List<GroceryBatch> batches, float consumptionRate) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.notes = notes;
        this.batches = batches;
        this.consumptionRate = consumptionRate;
    }
}