# Mi Complication Bridge

Keeps the built-in Xiaomi complications (steps, calories, heart rate, weather...)
updating on **custom WFF watch faces** on the Xiaomi Watch 5, with no ADB, no
root and no repeated manual steps.

## Why Xiaomi Complications Break (Root Cause)

Xiaomi's `MiWatchFace` app (`com.xiaomi.miwear.watchface`) breaks on third-party
Watch Face Format (WFF) watch faces because its engineers built the complication
providers under the assumption that `MiWatchFace` would always be the active
wallpaper host. When a custom WFF face is active, three architectural flaws in
Xiaomi's app cause the update pipeline to collapse:

1. **Zero-Second Poll Period (`UPDATE_PERIOD_SECONDS = 0`):** In `MiWatchFace`'s
   `AndroidManifest.xml`, every complication service (`StepComplicationDataSource`,
   `HeartRateComplicationDataSource`, `CalorieComplicationDataSource`,
   `WeatherComplicationService`, etc.) is hardcoded with
   `android.support.wearable.complications.UPDATE_PERIOD_SECONDS = "0"`. This
   instructs Wear OS (`com.google.wear.services`) to **never** poll or refresh
   these complications on a timer. Wear OS binds to the service once when the
   watch face loads, reads the initial cached value, and immediately unbinds —
   waiting passively for Xiaomi's app to push updates via `requestUpdateAll()`
   (implemented in Xiaomi's `pl.smali`).
2. **Unprotected Background Push Pipeline (`ft0` / `MiHealthServiceManager`):**
   Instead of subscribing to standard Wear OS Health Services, Xiaomi's
   complication base class (`ka.smali`) registers listeners with an internal
   data-provider manager (`rr.smali` / `de0.smali`). That manager uses
   `ft0.smali` (`MiHealthServiceManager`) to bind to a private Xiaomi service
   (`com.xiaomi.miwear.healthservices/.MiHealthService` with action
   `BIND_MI_HEALTH_SERVICE`), which pushes sensor and health updates every ~5
   seconds via `topicReportCallback`. When a built-in Xiaomi watch face is
   active, `MiWatchFace` hosts the active `WallpaperService`, giving the process
   foreground/perceptible priority (`SVC`) so `ft0` stays connected. When you
   switch to a custom WFF watch face, rendering moves to Google's
   `com.google.wear.watchface.runtime`, leaving `MiWatchFace` with **zero
   foreground components and zero bound services**.
3. **No Recovery from Android's Cached-App Freezer (`am_freeze`):**
   - When a WFF watch face is active and `MiWatchFace` has no bound clients,
     Android's activity manager demotes `com.xiaomi.miwear.watchface` to standby
     bucket `5` (`RESTRICTED`) and suspends the entire process in the
     **Cached-App Freezer (`am_freeze`)** within minutes.
   - Once frozen, `MiWatchFace` can no longer receive the 5-second
     `topicReportCallback` binder transactions from `MiHealthService`, and its 3
     client `ConnectionRecord`s in `MiHealthService` are dropped.
   - Because `ft0` (`MiHealthServiceManager`) only initializes its subscription
     when `ka.onComplicationActivated()` is called by Wear OS (when a watch face
     is first selected or edited), even if `MiWatchFace` briefly unfreezes
     later, its `MiHealthService` subscription is already gone and nothing inside
     Xiaomi's code re-subscribes.
   - As a result, `pl.requestUpdateAll()` is never called again, and the WFF
     runtime (`com.google.wear.watchface.runtime`) displays the last cached
     number forever.

## How it works

One foreground service (`KeepAliveService`) holds a persistent `bindService()`
connection (`BIND_AUTO_CREATE | BIND_IMPORTANT`) to Xiaomi's `TransmitRXService`
in `com.xiaomi.miwear.watchface`:

- Our process is foreground → we are never frozen by Android.
- Android's cached-app freezer will not freeze a process that another,
  non-frozen process holds a binding to → the Xiaomi watch-face process stays
  runnable, keeps its MiHealthService subscription, and the ~5-second topic
  pushes keep refreshing the complications.
- If the binding dies (`onBindingDied` / `onServiceDisconnected`), the service
  re-binds automatically with a backoff (`5s` → `30s` → `5m`). Re-binding also
  re-creates the Xiaomi process if it was killed.
- If `onNullBinding` fires (Xiaomi's `onBind()` returns null for unrecognized
  actions), the connection is **held**, not dropped — a registered
  ServiceConnection still pins the provider out of the freezer.
- Every (re)connect and every screen wake (throttled to once a minute)
  automatically broadcasts `ACTION_REQUEST_UPDATE_ALL` to SysUI
  (`com.google.wear.services` and `com.google.android.wearable.app`) across all
  35 Xiaomi complication data sources — the recovery path for a watch that was
  already in the broken state before the app was installed.
- All persisted state lives in **device-protected storage** (the service is
  `directBootAware` and starts at `LOCKED_BOOT_COMPLETED`, before the user
  unlocks the watch — credential-encrypted prefs would crash it there).
- The service restarts at boot (`LOCKED_BOOT_COMPLETED` / `BOOT_COMPLETED`)
  and after app updates (`MY_PACKAGE_REPLACED`).

The **Unfreeze** button forces a clean re-bind (dropping any stale connection
first) and re-requests a SysUI refresh — but you normally never need it:
refreshes also happen automatically on every connect and screen wake.

## Supported Xiaomi Complications (35 Data Sources)

| Category | Complication Data Sources |
|---|---|
| **Activity & Vigor** | `StepComplicationDataSource`, `LatestStepsComplicationDataSource`, `StepsDistanceComplicationDataSource`, `TargetNumberStepsComplicationDataSource`, `CalorieComplicationDataSource`, `CalorieTargetComplicationDataSource`, `LatestCalorieComplicationDataSource`, `StandingComplicationDataSource`, `StandingLatestComplicationDataSource`, `StandingTargetComplicationDataSource`, `ModerateToHighIntensityComplicationDataSource`, `TargetModerateHighIntensityComplicationDataSource`, `LatestIntensityTimeComplicationDataSource` |
| **Heart Rate & SpO2** | `HeartRateComplicationDataSource`, `HeartRateRecentComplicationDataSource`, `BloodOxygenComplicationDataSource`, `RecentBloodOxygenComplicationDataSource` |
| **Sleep & Stress** | `SleepComplicationDataSource`, `SleepGoalsComplicationDataSource`, `LatestSleepComplicationDataSource`, `StressComplicationDataSource`, `StressRangeComplicationDataSource` |
| **Weather** | `WeatherComplicationService`, `WeatherConditionsComplicationService`, `AirComplicationDataSource`, `LatestAirComplicationDataSource`, `HumidityComplicationDataSource`, `UvIndexComplicationDataSource`, `WindComplicationDataSource`, `WindScaleComplicationDataSource`, `SunriseComplicationService`, `SunsetComplicationService` |
| **System & Sensors** | `BatteryComplicationDataSource`, `ElevationComplicationDataSource`, `AirPressureComplicationDataSource` |

## One-time setup (the only manual step)

1. Open the app once on the watch (after install or update).
2. That's it. The service starts, binds, and survives reboots on its own.

If the watch was already in the broken state (frozen subscription), tap
**Unfreeze** once — and if values still don't move, re-select your watch face
one last time. From then on the binding prevents the state from recurring.

## Installation (End Users)

Download the latest `.apk` from the [**Releases**](../../releases) page and sideload it onto your watch using either an Android phone or a PC.

### Step 1: Enable Wireless Debugging on the Watch
1. On your watch, open **Settings → System → About → Versions**.
2. Tap **Build number** 7 times until *You are now a developer* appears.
3. Go back to **Settings → Developer options**.
4. Enable **ADB debugging** and **Wireless debugging** (while connected to Wi-Fi).

### Step 2: Sideload the APK

**Option A: From an Android Phone (No PC required)**
1. Install **Wear Installer 2** or **Bugjaeger** from the Play Store on your phone.
2. Open **Wireless debugging** on the watch, tap **Pair new device**, and enter the IP, port, and 6-digit pairing code in the phone app.
3. Select the downloaded `MiComplicationBridge.apk` and install it to the watch.

**Option B: From a PC (GUI or ADB)**
1. Use a desktop tool like **ADB AppControl** or **Wear OS ToolBox**, or connect via terminal:

        adb pair <watch-ip>:<pairing-port> <6-digit-code>
        adb connect <watch-ip>:<connection-port>
        adb install -r MiComplicationBridge.apk

2. **Important:** Turn **Wireless debugging** back **OFF** on your watch after installing so it does not drain battery.

## Building from Source (Developers)

1. Clone the repository and open the folder in **Android Studio**.
2. Build the debug or release APK:

    ./gradlew :app:assembleDebug

## Verifying it works (Optional)

Run these from a PC via ADB to verify the background binding:

| # | Command | Expected |
|---|---------|----------|
| 1 | `adb shell dumpsys activity services com.lindepuu.mibridge` | KeepAliveService listed, `isForeground=true` |
| 2 | `adb shell dumpsys activity services com.xiaomi.miwear.watchface` | TransmitRXService with a ConnectionRecord `c:com.lindepuu.mibridge` (a **binding**, not a start) |
| 3 | Wait 10+ minutes with the WFF face active | Steps/calories values advance on the face |
| 4 | `adb shell logcat -d \| grep -E "am_freeze"` | No `am_freeze` line for `com.xiaomi.miwear.watchface` while the app is active |
| 5 | Reboot the watch (leave it locked), do nothing | Complications live again ~1–2 min after boot — proves the direct-boot path works |
| 6 | `adb shell am force-stop com.xiaomi.miwear.watchface` | `Binding died...` then `bindService accepted` in logcat within seconds; complications resume |

## Project Structure

    app/src/main/java/com/lindepuu/mibridge/
    ├── service/KeepAliveService.kt     ← Foreground service holding the TransmitRXService binding
    ├── manager/BridgeManager.kt        ← Toggle, state snapshot, and SysUI refresh broadcaster
    ├── receiver/BootReceiver.kt        ← Boot and package-update receiver
    └── presentation/                   ← Wear Compose Material 3 status UI

## Troubleshooting

- **"bindService returned false"** — the Xiaomi package is not visible to the
  app. Check that the `<queries>` block in `AndroidManifest.xml` is intact.
- **Values still frozen right after install** — the subscription was already
  lost before the app was installed. Tap **Unfreeze**; if needed, re-select the
  watch face once.
- **Nothing after reboot** — check `adb shell logcat` for the boot broadcast;
  some OEM battery managers delay boot receivers by a minute.