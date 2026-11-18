package com.lindepuu.mibridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.lindepuu.mibridge.R
import com.lindepuu.mibridge.manager.BridgeManager
import com.lindepuu.mibridge.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service whose only job is to HOLD a binder connection to Xiaomi's
 * TransmitRXService (com.xiaomi.miwear.watchface).
 *
 * Why this works when everything else failed:
 *  - Our process is foreground (has a notification), so WE are never frozen.
 *  - Android's cached-app freezer will not freeze a process that another,
 *    non-frozen process holds a binding to -> the Xiaomi watch-face process
 *    stays runnable, keeps its MiHealthService subscription, and the ~5s topic
 *    pushes keep refreshing the complications.
 *  - onBindingDied / onServiceDisconnected trigger a re-bind with backoff,
 *    which also re-creates the provider process if it was killed.
 *  - onNullBinding is treated as a HELD connection: TransmitRXService's
 *    onBind() returns null for actions it does not recognize, but the
 *    ServiceConnection stays registered in ActivityManager until we unbind -
 *    and a registered connection keeps the provider out of the freezer. So we
 *    do NOT unbind there; we mark connected and let the watchdog handle a
 *    real death.
 *
 * There is deliberately no timer, no polling, no periodic "kick". The binding
 * IS the keep-alive.
 */
