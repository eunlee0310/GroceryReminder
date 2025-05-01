package my.edu.utar.grocerymanagement.model;

import com.google.firebase.firestore.IgnoreExtraProperties;

@IgnoreExtraProperties
public class GroceryBatch {
    public String expiryDate;
    public int quantity;

    public GroceryBatch() {} // Needed for Firestore

    public GroceryBatch(String expiryDate, int quantity) {
        this.expiryDate = expiryDate;
        this.quantity = quantity;
    }
}