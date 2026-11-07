package com.hayaplanner.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fetches the Jordan Ministry of Awqaf method 23 calendar for Amman. */
public final class PrayerCalendarFetcher {
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private PrayerCalendarFetcher() {}

    public static void refreshAsync(Context context) {
        if (!running.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            boolean updated = false;
            try {
                JSONArray fresh = new JSONArray();
                YearMonth month = YearMonth.now(PrayerScheduler.AMMAN);
                appendMonth(fresh, month);
                appendMonth(fresh, month.plusMonths(1));
                appendMonth(fresh, month.plusMonths(2));
                if (fresh.length() > 0) {
                    PrayerScheduler.saveSchedule(app, fresh);
                    updated = true;
                }
            } catch (Exception ignored) {
                // Keep the last saved schedule if the network is unavailable.
            } finally {
                PrayerScheduler.rescheduleSaved(app);
                if (updated) PlannerReminderScheduler.reschedule(app);
                running.set(false);
            }
        }, "HayaAmmanPrayerRefresh").start();
    }

    private static void appendMonth(JSONArray out, YearMonth month) throws Exception {
        String url = "https://api.aladhan.com/v1/calendarByCity/" + month.getYear() + "/" + month.getMonthValue()
                + "?city=Amman&country=Jordan&method=23&school=0";
        JSONObject root = new JSONObject(get(url));
        JSONArray days = root.getJSONArray("data");
        Map<String, String> apiNames = new LinkedHashMap<>();
        apiNames.put("Fajr", "prayer_fajr");
        apiNames.put("Dhuhr", "prayer_dhuhr");
        apiNames.put("Asr", "prayer_asr");
        apiNames.put("Maghrib", "prayer_maghrib");
        apiNames.put("Isha", "prayer_isha");
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("prayer_fajr", "صلاة الفجر");
        labels.put("prayer_dhuhr", "صلاة الظهر");
        labels.put("prayer_asr", "صلاة العصر");
        labels.put("prayer_maghrib", "صلاة المغرب");
        labels.put("prayer_isha", "صلاة العشاء");

        LocalDate today = LocalDate.now(PrayerScheduler.AMMAN);
        LocalDate horizon = today.plusDays(75);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.getJSONObject(i);
            String dateText = day.getJSONObject("date").getJSONObject("gregorian").getString("date");
            LocalDate date = LocalDate.parse(dateText, formatter);
            if (date.isBefore(today) || date.isAfter(horizon)) continue;
            JSONObject times = day.getJSONObject("timings");
            for (Map.Entry<String, String> prayer : apiNames.entrySet()) {
                String time = cleanTime(times.optString(prayer.getKey()));
                if (time.isEmpty()) continue;
                JSONObject row = new JSONObject();
                row.put("date", date.toString());
                row.put("time", time);
                row.put("prayerId", prayer.getValue());
                row.put("label", labels.get(prayer.getValue()));
                out.put(row);
            }
        }
    }

    private static String cleanTime(String raw) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(raw == null ? "" : raw);
        if (!match.find()) return "";
        return String.format(java.util.Locale.US, "%02d:%s", Integer.parseInt(match.group(1)), match.group(2));
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }
}
