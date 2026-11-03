# Haya Planner Android
Personal Android wrapper for Haya Planner with native exact prayer alarms and Adhan playback.

## Features
- Local planner WebView using the existing Haya Planner HTML.
- Prayer calendar from AlAdhan for Amman, Jordan, method 23.
- Exact Android alarms for Fajr, Dhuhr, Asr, Maghrib and Isha.
- User-selectable Adhan audio file stored with persistent URI permission.
- Foreground media playback at prayer time with Stop action.
- Reschedules saved alarms after reboot/time/timezone changes.
- Native prayer alarm toggles follow the planner prayer reminder toggles.

## Build
Open in Android Studio or run the included GitHub Actions workflow. The workflow outputs app-debug.apk as an artifact.

## First use
1. Install APK and allow notifications.
2. In Haya Planner > Reminders, choose an Adhan audio file once.
3. Keep automatic prayer times enabled.
4. Import your planner backup if you want historical website data inside the APK.
