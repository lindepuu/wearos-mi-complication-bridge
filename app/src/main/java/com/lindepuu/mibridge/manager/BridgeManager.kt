package com.lindepuu.mibridge.manager

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.lindepuu.mibridge.service.KeepAliveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Small coordinator: user toggle, status snapshot, and the platform-side
 * recovery nudge. All the hard work lives in [KeepAliveService].
 */
object BridgeManager {

    private const val TAG = "MiBridge"

    const val WATCHFACE_PACKAGE = "com.xiaomi.miwear.watchface"
    const val TRANSMIT_SERVICE_CLASS = "com.xiaomi.miwear.transmit.TransmitRXService"
    const val TRANSMIT_ACTION = "com.xiaomi.miwear.transmit.receive.service"
    const val TRANSMIT_URI = "miwear://transmit/miwear/watchface/cpc"

    const val PREFS_NAME = KeepAliveService.PREFS_NAME
    private const val KEY_ENABLED = "bridge_enabled"

    private const val ACTION_REQUEST_UPDATE_ALL =
        "android.support.wearable.complications.ACTION_REQUEST_UPDATE_ALL"
    private const val EXTRA_PACKAGE_NAME =
        "android.support.wearable.complications.EXTRA_PACKAGE_NAME"
    private const val EXTRA_PROVIDER_COMPONENT =
        "android.support.wearable.complications.EXTRA_PROVIDER_COMPONENT"
    private const val EXTRA_PENDING_INTENT =
        "android.support.wearable.complications.EXTRA_PENDING_INTENT"

    private val _statusVersion = MutableStateFlow(0L)
    val statusVersion: StateFlow<Long> = _statusVersion.asStateFlow()

    fun getDevicePrefs(context: Context): SharedPreferences {
        val storageContext = context.applicationContext.createDeviceProtectedStorageContext()
        return storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun notifyStatusChanged() {
        _statusVersion.value += 1L
    }

    // ---------------------------------------------------------------- toggle

    fun isBridgeEnabled(context: Context): Boolean =
        getDevicePrefs(context).getBoolean(KEY_ENABLED, true)

    fun setBridgeEnabled(context: Context, enabled: Boolean) {
        getDevicePrefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        if (enabled) {
            KeepAliveService.start(context, "toggle_on")
        } else {
            KeepAliveService.stop(context)
        }
        notifyStatusChanged()
    }

    // ---------------------------------------------------------------- status

    fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Throwable) {
        false
    }

    data class BridgeSnapshot(
        val watchfaceInstalled: Boolean,
        val serviceRunning: Boolean,
        val bound: Boolean,
        val bindingInFlight: Boolean,
        val enabled: Boolean,
        val lastEventAtMs: Long,
        val lastError: String?
    )

    fun getSnapshot(context: Context): BridgeSnapshot {
        val prefs = getDevicePrefs(context)
        return BridgeSnapshot(
            watchfaceInstalled = isPackageInstalled(context, WATCHFACE_PACKAGE),
            serviceRunning = KeepAliveServiceRunningHolder.running,
            bound = prefs.getBoolean(KeepAliveService.KEY_BOUND, false),
            bindingInFlight = prefs.getBoolean(KeepAliveService.KEY_BINDING, false),
            enabled = isBridgeEnabled(context),
            lastEventAtMs = prefs.getLong(KeepAliveService.KEY_LAST_EVENT_MS, 0L),
            lastError = prefs.getString(KeepAliveService.KEY_LAST_ERROR, null)
        )
    }

    object KeepAliveServiceRunningHolder {
        @Volatile
        var running: Boolean = false
    }

    // ---------------------------------------------------------------- recovery nudge

    /**
     * Sends the complication update broadcast directly to SysUI (com.google.wear.services)
     * instead of calling ComplicationDataSourceUpdateRequester.requestUpdateAll(), because
     * watchface-complications-data-source 1.3.0 spawns an internal coroutine that calls
     * bindService(ACTION_WEAR_SDK_COMPLICATION_UPDATE_REQUEST) on the target component,
     * which throws an uncaught SecurityException across packages.
     */
    suspend fun requestComplicationRefresh(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val dummyPendingIntent = PendingIntent.getActivity(
                appContext,
                0,
                Intent(""),
                PendingIntent.FLAG_IMMUTABLE
            )
            var anyOk = false
            for (source in XIAOMI_COMPLICATION_DATA_SOURCES) {
                try {
                    val component = ComponentName(WATCHFACE_PACKAGE, source)
                    for (targetPkg in listOf("com.google.wear.services", "com.google.android.wearable.app")) {
                        val intent = Intent(ACTION_REQUEST_UPDATE_ALL).apply {
                            setPackage(targetPkg)
                            putExtra(EXTRA_PACKAGE_NAME, WATCHFACE_PACKAGE)
                            putExtra(EXTRA_PROVIDER_COMPONENT, component)
                            putExtra(EXTRA_PENDING_INTENT, dummyPendingIntent)
                        }
                        appContext.sendBroadcast(intent)
                    }
                    anyOk = true
                } catch (t: Throwable) {
                    Log.d(TAG, "Update broadcast skipped for $source: ${t.message}")
                }
            }
            Log.i(TAG, "Requested complication refresh across Xiaomi sources (anyOk=$anyOk)")
            anyOk
        }

    private val XIAOMI_COMPLICATION_DATA_SOURCES = listOf(
        "com.xiaomi.miwear.watchface.complication.health.vigor.LatestStepsComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.vigor.StepComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.vigor.StepsDistanceComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.vigor.TargetNumberStepsComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.vigor.CalorieComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.vigor.CalorieTargetComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.vigor.LatestCalorieComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.vigor.StandingComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.vigor.StandingLatestComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.vigor.StandingTargetComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.vigor.ModerateToHighIntensityComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.vigor.TargetModerateHighIntensityComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.vigor.LatestIntensityTimeComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.hr.HeartRateComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.hr.HeartRateRecentComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.spo2.BloodOxygenComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.spo2.RecentBloodOxygenComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.stress.StressComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.stress.StressRangeComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.sleep.SleepComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.sleep.SleepGoalsComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.health.sleep.LatestSleepComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.weather.WeatherComplicationService",
        "com.xiaomi.miwear.watchface.complication.weather.WeatherConditionsComplicationService",
        "com.xiaomi.miwear.watchface.complication.weather.AirComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.weather.LatestAirComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.weather.HumidityComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.weather.UvIndexComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.weather.WindComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.weather.WindScaleComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.weather.SunriseComplicationService",
        "com.xiaomi.miwear.watchface.complication.weather.SunsetComplicationService",
        "com.xiaomi.miwear.watchface.complication.other.BatteryComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.altitude.ElevationComplicationDataSource",
        "com.xiaomi.miwear.watchface.complication.altitude.AirPressureComplicationDataSource"
    )
}
