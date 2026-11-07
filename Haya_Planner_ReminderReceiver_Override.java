package com.hayaplanner.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

public class PlannerReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL_PREFIX = "haya_planner_alerts_v3_";

    @Override public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra("reminderId");
        if (id == null) return;
        if (intent.getBooleanExtra("recurring", false)) {
            String raw = context.getSharedPreferences("haya_planner_reminders_native", Context.MODE_PRIVATE)
                    .getString("planner_config", "{}");
            try { PlannerReminderScheduler.scheduleNext(context, new JSONObject(raw), id); }
            catch (Exception ignored) {}
        }
        show(context, intent, id);
    }

    private void show(Context context, Intent source, String id) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        String channelId = ensureChannel(context, manager);
        Intent open = new Intent(context, MainActivity.class)
                .putExtra("plannerReminder", source.getStringExtra("route"))
                .putExtra("plannerTaskId", source.getStringExtra("taskId"))
                .putExtra("plannerDayKey", source.getStringExtra("dayKey"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, requestCode("open_" + id), open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        String title = source.getStringExtra("title");
        String body = source.getStringExtra("body");
        if (title == null || title.isEmpty()) title = "تذكير Haya Planner";
        if (body == null || body.isEmpty()) body = "اضغطي لفتح القسم داخل التطبيق";
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, channelId) : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(content).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_HIGH).setWhen(System.currentTimeMillis());
        try { manager.notify(requestCode(id + System.currentTimeMillis()), builder.build()); }
        catch (SecurityException ignored) {}
    }

    private static String ensureChannel(Context context, NotificationManager manager) {
        String sound = context.getSharedPreferences(MainActivity.AUDIO_PREFS, Context.MODE_PRIVATE)
                .getString(MainActivity.AUDIO_NOTIFICATION, "");
        String suffix = sound.isEmpty() ? "default" : Integer.toHexString(sound.hashCode());
        String channelId = CHANNEL_PREFIX + suffix;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "تنبيهات Haya Planner المنبثقة", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("تنبيهات الماء والأذكار والمهام مع الصوت المختار وبانر أعلى الشاشة");
            channel.enableVibration(true);
            if (!sound.isEmpty()) {
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                channel.setSound(Uri.parse(sound), attributes);
            }
            manager.createNotificationChannel(channel);
        }
        return channelId;
    }

    private static int requestCode(String value) { return value.hashCode() & 0x7fffffff; }
}
