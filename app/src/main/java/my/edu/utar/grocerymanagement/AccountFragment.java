package my.edu.utar.grocerymanagement;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.firebase.auth.*;

import com.google.firebase.firestore.FirebaseFirestore;

public class AccountFragment extends Fragment {

    private TextView usernameText, emailText, notificationLimitText, logoutText;
    private LinearLayout openSnoozeSettings,changeLimit, changeUsername, resetPassword;

    private FirebaseAuth mAuth;
    private FirebaseUser currentUser;
    private SharedPreferences prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Reuse your existing activity layout
        return inf.inflate(R.layout.activity_account, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);

        mAuth = FirebaseAuth.getInstance();
        currentUser = mAuth.getCurrentUser();
        prefs = requireContext().getSharedPreferences("UserPrefs", 0);

        usernameText = v.findViewById(R.id.username);
        emailText = v.findViewById(R.id.email);
        notificationLimitText = v.findViewById(R.id.notification_limit);
        changeLimit = v.findViewById(R.id.changeLimit);
        changeUsername = v.findViewById(R.id.changeUser);
        resetPassword = v.findViewById(R.id.resetPass);
        logoutText = v.findViewById(R.id.logout);
        openSnoozeSettings = v.findViewById(R.id.openSnoozeSettings);

        if (currentUser != null) {
            String uid = currentUser.getUid();
            String email = currentUser.getEmail();
            emailText.setText(email != null ? email : "No email");

            FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(uid)
                    .get()
                    .addOnSuccessListener(ds -> {
                        if (ds.exists() && ds.contains("username")) {
                            String username = ds.getString("username");
                            usernameText.setText(username != null ? username : "No username");
                        } else {
                            usernameText.setText("No username");
                        }
                    })
                    .addOnFailureListener(e -> {
                        usernameText.setText("No username");
                        Toast.makeText(requireContext(), "Failed to load username", Toast.LENGTH_SHORT).show();
                    });
        }

        changeUsername.setOnClickListener(_v -> showChangeUsernameDialog());
        resetPassword.setOnClickListener(_v -> showResetPasswordDialog());

        int savedLimit = prefs.getInt("maxNotificationsPerDay", 5);
        notificationLimitText.setText(String.valueOf(savedLimit));

        changeLimit.setOnClickListener(_v -> showNotificationPicker());
        openSnoozeSettings.setOnClickListener(_v ->
                startActivity(new Intent(requireContext(), SnoozeSettingActivity.class)));

        logoutText.setOnClickListener(_v -> {
            mAuth.signOut();
            Toast.makeText(requireContext(), "Logged out", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(requireContext(), my.edu.utar.grocerymanagement.account.SignInActivity.class));
            requireActivity().finish();
        });
    }

    private void showNotificationPicker() {
        NumberPicker picker = new NumberPicker(requireContext());
        picker.setMinValue(1);
        picker.setMaxValue(7);
        picker.setWrapSelectorWheel(true);

        int current = Integer.parseInt(notificationLimitText.getText().toString());
        picker.setValue(current);

        new AlertDialog.Builder(requireContext())
                .setTitle("Set Daily Notification Limit")
                .setView(picker)
                .setPositiveButton("OK", (d, w) -> {
                    int selected = picker.getValue();
                    prefs.edit().putInt("maxNotificationsPerDay", selected).apply();
                    notificationLimitText.setText(String.valueOf(selected));
                    Toast.makeText(requireContext(), "Updated to " + selected + " notifications/day", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showChangeUsernameDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Change Username");

        final EditText input = new EditText(requireContext());
        input.setHint("Enter new username");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty()) {
                Toast.makeText(requireContext(), "Username cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }

            FirebaseUser currentUser = mAuth.getCurrentUser();
            if (currentUser != null) {
                UserProfileChangeRequest profileUpdates = new UserProfileChangeRequest.Builder()
                        .setDisplayName(newName)
                        .build();

                currentUser.updateProfile(profileUpdates)
                        .addOnCompleteListener(task -> {
                            if (task.isSuccessful()) {
                                FirebaseFirestore.getInstance()
                                        .collection("users")
                                        .document(currentUser.getUid())
                                        .update("username", newName)
                                        .addOnSuccessListener(unused -> {
                                            usernameText.setText(newName);
                                            Toast.makeText(requireContext(), "Username updated", Toast.LENGTH_SHORT).show();
                                        })
                                        .addOnFailureListener(e -> {
                                            Toast.makeText(requireContext(), "Failed to update Firestore username: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                        });
                            } else {
                                Toast.makeText(requireContext(), "Failed to update FirebaseAuth profile", Toast.LENGTH_SHORT).show();
                            }
                        });
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showResetPasswordDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Reset Password");

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText oldPass = new EditText(requireContext());
        oldPass.setHint("Enter current password");
        oldPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(oldPass);

        final EditText newPass = new EditText(requireContext());
        newPass.setHint("Enter new password");
        newPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(newPass);

        builder.setView(layout);

        builder.setPositiveButton("Change", (dialog, which) -> {
            String oldPassword = oldPass.getText().toString().trim();
            String newPassword = newPass.getText().toString().trim();

            if (oldPassword.isEmpty() || newPassword.isEmpty()) {
                Toast.makeText(requireContext(), "Please fill in both fields", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!isValidPassword(newPassword)) {
                Toast.makeText(requireContext(), "New password too weak. Use uppercase, lowercase, number, and special character.", Toast.LENGTH_LONG).show();
                return;
            }

            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null && user.getEmail() != null) {
                String email = user.getEmail();
                FirebaseAuth mAuth = FirebaseAuth.getInstance();
                mAuth.signInWithEmailAndPassword(email, oldPassword)
                        .addOnSuccessListener(authResult -> {
                            user.updatePassword(newPassword)
                                    .addOnSuccessListener(unused -> {
                                        Toast.makeText(requireContext(), "Password updated successfully", Toast.LENGTH_SHORT).show();
                                    })
                                    .addOnFailureListener(e -> {
                                        Toast.makeText(requireContext(), "Password update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                    });
                        })
                        .addOnFailureListener(e -> {
                            Toast.makeText(requireContext(), "Re-authentication failed: Incorrect current password", Toast.LENGTH_LONG).show();
                        });
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private boolean isValidPassword(String password) {
        return password.length() >= 6 &&
                password.matches(".*[A-Z].*") &&
                password.matches(".*[a-z].*") &&
                password.matches(".*\\d.*") &&
                password.matches(".*[^a-zA-Z0-9].*");
    }
}
