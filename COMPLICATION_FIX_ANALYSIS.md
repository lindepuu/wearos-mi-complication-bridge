# TECHNICAL ANALYSIS REPORT
**Xiaomi Watch 5 Complication Freeze: Root Cause Analysis and Fix Design**

* **Device:** Xiaomi Watch 5 (Wear OS 5.2, Android 15)
* **Scope:** `com.xiaomi.miwear.watchface` complications on WFF faces
* **Sources:** APK decompilation, 2-session device logs, companion app source
* **Date:** 17 November 2026
* **Project:** Complication Bridge / Starter
* **Analysis Report Version:** 1.0 (Anonymized)

---

## Table of Contents
1. Executive Summary
2. Scope, Sources and Method
3. How the Complication Pipeline Is Designed to Work
4. What the Logs Show
   - 4.1 The working session
   - 4.2 The broken session
   - 4.3 The app crash loop
5. Why the Known Workarounds Behave the Way They Do
   - 5.1 Re-entering the watch-face editor (the "temporary fix")
   - 5.2 The ADB shell start (the "permanent" fix)
   - 5.3 Why the failure mode appeared to change
6. Audit of Initial Companion App v1 - What Works and What Is Dead Weight
7. Recommended Design for Version 2
   - 7.1 The core mechanism - hold the binding, do not start the service
   - 7.2 Trigger points
   - 7.3 Handling the already-frozen state (the "unfreeze" case)
   - 7.4 What to delete, what to keep
   - 7.5 Permissions and manifest changes
   - 7.6 Risks and honest limitations
8. How to Verify the Fix
9. Conclusions

---

## 1. Executive Summary
This report analyzes why built-in Xiaomi complications (steps, calories, heart rate, weather, etc., served by `com.xiaomi.miwear.watchface`) stop updating when a custom Watch Face Format (WFF) face is active on a Xiaomi Watch 5, why existing workarounds (re-entering the watch-face editor or running ADB commands from a PC) only restore them temporarily, why the initial companion app failed to keep them alive, and what a revised app must do instead. 

The analysis is based on three artifacts: a full decompilation of the `MiWatchFace` system app (2,622 smali files), its `AndroidManifest.xml`, roughly 332,000 lines of captured device logs from two sessions, and the complete source code of the initial companion app v1.

The central finding is that there is no single bug, but a chain of three independent conditions:
1. **Push-driven architecture:** Xiaomi complication data sources register with Wear OS with an update period of zero seconds, meaning the platform never refreshes them independently; they rely entirely on external data pushes.
2. **Process caching and freezing:** That push pipeline only functions while the `MiWatchFace` process maintains a live binder subscription to `MiHealthService`. Android's cached-app freezer suspends the process whenever it lacks a foreground component and lacks bindings from a non-frozen process—which is precisely its state when a third-party WFF face is active.
3. **Crash loop in v1:** The companion app's release build suffered from an R8 shrinking bug that stripped a Room constructor required by WorkManager, causing startup crash-loops. Consequently, periodic background syncs and resume hooks never executed in the field.

The recommended fix is to eliminate background worker complexity and instead maintain a single persistent foreground service whose sole responsibility is holding a binder connection to `TransmitRXService`. A binding from a non-frozen process keeps the provider process out of the cached set and prevents freezing. Combined with an immediate watchdog that re-binds on disconnect, this maintains health service topic pushes without requiring ADB, root access, or ongoing manual intervention.

---

## 2. Scope, Sources, and Method
* **Inputs:** 
  1. `MiWatchFace` decompilation (`com.xiaomi.miwear.watchface`, targetSdk 34): `TransmitRXService`, complication data-source hierarchies (`sd0 -> ka -> pa`), provider managers, `MiHealthService` client (`ft0`), and update requesters (`pl`).
  2. Device logs (75 diagnostic steps): `dumpsys activity services` output, process tables, event logs (`am_freeze`, `am_unfreeze`, `am_kill`), and logcat streams.
  3. Companion app source code (manifest, managers, receivers, workers).
* **Method:** Reconstruction of intended data paths from decompiled smali code; comparative analysis of working vs. broken logging sessions; auditing the companion app's architecture against system behavior.

---

## 3. How the Complication Pipeline Is Designed to Work
Every Xiaomi complication is implemented as a standard Wear OS `ComplicationDataSourceService` subclass within `MiWatchFace`. Two core rules govern the system:
* **Fact 1 (Zero-second update period):** Declared with `UPDATE_PERIOD_SECONDS = "0"`. In Wear OS, a period of zero means "active-only / push-driven." System UI binds data sources when active watch faces require them, but never polls them automatically.
* **Fact 2 (Private binder data stream):** Data arrives via private binder subscriptions. When a complication activates, `ka.onComplicationActivated()` registers data keys (e.g., step counts, heart rate) with the provider manager (`ft0`), which binds to `com.xiaomi.miwear.healthservices/.MiHealthService` and receives `topicReportCallback` updates every ~5 seconds. Upon changes, it calls `requestUpdateAll()` so System UI queries fresh values.

When a Xiaomi watch face is active, its wallpaper service runs in the same process, keeping it alive (state `SVC` or better). When a third-party WFF face is active, rendering shifts to the WFF runtime, `MiWatchFace` retains no foreground component, and Android treats it as disposable cache.

