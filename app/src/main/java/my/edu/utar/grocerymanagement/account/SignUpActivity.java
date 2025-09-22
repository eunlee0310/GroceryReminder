package my.edu.utar.grocerymanagement.account;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import my.edu.utar.grocerymanagement.R;
import my.edu.utar.grocerymanagement.FirestoreCollection;

public class SignUpActivity extends AppCompatActivity {

    EditText username, email, password;
    Button signUpBtn;
    FirebaseAuth mAuth;
    FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_up);

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        username = findViewById(R.id.usernameEditText);
        email = findViewById(R.id.emailEditText);
        password = findViewById(R.id.passwordEditText);
        signUpBtn = findViewById(R.id.btnSignup);

        TextWatcher watcher = new TextWatcher() {
            public void afterTextChanged(Editable s) {
                String pass = password.getText().toString();
                String user = username.getText().toString();
                String mail = email.getText().toString();
                boolean valid = isValidPassword(pass) && !user.isEmpty() && !mail.isEmpty();

                signUpBtn.setEnabled(valid);
                signUpBtn.setBackgroundTintList(ContextCompat.getColorStateList(SignUpActivity.this, valid ? R.color.green : R.color.grey));
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
        };

        username.addTextChangedListener(watcher);
        email.addTextChangedListener(watcher);
        password.addTextChangedListener(watcher);

        signUpBtn.setOnClickListener(v -> {
            String user = username.getText().toString().trim();
            String mail = email.getText().toString().trim();
            String pass = password.getText().toString();

            checkUsernameExists(user, exists -> {
                if (exists) {
                    Toast.makeText(this, "Username already existed", Toast.LENGTH_SHORT).show();
                } else {
                    mAuth.createUserWithEmailAndPassword(mail, pass)
                            .addOnCompleteListener(task -> {
                                if (task.isSuccessful()) {
                                    String uid = Objects.requireNonNull(mAuth.getCurrentUser()).getUid();
                                    Map<String, Object> userMap = new HashMap<>();
                                    userMap.put("username", user);
                                    userMap.put("email", mail);

                                    // Save directly to users/uid (flattened)
                                    db.collection(FirestoreCollection.USERS)
                                            .document(uid)
                                            .set(userMap)
                                            .addOnSuccessListener(unused -> {
                                                Toast.makeText(SignUpActivity.this, "Account created.", Toast.LENGTH_SHORT).show();
                                                startActivity(new Intent(SignUpActivity.this, SignInActivity.class));
                                                finish();
                                            })
                                            .addOnFailureListener(e -> {
                                                Toast.makeText(SignUpActivity.this, "Failed to save user: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                            });

                                } else {
                                    Exception exception = task.getException();
                                    if (exception instanceof FirebaseAuthUserCollisionException) {
                                        Toast.makeText(SignUpActivity.this, "Email already existed", Toast.LENGTH_SHORT).show();
                                    } else if (exception instanceof FirebaseAuthInvalidCredentialsException) {
                                        Toast.makeText(SignUpActivity.this,"Incorrect email format", Toast.LENGTH_SHORT).show();
                                    } else {
                                        Toast.makeText(SignUpActivity.this, "Signup failed.", Toast.LENGTH_SHORT).show();
                                    }
                                }
                            });
                }
            });
        });

        findViewById(R.id.goToLogin).setOnClickListener(v -> {
            startActivity(new Intent(SignUpActivity.this, SignInActivity.class));
        });
    }

    private boolean isValidPassword(String password) {
        return password.length() >= 6 &&
                password.matches(".*[A-Z].*") &&
                password.matches(".*[a-z].*") &&
                password.matches(".*\\d.*") &&
                password.matches(".*[^a-zA-Z0-9].*");
    }

    private void checkUsernameExists(String username, OnUsernameCheckListener listener) {
        db.collection(FirestoreCollection.USERS)
                .whereEqualTo("username", username)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        boolean exists = !task.getResult().isEmpty();
                        listener.onCheck(exists);
                    } else {
                        listener.onCheck(false);
                    }
                });
    }

    interface OnUsernameCheckListener {
        void onCheck(boolean exists);
    }
}
