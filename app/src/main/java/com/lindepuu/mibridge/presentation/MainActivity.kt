package com.lindepuu.mibridge.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lindepuu.mibridge.manager.BridgeManager
import com.lindepuu.mibridge.service.KeepAliveService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // One-time setup: opening the app once after install starts the
        // keep-alive. Everything after this is automatic (boot, rebinds).
        if (BridgeManager.isBridgeEnabled(this)) {
            KeepAliveService.start(this, "app_open")
        }

        setContent {
            val statusVersion by BridgeManager.statusVersion.collectAsState()
            MiBridgeScreen(statusVersion = statusVersion)
        }
    }
}
