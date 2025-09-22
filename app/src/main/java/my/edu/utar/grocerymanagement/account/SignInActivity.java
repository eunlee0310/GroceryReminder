package my.edu.utar.grocerymanagement.account;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;

import my.edu.utar.grocerymanagement.MainActivity;
import my.edu.utar.grocerymanagement.R;

public class SignInActivity extends AppCompatActivity {
    private EditText email, password;
    private TextView goSignUp;
    private Button loginBtn;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_sign_in);

        mAuth = FirebaseAuth.getInstance();
        email = findViewById(R.id.emailEditText);
        password = findViewById(R.id.passwordEditText);
        loginBtn = findViewById(R.id.btnLogin);
        goSignUp = findViewById(R.id.goToSignUp);

        TextWatcher watcher = new TextWatcher() {
            public void afterTextChanged(Editable s) {
                boolean enable = !email.getText().toString().isEmpty() && !password.getText().toString().isEmpty();
                loginBtn.setEnabled(enable);
                loginBtn.setBackgroundTintList(ContextCompat.getColorStateList(SignInActivity.this, enable ? R.color.green : R.color.grey));
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        };
        email.addTextChangedListener(watcher);
        password.addTextChangedListener(watcher);

        loginBtn.setOnClickListener(v -> {
            mAuth.signInWithEmailAndPassword(email.getText().toString(), password.getText().toString())
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            Intent intent = new Intent(SignInActivity.this, MainActivity.class);
                            startActivity(intent);
                            finish();
                        } else {
                            Toast.makeText(this, "Login Failed. Wrong email or password.", Toast.LENGTH_SHORT).show();
                        }
                    });
        });

        goSignUp.setOnClickListener(v -> {
            startActivity(new Intent(SignInActivity.this, SignUpActivity.class));
        });
    }
}