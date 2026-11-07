package com.hayaplanner.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/** Local-only reminders for planner sections and per-task Pomodoro sessions. */
public final class PlannerReminderScheduler {
    private static final String PREFS = "haya_planner_reminders_native";
    private static final String KEY_CONFIG = "planner_config";
    private static final String ACTION = "com.hayaplanner.app.PLANNER_REMINDER";
    private static final ZoneId ZONE = ZoneId.systemDefault();
    public static final List<String> IDS = Arrays.asList(
            "adhkarMorning", "adhkarEvening", "adhkarSleep", "water", "baqarah", "kahf", "khitma");

    private PlannerReminderScheduler() {}

    public static void saveConfig(Context context, String json) {
        try { new JSONObject(json); }
        catch (Exception e) { return; }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CONFIG, json).apply();
        reschedule(context);
    }

    public static void reschedule(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONFIG, "{}");
        JSONObject config;
        try { config = new JSONObject(raw); } catch (Exception e) { config = new JSONObject(); }
        boolean refreshPrayerTimes = false;
        for (String id : IDS) {
            cancel(context, id);
            JSONObject item = config.optJSONObject(id);
            if (item != null && item.optBoolean("enabled", false) && usesPrayerRelativeTime(id, item)
                    && nextPrayerTrigger(context, item) <= System.currentTimeMillis()) refreshPrayerTimes = true;
            scheduleNext(context, config, id);
        }
        if (refreshPrayerTimes) PrayerCalendarFetcher.refreshAsync(context);
    }

    public static void scheduleNext(Context context, JSONObject config, String id) {
        try {
            JSONObject item = config.optJSONObject(id);
            if (item == null || !item.optBoolean("enabled", false)) return;
            long at = nextTrigger(context, id, item);
            if (at <= System.currentTimeMillis()) return;
            schedule(context, id, at, titleFor(id), "", "", "", true);
        } catch (Exception ignored) {}
    }

    private static long nextTrigger(Context context, String id, JSONObject item) {
        if (("adhkarMorning".equals(id) || "adhkarEvening".equals(id)) && usesPrayerRelativeTime(id, item))
            return nextPrayerTrigger(context, item);
        LocalDateTime now = LocalDateTime.now(ZONE);
        if ("water".equals(id)) {
            LocalTime start = LocalTime.parse(item.optString("start", "08:30"));
            LocalTime end = LocalTime.parse(item.optString("end", "21:30"));
            int interval = Math.max(10, Math.min(240, item.optInt("interval", 30)));
            LocalDate date = now.toLocalDate();
            LocalDateTime candidate;
            if (now.toLocalTime().isBefore(start)) candidate = date.atTime(start);
            else if (!now.toLocalTime().isAfter(end)) {
                long elapsed = java.time.Duration.between(start, now.toLocalTime()).toMinutes();
                long next = (elapsed / interval + 1) * interval;
                candidate = date.atTime(start).plusMinutes(next);
                if (candidate.toLocalTime().isAfter(end)) candidate = date.plusDays(1).atTime(start);
            } else candidate = date.plusDays(1).atTime(start);
            return candidate.atZone(ZONE).toInstant().toEpochMilli();
        }
        LocalTime time = LocalTime.parse(item.optString("time", "19:00"));
        LocalDate date = now.toLocalDate();
        if ("kahf".equals(id)) {
            int target = DayOfWeek.FRIDAY.getValue();
            int delta = (target - date.getDayOfWeek().getValue() + 7) % 7;
            date = date.plusDays(delta);
            LocalDateTime candidate = date.atTime(time);
            if (!candidate.isAfter(now)) candidate = candidate.plusWeeks(1);
            return candidate.atZone(ZONE).toInstant().toEpochMilli();
        }
        LocalDateTime candidate = date.atTime(time);
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
        return candidate.atZone(ZONE).toInstant().toEpochMilli();
    }

    private static boolean usesPrayerRelativeTime(String id, JSONObject item) {
        if (!("adhkarMorning".equals(id) || "adhkarEvening".equals(id))) return false;
        String mode = item.optString("mode", "");
        if ("fixed".equals(mode)) return false;
        return "afterPrayer".equals(mode) || !item.optString("afterPrayer", "").isEmpty();
    }

    private static long nextPrayerTrigger(Context context, JSONObject item) {
        String prayerId = item.optString("afterPrayer", "");
        if (prayerId.isEmpty()) return 0L;
        int offsetMinutes = Math.max(0, Math.min(120, item.optInt("offsetMinutes", 15)));
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        org.json.JSONArray rows = PrayerScheduler.loadSchedule(context);
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                if (!prayerId.equals(row.optString("prayerId"))) continue;
                LocalDateTime prayer = LocalDateTime.parse(row.getString("date") + "T" + row.getString("time"));
                long candidate = prayer.atZone(PrayerScheduler.AMMAN).toInstant().toEpochMilli()
                        + offsetMinutes * 60_000L;
                if (candidate > now && candidate < next) next = candidate;
            } catch (Exception ignored) {}
        }
        return next == Long.MAX_VALUE ? 0L : next;
    }

    public static void schedulePomodoro(Context context, long at, String taskId, String taskTitle, String dayKey) {
        String id = "pomodoro_" + taskId;
        cancel(context, id);
        if (at > System.currentTimeMillis()) schedule(context, id, at, "انتهى وقت التركيز", taskTitle, "pomodoro", taskId, false, dayKey);
    }

    public static void cancelPomodoro(Context context, String taskId) { cancel(context, "pomodoro_" + taskId); }

    public static void scheduleTaskReminder(Context context, long at, String taskId, String taskTitle, String dayKey) {
        String id = "task_" + taskId;
        cancel(context, id);
        if (at > System.currentTimeMillis()) {
            String body = "حان وقت المهمة. اضغطي لفتح سجلها وبدء وقت الفوكس.";
            schedule(context, id, at, "موعد المهمة: " + (taskTitle == null ? "مهمة" : taskTitle), body, "task", taskId, false, dayKey);
        }
    }

    public static void cancelTaskReminder(Context context, String taskId) { cancel(context, "task_" + taskId); }

    private static String titleFor(String id) {
        switch (id) {
            case "adhkarMorning": return "أذكار الصباح";
            case "adhkarEvening": return "أذكار المساء";
            case "adhkarSleep": return "أذكار النوم";
            case "water": return "موعد شرب الماء";
            case "baqarah": return "قراءة سورة البقرة";
            case "kahf": return "قراءة سورة الكهف";
            case "khitma": return "موعد ختمتي";
            default: return "تذكير Haya Planner";
        }
    }

    private static void schedule(Context context, String id, long at, String title, String body,
                                 String route, String taskId, boolean recurring) {
        schedule(context, id, at, title, body, route, taskId, recurring, "");
    }

    private static void schedule(Context context, String id, long at, String title, String body,
                                 String route, String taskId, boolean recurring, String dayKey) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        Intent intent = new Intent(context, PlannerReminderReceiver.class).setAction(ACTION + "." + id);
        intent.putExtra("reminderId", id);
        intent.putExtra("title", title);
        intent.putExtra("body", body);
        intent.putExtra("route", route.isEmpty() ? id : route);
        intent.putExtra("taskId", taskId);
        intent.putExtra("dayKey", dayKey);
        intent.putExtra("recurring", recurring);
        PendingIntent pi = PendingIntent.getBroadcast(context, requestCode(id), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        try {
            if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else alarms.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static void cancel(Context context, String id) {
        Intent intent = new Intent(context, PlannerReminderReceiver.class).setAction(ACTION + "." + id);
        PendingIntent pi = PendingIntent.getBroadcast(context, requestCode(id), intent,
                PendingIntent.FLAG_NO_CREATE | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        if (pi != null) {
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarms != null) alarms.cancel(pi);
            pi.cancel();
        }
    }

    private static int requestCode(String id) { return id.hashCode() & 0x7fffffff; }
}
