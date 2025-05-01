package my.edu.utar.grocerymanagement;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.core.app.NotificationCompat;

import my.edu.utar.grocerymanagement.R;

public class NotificationAssistant {
    private static final String CHANNEL_ID = "grocery_reminder";

    private final Context context;

    public NotificationAssistant(Context context) {
        this.context = context;
    }

    public static void sendImmediate(Context context, String title, String message) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Grocery Reminders", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Reminders for groceries that are expiring or forgotten");
            manager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_grocery)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        manager.notify(title.hashCode(), builder.build());
    }
}