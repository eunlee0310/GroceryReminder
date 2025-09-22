package my.edu.utar.grocerymanagement;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;

import java.util.concurrent.TimeUnit;

import my.edu.utar.grocerymanagement.account.SignInActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

//        SharedPreferences prefs = getSharedPreferences("notification_count", MODE_PRIVATE);
//        prefs.edit().clear().apply();  String[] prefsList = { "notify_state",  "notification_count", "seen_notifications", "active_snooze", "snooze_retries" };
//        for (String name : prefsList) { getSharedPreferences(name, MODE_PRIVATE).edit().clear().apply();
//        }
//        Log.i("MainActivity", "Notification prefs cleared for testing");

        // Exact alarm permission (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this,
                        "This app requires Exact Alarm permission. Please enable it, then reopen the app.",
                        Toast.LENGTH_LONG).show();
                finishAffinity();
                System.exit(0);
                return;
            }
        }

        // Daily background work
        PeriodicWorkRequest dailyWorkRequest =
                new PeriodicWorkRequest.Builder(BackgroundWorker.class, 1, TimeUnit.DAYS)
                        .addTag("daily_product_details_update")
                        .build();

        WorkManager.getInstance(getApplicationContext()).enqueueUniquePeriodicWork(
                "DailyProductDetails",
                ExistingPeriodicWorkPolicy.REPLACE,
                dailyWorkRequest
        );

        // Auth gate
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, SignInActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        // Morning kick (7am)
        NotificationService.scheduleMorningCheck(this);

        // Android 13+ notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }

        BottomNavigationView bottom = findViewById(R.id.bottom_nav);
        bottom.setItemActiveIndicatorEnabled(false);
        bottom.setItemRippleColor(null);

        // Add all tabs once, then hide/show by tag
        FragmentManager fm = getSupportFragmentManager();
        if (b == null) {
            Fragment home   = new ProductListFragment();
            Fragment scan   = new BarcodeScannerFragment();
            Fragment notif  = new NotificationCenterFragment();
            Fragment acc    = new AccountFragment();

            FragmentTransaction tx = fm.beginTransaction();
            tx.add(R.id.fragment_container, home,  "home");
            tx.add(R.id.fragment_container, scan,  "scan").hide(scan);
            tx.add(R.id.fragment_container, notif, "notif").hide(notif);
            tx.add(R.id.fragment_container, acc,   "account").hide(acc);
            tx.commitNow(); // ensure fragments are attached before switching
        } else {
            fm.executePendingTransactions();
        }

        bottom.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home)               { show("home");   return true; }
            else if (id == R.id.nav_scan)          { show("scan");   return true; }
            else if (id == R.id.nav_notifications) { show("notif");  return true; }
            else if (id == R.id.nav_account)       { show("account");return true; }
            return false;
        });

        // If launched from a notification, jump straight to Notification Center
        boolean openNotifications = getIntent() != null
                && getIntent().getBooleanExtra("open_notifications", false);

        if (openNotifications) {
            show("notif"); // switch content immediately
            bottom.setSelectedItemId(R.id.nav_notifications); // sync icon
        } else {
            bottom.setSelectedItemId(R.id.nav_home);
        }
    }

    // Handle taps when activity is already running (uses FLAG_ACTIVITY_SINGLE_TOP in content intent)
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("open_notifications", false)) {
            getSupportFragmentManager().executePendingTransactions();
            show("notif");
            BottomNavigationView bottom = findViewById(R.id.bottom_nav);
            if (bottom != null) bottom.setSelectedItemId(R.id.nav_notifications);
        }
    }

    public void selectBottomTab(int menuId) {
        BottomNavigationView bottom = findViewById(R.id.bottom_nav);
        if (bottom != null && bottom.getSelectedItemId() != menuId) {
            bottom.setSelectedItemId(menuId);
        }
    }

    private void show(String tag) {
        FragmentManager fm = getSupportFragmentManager();
        Fragment home  = fm.findFragmentByTag("home");
        Fragment scan  = fm.findFragmentByTag("scan");
        Fragment notif = fm.findFragmentByTag("notif");
        Fragment acc   = fm.findFragmentByTag("account");
        Fragment toShow = fm.findFragmentByTag(tag);

        FragmentTransaction tx = fm.beginTransaction();
        tx.setReorderingAllowed(true);

        // Hide all
        if (home  != null) tx.hide(home);
        if (scan  != null) tx.hide(scan);
        if (notif != null) tx.hide(notif);
        if (acc   != null) tx.hide(acc);

        // Show target
        if (toShow != null) tx.show(toShow);

        // Lifecycle control: only visible fragment is RESUMED
        if (home  != null) tx.setMaxLifecycle(home,  Lifecycle.State.STARTED);
        if (scan  != null) tx.setMaxLifecycle(scan,  Lifecycle.State.STARTED);
        if (notif != null) tx.setMaxLifecycle(notif, Lifecycle.State.STARTED);
        if (acc   != null) tx.setMaxLifecycle(acc,   Lifecycle.State.STARTED);
        if (toShow != null) tx.setMaxLifecycle(toShow, Lifecycle.State.RESUMED);

        if (toShow != null) tx.setPrimaryNavigationFragment(toShow);
        tx.commit();
    }
}