---

## 4. What the Logs Show
### 4.1 The Working Session
In a healthy session, `TransmitRXService` was started via ADB (`com.android.shell`), placing the watch face process on a system allow-list with an unlimited duration (`tempAllowListReason duration: 9223372036854775807`). This maintained active connections to `MiHealthService`, ensuring complication values updated reliably.

### 4.2 The Broken Session
Following a standard boot without persistent bindings, the process obtained only a temporary 20-second boot exemption. Standby-bucket dumps placed the process into bucket `5` (`RESTRICTED`), followed quickly by an `am_freeze` event. Client subscriptions dropped to zero, breaking the push pipeline and leaving WFF complications permanently stale.

### 4.3 The App Crash Loop
Log analysis revealed startup fatal exceptions:
```text
FATAL EXCEPTION: Unable to get provider androidx.startup.InitializationProvider:
java.lang.NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init>
```
This is a classic R8 full-mode shrinking issue where the no-argument constructor for Room-generated database classes is stripped. Because the app process died immediately upon launch, all scheduled periodic tasks were dead in the field.

---

## 5. Why Workarounds Behave as They Do
* **Re-entering the Watch-Face Editor:** Forces System UI to tear down and re-create wallpaper instances, triggering fresh `onComplicationActivated()` calls and temporarily bringing the process to the foreground. However, it does nothing to alter long-term process lifecycle management.
* **ADB Shell Start:** Places the target process on an unlimited system allow-list and records the caller as shell. While functional for hours or days, it remains vulnerable to reboots, memory trims, or cache sweeps.

---

## 6. Audit of Companion App v1
* **Root/su whitelist attempts:** Ineffective (devices lack `su` binaries and Companion apps cannot execute `dumpsys`).
* **Doze whitelist checks (`isIgnoringBatteryOptimizations`):** Ineffective (cached-app freezer and standby buckets ignore battery optimization rules).
* **`startService(TransmitRXService)`:** Partly effective (starts the service but only yields a temporary 20-second allow-list when initiated from background states).
* **`bindService(TransmitRXService)` with `BIND_AUTO_CREATE`:** **Effective and crucial.** A binding from a non-frozen process prevents the provider from entering the cached set.
* **WorkManager periodic tasks:** Broken due to R8 code stripping; furthermore, frozen background apps cannot reliably execute jobs anyway.

---

## 7. Recommended Design for Version 2
The design objective is simple: **The `MiWatchFace` process must never enter the cached-and-frozen state while a WFF face is active.**

### 7.1 The Core Mechanism: Persistent Foreground Binding
Replace start-then-bind logic with a persistent foreground service (`KeepAliveService`) in the companion app. Because foreground services are exempt from caching, the companion app remains unfrozen and continuously holds a binder connection to `TransmitRXService`.

```kotlin
class KeepAliveService : Service() {
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, s: IBinder?) { /* held */ }
        override fun onServiceDisconnected(name: ComponentName?) { rebind() }
        override fun onBindingDied(name: ComponentName?) { rebind() }
        override fun onNullBinding(name: ComponentName?) { rebind() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        rebind()
        return START_STICKY
    }

    private fun rebind() {
        val intent = Intent("com.xiaomi.miwear.transmit.receive.service").apply {
            data = Uri.parse("miwear://transmit/miwear/watchface/cpc")
            setComponent(ComponentName("com.xiaomi.miwear.watchface",
                "com.xiaomi.miwear.transmit.TransmitRXService"))
        }
        try { unbindService(conn) } catch (_: IllegalArgumentException) {}
        try { bindService(intent, conn, BIND_AUTO_CREATE) }
        catch (_: Throwable) { /* schedule retry */ }
    }
}
```

### 7.2 Trigger Points
* **First App Launch:** Started via `MainActivity` check.
* **Boot Completed:** Handled via direct-boot-aware `BootReceiver` listening to `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED`.
* **App Updates:** Handled via `MY_PACKAGE_REPLACED`.
* **Disconnection:** Handled instantly via `onBindingDied` / `onServiceDisconnected` callbacks triggering `rebind()`.

### 7.3 Permissions and Manifest Requirements
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<service android:name=".keepalive.KeepAliveService"
         android:exported="false"
         android:foregroundServiceType="specialUse">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
              android:value="Keeps Xiaomi complication provider process bound so complication data keeps updating on WFF watch faces" />
</service>
```
*Note: Use minimal priority for foreground service notifications on Wear OS 5 so they remain unobtrusive.*

---

## 8. Verification Checklist
1. **Launch App:** Verify foreground service initialization and notification presence.
2. **Idle Test:** Select a WFF watch face and leave the watch idle for 30+ minutes; verify complication values continue updating.
3. **ADB Inspection:** Run `adb shell dumpsys activity services com.xiaomi.miwear.watchface` to confirm `TransmitRXService` shows an active connection record tied to the companion package.
4. **Reboot Test:** Reboot the watch and verify automatic recovery within 1–2 minutes without manual intervention.

---

## 9. Conclusions
Complications stop updating because the provider process is treated as disposable cache when not hosting a native Xiaomi watch face. Companion v2 solves this elegantly through a lightweight, self-healing foreground service binding that requires no ADB, no root, and no user action after initial setup.