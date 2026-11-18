package com.lindepuu.mibridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lindepuu.mibridge.manager.BridgeManager
import com.lindepuu.mibridge.service.KeepAliveService

/**
 * Restarts the keep-alive foreground service after boot, after the app is
 * updated, and (via LOCKED_BOOT_COMPLETED) as early as possible on a locked
 * watch. The service does the binding itself; this receiver only starts it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        Log.i(TAG, "Received $action")

        // On LOCKED_BOOT_COMPLETED the device is still locked: only
        // device-protected storage is available (BridgeManager reads its
        // toggle from deviceProtectedStorage, so this is safe). Starting a
        // foreground service from a boot receiver is one of the allowed cases.
        if (!BridgeManager.isBridgeEnabled(context)) {
            Log.i(TAG, "Bridge disabled; not starting service")
            return
        }
        KeepAliveService.start(context, action)
    }

    private companion object {
        const val TAG = "MiBridgeBoot"
    }
}