class KeepAliveService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True between bindService() success and onServiceConnected(). */
    @Volatile
    private var bindingInFlight = false

    @Volatile
    private var connected = false

    @Volatile
    private var retryCount = 0

    @Volatile
    private var destroyed = false

    @Volatile
    private var lastScreenRefreshElapsedMs = 0L

    private var connection: ServiceConnection? = null
    private var screenReceiverRegistered = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Xiaomi TransmitRXService connected: $name")
            connected = true
            bindingInFlight = false
            retryCount = 0
            recordState(connected = true, binding = false, error = null)
            // Automatically nudge SysUI to refresh complications whenever we
            // (re)connect - recovers a subscription that died while we were
            // not holding the binding.
            serviceScope.launch {
                BridgeManager.requestComplicationRefresh(applicationContext)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The provider process died or was restarted. The system keeps the
            // connection registered and will deliver onServiceConnected again
            // when it comes back, but re-binding proactively with
            // BIND_AUTO_CREATE resurrects the process immediately.
            Log.w(TAG, "Xiaomi TransmitRXService disconnected: $name")
            connected = false
            recordState(connected = false, binding = false, error = "Disconnected; rebinding")
            scheduleRebind(0)
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.w(TAG, "Binding died (provider process killed): $name")
            dropConnection()
            recordState(connected = false, binding = false, error = "Binding died; rebinding")
            scheduleRebind(0)
        }

        override fun onNullBinding(name: ComponentName?) {
            // TransmitRXService returned null from onBind(). The connection is
            // still registered in ActivityManager (and still pins the provider
            // out of the freezer) as long as we do NOT unbind - so hold it.
            Log.i(TAG, "Null binding from $name (connection held in ActivityManager)")
            connected = true
            bindingInFlight = false
            retryCount = 0
            recordState(connected = true, binding = false, error = null)
            serviceScope.launch {
                BridgeManager.requestComplicationRefresh(applicationContext)
            }
        }
    }

    /**
     * Screen-wake nudge: if the connection dropped while the screen was off,
     * rebind immediately; otherwise throttle a SysUI refresh to once a minute
     * so a freshly-woken watch shows current values without hammering SysUI.
     */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_ON) return
            if (destroyed) return
            if (!connected && !bindingInFlight) {
                scheduleRebind(0)
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastScreenRefreshElapsedMs >= 60_000L) {
                lastScreenRefreshElapsedMs = now
                serviceScope.launch {
                    BridgeManager.requestComplicationRefresh(applicationContext)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KeepAliveService created")
        BridgeManager.KeepAliveServiceRunningHolder.running = true
        startAsForeground()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        BridgeManager.KeepAliveServiceRunningHolder.running = true
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "unknown"
        Log.i(TAG, "onStartCommand (source=$source)")

        if (BridgeManager.isBridgeEnabled(this)) {
            // A manual tap forces a clean re-bind even if we think we are
            // already connected (stale state recovery).
            if (source == "manual") {
                dropConnection()
            }
            scheduleRebind(0)
        } else {
            Log.i(TAG, "Bridge disabled by user; stopping self")
            stopSelf()
        }
        // If the system kills us, we want to be restarted - the binding dies
        // with us, so this service must come back.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.w(TAG, "KeepAliveService destroyed - dropping Xiaomi binding")
        destroyed = true
        unregisterScreenReceiver()
        BridgeManager.KeepAliveServiceRunningHolder.running = false
        dropConnection()
        recordState(connected = false, binding = false, error = null)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- foreground

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_MIN   // not shown on the watch face
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(getString(R.string.notif_running_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // e.g. FGS start restrictions in weird states - log and let the
            // START_STICKY + BootReceiver retry ladder handle it.
            Log.e(TAG, "startForeground failed", t)
        }
    }

    // ---------------------------------------------------------------- binding

    private fun scheduleRebind(delayMs: Long) {
        if (destroyed) return
        serviceScope.launch {
            if (delayMs > 0) delay(delayMs)
            if (destroyed) return@launch
            bindXiaomi()
        }
    }

    private fun bindXiaomi() {
        if (!BridgeManager.isBridgeEnabled(this)) return

        if (!isXiaomiWatchfaceInstalled()) {
            Log.w(TAG, "Xiaomi watchface package not installed; will retry later")
            recordState(connected = false, binding = false, error = "Xiaomi watchface not installed")
            scheduleRebind(RETRY_WHEN_MISSING_MS)
            return
        }

        if (bindingInFlight || connected) return

        val intent = Intent(BridgeManager.TRANSMIT_ACTION).apply {
            data = Uri.parse(BridgeManager.TRANSMIT_URI)
            component = ComponentName(
                BridgeManager.WATCHFACE_PACKAGE,
                BridgeManager.TRANSMIT_SERVICE_CLASS
            )
        }

        try {
            dropConnection()   // clear any stale connection object first
            bindingInFlight = true
            connection = conn
            // BIND_IMPORTANT: treat the provider like a foreground-level
            // dependency so the system is even less inclined to freeze it.
            val ok = bindService(
                intent,
                conn,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
            if (!ok) {
                Log.e(TAG, "bindService returned false (provider missing or not visible)")
                bindingInFlight = false
                connection = null
                retryCount++
                recordState(connected = false, binding = false, error = "bindService returned false")
                scheduleRebind(backoffMs())
            } else {
                Log.i(TAG, "bindService accepted; waiting for callback")
                recordState(connected = false, binding = true, error = null)
                // Safety net: if neither onServiceConnected nor onNullBinding
                // arrives (system dropped the request), retry instead of
                // waiting forever.
                scheduleConnectionTimeout()
            }
        } catch (t: Throwable) {
            bindingInFlight = false
            connection = null
            Log.e(TAG, "bindService threw", t)
            retryCount++
            recordState(connected = false, binding = false, error = "${t.javaClass.simpleName}: ${t.message}")
            scheduleRebind(backoffMs())
        }
    }

    private fun backoffMs(): Long = when {
        retryCount <= 1 -> 5_000L
        retryCount <= 3 -> 30_000L
        else -> 5 * 60_000L
    }

    /** If neither onServiceConnected nor onNullBinding has arrived ~30s after
     *  a successful bindService(), assume the connection was silently dropped
     *  and retry. */
    private fun scheduleConnectionTimeout() {
        serviceScope.launch {
            delay(30_000)
            if (destroyed) return@launch
            if (bindingInFlight && !connected) {
                Log.w(TAG, "Connection timeout; rebinding")
                dropConnection()
                retryCount++
                recordState(connected = false, binding = false, error = "connection timeout")
                scheduleRebind(0)
            }
        }
    }

    private fun dropConnection() {
        connection?.let {
            try {
                unbindService(it)
            } catch (_: IllegalArgumentException) {
                // was not bound - fine
            }
        }
        connection = null
        connected = false
        bindingInFlight = false
    }

    // ---------------------------------------------------------------- screen receiver

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        try {
            registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            screenReceiverRegistered = true
        } catch (_: Throwable) {
            // registered on a destroyed service - ignore
        }
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        try {
            unregisterReceiver(screenOnReceiver)
        } catch (_: Throwable) {
        }
        screenReceiverRegistered = false
    }

    // ---------------------------------------------------------------- helpers

    private fun isXiaomiWatchfaceInstalled(): Boolean =
        BridgeManager.isPackageInstalled(this, BridgeManager.WATCHFACE_PACKAGE)

    /**
     * Persist connection state for the UI. MUST go through device-protected
     * storage: this service is directBootAware and can run before the user
     * unlocks the watch (LOCKED_BOOT_COMPLETED), where credential-encrypted
     * SharedPreferences throw IllegalStateException.
     */
    private fun recordState(connected: Boolean, binding: Boolean, error: String?) {
        BridgeManager.getDevicePrefs(this).edit {
            putBoolean(KEY_BOUND, connected)
            putBoolean(KEY_BINDING, binding)
            putLong(KEY_LAST_EVENT_MS, System.currentTimeMillis())
            error?.let { putString(KEY_LAST_ERROR, it) } ?: remove(KEY_LAST_ERROR)
        }
        BridgeManager.notifyStatusChanged()
    }

    companion object {
        private const val TAG = "MiBridge"
        const val EXTRA_SOURCE = "extra_source"
        const val CHANNEL_ID = "mi_bridge_keepalive"
        const val NOTIFICATION_ID = 1001
        const val RETRY_WHEN_MISSING_MS = 15 * 60_000L
        const val PREFS_NAME = "mi_bridge_prefs"
        const val KEY_BOUND = "bound"
        const val KEY_BINDING = "binding_in_flight"
        const val KEY_LAST_EVENT_MS = "last_event_ms"
        const val KEY_LAST_ERROR = "last_error"

        fun start(context: Context, source: String) {
            val intent = Intent(context, KeepAliveService::class.java)
                .putExtra(EXTRA_SOURCE, source)
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "startForegroundService failed (source=$source): ${t.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
