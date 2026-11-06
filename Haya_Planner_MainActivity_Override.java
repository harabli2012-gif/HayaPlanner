package com.hayaplanner.app;

import android.app.Activity;
import android.app.AlarmManager;
import android.Manifest;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * A minimal Android shell for the offline Haya Planner interface.
 * Application data remains in this WebView's private storage.
 */
public class MainActivity extends Activity {
    private static final String DOMAIN = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + DOMAIN + "/assets/index.html";
    private static final int CHOOSE_JSON = 701;
    private static final int SAVE_JSON = 702;
    private static final int PICK_AUDIO = 703;
    static final String AUDIO_PREFS = "haya_audio_settings";
    static final String AUDIO_ADHAN = "adhan_audio_uri";
    static final String AUDIO_NOTIFICATION = "notification_audio_uri";
    private static final int REQUEST_POST_NOTIFICATIONS = 704;
    private static final int REQUEST_EXACT_ALARM = 705;

    private WebView webView;
    private android.webkit.ValueCallback<Uri[]> fileChooser;
    private String pendingBackup;
    private String pendingAudioKind;
    private MediaPlayer previewPlayer;
    private boolean pendingAdhanEnable;
    private boolean exactAlarmSettingsLaunched;
    private String pendingPrayerReminder;
    private String pendingPlannerReminder;
    private String pendingPlannerTaskId;
    private String pendingPlannerDayKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(255,248,251));
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setDefaultTextEncodingName("UTF-8");

        // The app's HTML has a secure, stable HTTPS origin. That makes localStorage
        // persistent between normal app launches without loading an external website.
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !DOMAIN.equals(uri.getHost())) return null;
                String path = uri.getPath();
                if (path == null || !path.startsWith("/assets/")) return forbidden();
                String file = path.substring("/assets/".length());
                if (file.isEmpty() || file.contains("..") || file.contains("\\") || file.startsWith("/")) return forbidden();
                try {
                    InputStream body = getAssets().open(file);
                    String mime = "application/octet-stream";
                    if (file.endsWith(".html")) mime = "text/html";
                    else if (file.endsWith(".js")) mime = "text/javascript";
                    else if (file.endsWith(".css")) mime = "text/css";
                    else if (file.endsWith(".png")) mime = "image/png";
                    else if (file.endsWith(".svg")) mime = "image/svg+xml";
                    else if (file.endsWith(".webmanifest") || file.endsWith(".json")) mime = "application/json";
                    return new WebResourceResponse(mime, "UTF-8", body);
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                            java.util.Collections.emptyMap(),
                            new java.io.ByteArrayInputStream(new byte[0]));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && DOMAIN.equals(uri.getHost())) return false;
                if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (Exception ex) { Toast.makeText(MainActivity.this, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show(); }
                    return true;
                }
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                    android.webkit.ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (fileChooser != null) fileChooser.onReceiveValue(null);
                fileChooser = callback;
                Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                open.addCategory(Intent.CATEGORY_OPENABLE);
                open.setType("application/json");
                open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivityForResult(open, CHOOSE_JSON); }
                catch (Exception e) {
                    fileChooser.onReceiveValue(null);
                    fileChooser = null;
                    Toast.makeText(MainActivity.this, "تعذر فتح ملف JSON", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        // Only the local trusted app page should load in this WebView.
        // Other URLs open in the system browser.
        webView.addJavascriptInterface(new BackupBridge(), "HayaAndroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                return loadAssetResponse(request);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && DOMAIN.equals(uri.getHost())) return false;
                if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                    return true;
                }
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                refreshNativeSettings();
                openPendingPrayerReminder();
                openPendingPlannerReminder();
            }
        });
        PrayerScheduler.rescheduleSaved(this);
        if (PrayerScheduler.isAdhanEnabled(this)) PrayerCalendarFetcher.refreshAsync(this);
        pendingPrayerReminder = getIntent().getStringExtra("reminder");
        pendingPlannerReminder = getIntent().getStringExtra("plannerReminder");
        pendingPlannerTaskId = getIntent().getStringExtra("plannerTaskId");
        pendingPlannerDayKey = getIntent().getStringExtra("plannerDayKey");
        PlannerReminderScheduler.reschedule(this);
        webView.loadUrl(START_URL);
    }

    private WebResourceResponse loadAssetResponse(WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (!"https".equals(uri.getScheme()) || !DOMAIN.equals(uri.getHost())) return null;
        String path = uri.getPath();
        if (path == null || !path.startsWith("/assets/")) return forbidden();
        String file = path.substring("/assets/".length());
        if (file.isEmpty() || file.contains("..") || file.contains("\\") || file.startsWith("/")) return forbidden();
        try {
            InputStream body = getAssets().open(file);
            String mime = "application/octet-stream";
            if (file.endsWith(".html")) mime = "text/html";
            else if (file.endsWith(".js")) mime = "text/javascript";
            else if (file.endsWith(".css")) mime = "text/css";
            else if (file.endsWith(".png")) mime = "image/png";
            else if (file.endsWith(".svg")) mime = "image/svg+xml";
            else if (file.endsWith(".webmanifest") || file.endsWith(".json")) mime = "application/json";
            return new WebResourceResponse(mime, "UTF-8", body);
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
        }
    }

    private void enableAdhan() {
        pendingAdhanEnable = true;
        getSharedPreferences(PrayerScheduler.PREFS, MODE_PRIVATE).edit()
                .putBoolean(PrayerScheduler.KEY_ADHAN_ENABLED, true).apply();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
            return;
        }
        requestExactAlarmOrFinish();
    }

    private void requestExactAlarmOrFinish() {
        if (!pendingAdhanEnable) return;
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarms != null && !alarms.canScheduleExactAlarms() && !exactAlarmSettingsLaunched) {
                exactAlarmSettingsLaunched = true;
                try {
                    Intent request = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    request.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(request, REQUEST_EXACT_ALARM);
                    return;
                } catch (Exception ignored) {}
            }
        }
        finishAdhanEnable();
    }

    private void finishAdhanEnable() {
        pendingAdhanEnable = false;
        exactAlarmSettingsLaunched = false;
        PrayerCalendarFetcher.refreshAsync(this);
        refreshNativeSettings();
        Toast.makeText(this, "تم تفعيل أذان عمّان حسب مواقيت وزارة الأوقاف الأردنية", Toast.LENGTH_LONG).show();
    }

    private void refreshNativeSettings() {
        if (webView != null) webView.evaluateJavascript("window.refreshNativeAdhanStatus && window.refreshNativeAdhanStatus()", null);
    }

    private void openPendingPrayerReminder() {
        if (webView == null || pendingPrayerReminder == null || pendingPrayerReminder.isEmpty()) return;
        String prayerId = pendingPrayerReminder;
        pendingPrayerReminder = null;
        webView.evaluateJavascript("window.openPrayerFromReminder && window.openPrayerFromReminder('" + prayerId + "')", null);
    }

    private void openPendingPlannerReminder() {
        if (webView == null || pendingPlannerReminder == null || pendingPlannerReminder.isEmpty()) return;
        String route = pendingPlannerReminder;
        String task = pendingPlannerTaskId == null ? "" : pendingPlannerTaskId;
        String day = pendingPlannerDayKey == null ? "" : pendingPlannerDayKey;
        pendingPlannerReminder = null; pendingPlannerTaskId = null; pendingPlannerDayKey = null;
        webView.evaluateJavascript("window.openPlannerReminderFromNative && window.openPlannerReminderFromNative("
                + JSONObject.quote(route) + "," + JSONObject.quote(task) + "," + JSONObject.quote(day) + ")", null);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingPrayerReminder = intent.getStringExtra("reminder");
        pendingPlannerReminder = intent.getStringExtra("plannerReminder");
        pendingPlannerTaskId = intent.getStringExtra("plannerTaskId");
        pendingPlannerDayKey = intent.getStringExtra("plannerDayKey");
        openPendingPrayerReminder();
        openPendingPlannerReminder();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshNativeSettings();
        if (webView != null) webView.evaluateJavascript("window.refreshPlannerReminderStatus && window.refreshPlannerReminderStatus()", null);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 706) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                PlannerReminderScheduler.reschedule(this);
                if (webView != null) webView.evaluateJavascript("window.refreshPlannerReminderStatus && window.refreshPlannerReminderStatus()", null);
                Toast.makeText(this, "تم تفعيل تنبيهات Haya Planner", Toast.LENGTH_SHORT).show();
            } else {
                if (webView != null) webView.evaluateJavascript("window.refreshPlannerReminderStatus && window.refreshPlannerReminderStatus()", null);
                Toast.makeText(this, "يمكن تفعيل الإشعارات لاحقًا من إعدادات Android", Toast.LENGTH_LONG).show();
            }
        }
        if (requestCode == REQUEST_POST_NOTIFICATIONS && pendingAdhanEnable) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) requestExactAlarmOrFinish();
            else {
                pendingAdhanEnable = false;
                getSharedPreferences(PrayerScheduler.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(PrayerScheduler.KEY_ADHAN_ENABLED, false).apply();
                refreshNativeSettings();
                Toast.makeText(this, "يلزم السماح بالإشعارات لعرض تنبيه الصلاة", Toast.LENGTH_LONG).show();
            }
        }
    }

    private WebResourceResponse forbidden() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                java.util.Collections.emptyMap(),
                new java.io.ByteArrayInputStream(new byte[0]));
    }

    public final class BackupBridge {
        @JavascriptInterface public boolean isAdhanEnabled() { return PrayerScheduler.isAdhanEnabled(MainActivity.this); }
        @JavascriptInterface public boolean isPrayerEnabled(String prayerId) { return PrayerScheduler.isPrayerEnabled(MainActivity.this, prayerId); }
        @JavascriptInterface public String getPrayerStatus() {
            if (!PrayerScheduler.isAdhanEnabled(MainActivity.this)) return "أذان عمّان التلقائي متوقف";
            AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
            boolean exact = Build.VERSION.SDK_INT < 31 || alarms == null || alarms.canScheduleExactAlarms();
            boolean audio = !getSharedPreferences(AUDIO_PREFS, MODE_PRIVATE).getString(AUDIO_ADHAN, "").isEmpty();
            return "أذان عمّان مفعّل" + (exact ? " — وقت دقيق" : " — وقت تقريبي؛ إذن المنبّه الدقيق غير مفعّل") + (audio ? " — الصوت محفوظ" : " — اختاري ملف صوت الأذان");
        }
        @JavascriptInterface public void setAdhanEnabled(boolean enabled) {
            runOnUiThread(() -> {
                if (enabled) enableAdhan();
                else {
                    pendingAdhanEnable = false;
                    PrayerScheduler.setAdhanEnabled(MainActivity.this, false);
                    refreshNativeSettings();
                    Toast.makeText(MainActivity.this, "تم إيقاف أذان الصلاة التلقائي", Toast.LENGTH_SHORT).show();
                }
            });
        }
        @JavascriptInterface public void setPrayerEnabled(String prayerId, boolean enabled) {
            runOnUiThread(() -> {
                PrayerScheduler.setPrayerEnabled(MainActivity.this, prayerId, enabled);
                refreshNativeSettings();
            });
        }
        @JavascriptInterface public void refreshPrayerTimes() {
            PrayerCalendarFetcher.refreshAsync(MainActivity.this);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "جارٍ تحديث مواقيت عمّان", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void syncPlannerReminders(String json) {
            PlannerReminderScheduler.saveConfig(MainActivity.this, json == null ? "{}" : json);
            runOnUiThread(() -> {
                try {
                    JSONObject config = new JSONObject(json == null ? "{}" : json);
                    boolean enabled = false;
                    java.util.Iterator<String> keys = config.keys();
                    while (keys.hasNext()) {
                        JSONObject item = config.optJSONObject(keys.next());
                        if (item != null && item.optBoolean("enabled", false)) enabled = true;
                    }
                    if (Build.VERSION.SDK_INT >= 33 && enabled
                            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 706);
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public String getPlannerReminderStatus() {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                return "إذن الإشعارات غير ممنوح؛ فعّليه من إعدادات Android";
            AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
            boolean exact = Build.VERSION.SDK_INT < 31 || alarms == null || alarms.canScheduleExactAlarms();
            return exact ? "التنبيهات المحلية مفعّلة على هذا الجهاز" : "التنبيهات مفعّلة؛ الأوقات قد تتأخر قليلًا دون إذن المنبّه الدقيق";
        }

        @JavascriptInterface
        public void schedulePomodoro(long at, String taskId, String title, String dayKey) {
            PlannerReminderScheduler.schedulePomodoro(MainActivity.this, at,
                    taskId == null ? "" : taskId, title == null ? "مهمة" : title, dayKey == null ? "" : dayKey);
        }

        @JavascriptInterface
        public void cancelPomodoro(String taskId) {
            PlannerReminderScheduler.cancelPomodoro(MainActivity.this, taskId == null ? "" : taskId);
        }

        @JavascriptInterface
        public void scheduleTaskReminder(long at, String taskId, String title, String dayKey) {
            PlannerReminderScheduler.scheduleTaskReminder(MainActivity.this, at,
                    taskId == null ? "" : taskId, title == null ? "مهمة" : title, dayKey == null ? "" : dayKey);
            if (Build.VERSION.SDK_INT >= 33) runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 707);
            });
        }

        @JavascriptInterface
        public void cancelTaskReminder(String taskId) {
            PlannerReminderScheduler.cancelTaskReminder(MainActivity.this, taskId == null ? "" : taskId);
        }

        @JavascriptInterface
        public void chooseAudio(String kind) {
            runOnUiThread(() -> {
                pendingAudioKind = "notification".equals(kind) ? "notification" : "adhan";
                Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                open.addCategory(Intent.CATEGORY_OPENABLE);
                open.setType("audio/*");
                open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try { startActivityForResult(open, PICK_AUDIO); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "تعذر فتح ملفات الصوت", Toast.LENGTH_SHORT).show(); }
            });
        }

        @JavascriptInterface
        public String getAudioStatus(String kind) {
            String key = "notification".equals(kind) ? AUDIO_NOTIFICATION : AUDIO_ADHAN;
            String uri = getSharedPreferences(AUDIO_PREFS, MODE_PRIVATE).getString(key, "");
            return uri.isEmpty() ? "لم يتم اختيار ملف صوت بعد" : "تم اختيار صوت محفوظ على هذا الجهاز";
        }

        @JavascriptInterface
        public void testAudio(String kind) {
            runOnUiThread(() -> playAudio(kind));
        }

        @JavascriptInterface
        public void stopAudio() {
            runOnUiThread(() -> stopAudioPreview());
        }

        private void playAudio(String kind) {
            String key = "notification".equals(kind) ? AUDIO_NOTIFICATION : AUDIO_ADHAN;
            String uri = getSharedPreferences(AUDIO_PREFS, MODE_PRIVATE).getString(key, "");
            if (uri.isEmpty()) {
                Toast.makeText(MainActivity.this, "اختاري ملف الصوت أولًا", Toast.LENGTH_SHORT).show();
                return;
            }
            stopAudioPreview();
            try {
                previewPlayer = new MediaPlayer();
                previewPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage("notification".equals(kind) ? AudioAttributes.USAGE_NOTIFICATION : AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                previewPlayer.setDataSource(MainActivity.this, Uri.parse(uri));
                previewPlayer.setOnPreparedListener(MediaPlayer::start);
                previewPlayer.setOnCompletionListener(mp -> stopAudioPreview());
                previewPlayer.setOnErrorListener((mp, what, extra) -> { stopAudioPreview(); Toast.makeText(MainActivity.this, "تعذر تشغيل ملف الصوت", Toast.LENGTH_SHORT).show(); return true; });
                previewPlayer.prepareAsync();
            } catch (Exception e) {
                stopAudioPreview();
                Toast.makeText(MainActivity.this, "تعذر تشغيل ملف الصوت", Toast.LENGTH_SHORT).show();
            }
        }

    private void stopAudioPreview() {
        if (previewPlayer != null) {
            try { if (previewPlayer.isPlaying()) previewPlayer.stop(); } catch (Exception ignored) {}
            try { previewPlayer.release(); } catch (Exception ignored) {}
            previewPlayer = null;
        }
    }

        @JavascriptInterface
        public void exportJson(String json, String requestedName) {
            exportContent(json, requestedName, "application/json");
        }

        @JavascriptInterface
        public void exportText(String text, String requestedName) {
            exportContent(text, requestedName, "text/plain");
        }

        private void exportContent(String content, String requestedName, String mimeType) {
            runOnUiThread(() -> {
                if (pendingBackup != null) {
                    Toast.makeText(MainActivity.this, "أكملي حفظ النسخة أولًا", Toast.LENGTH_SHORT).show();
                    return;
                }
                pendingBackup = content;
                String safe = requestedName == null ? "Haya_Planner_backup.json" :
                        requestedName.replaceAll("[^A-Za-z0-9_.-]", "_");
                Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                save.addCategory(Intent.CATEGORY_OPENABLE);
                save.setType(mimeType);
                save.putExtra(Intent.EXTRA_TITLE, safe);
                try { startActivityForResult(save, SAVE_JSON); }
                catch (Exception e) {
                    pendingBackup = null;
                    Toast.makeText(MainActivity.this, "تعذر فتح نافذة الحفظ", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CHOOSE_JSON) {
            if (fileChooser != null) {
                fileChooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileChooser = null;
            }
        } else if (requestCode == PICK_AUDIO) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingAudioKind != null) {
                Uri uri = data.getData();
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                String key = "notification".equals(pendingAudioKind) ? AUDIO_NOTIFICATION : AUDIO_ADHAN;
                getSharedPreferences(AUDIO_PREFS, MODE_PRIVATE).edit().putString(key, uri.toString()).apply();
                Toast.makeText(this, "تم حفظ الصوت على هذا الجهاز", Toast.LENGTH_SHORT).show();
                if (webView != null) webView.evaluateJavascript("window.refreshAudioStatus && window.refreshAudioStatus()", null);
            }
            pendingAudioKind = null;
        } else if (requestCode == REQUEST_EXACT_ALARM) {
            if (pendingAdhanEnable) {
                AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
                if (Build.VERSION.SDK_INT >= 31 && alarms != null && !alarms.canScheduleExactAlarms()) {
                    Toast.makeText(this, "لم يُمنح إذن الوقت الدقيق؛ سيستخدم التطبيق أقرب تنبيه يسمح به Android", Toast.LENGTH_LONG).show();
                }
                finishAdhanEnable();
            }
        } else if (requestCode == SAVE_JSON) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingBackup != null) {
                try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
                    if (stream == null) throw new IOException("Output stream unavailable");
                    stream.write(pendingBackup.getBytes(StandardCharsets.UTF_8));
                    stream.flush();
                    Toast.makeText(this, "تم حفظ النسخة الاحتياطية", Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    Toast.makeText(this, "تعذر حفظ النسخة، أعيدي المحاولة", Toast.LENGTH_LONG).show();
                }
            }
            pendingBackup = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("(function(){var x=document.getElementById('mushafReader');" +
                    "if(x&&!x.classList.contains('hidden')){document.getElementById('mushafClose').click();return true;}" +
                    "var k=document.getElementById('kahfReader');if(k&&!k.classList.contains('hidden')){document.getElementById('kahfClose').click();return true;}" +
                    "var h=document.getElementById('khitmaReader');if(h&&!h.classList.contains('hidden')){document.getElementById('khitmaClose').click();return true;}" +
                    "return false;})()", answer -> {
                if (!"true".equals(answer)) MainActivity.super.onBackPressed();
            });
        } else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (fileChooser != null) { fileChooser.onReceiveValue(null); fileChooser = null; }
        new BackupBridge().stopAudioPreview();
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
