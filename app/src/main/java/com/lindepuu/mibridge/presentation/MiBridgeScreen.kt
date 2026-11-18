package com.lindepuu.mibridge.presentation

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.lindepuu.mibridge.R
import com.lindepuu.mibridge.manager.BridgeManager
import com.lindepuu.mibridge.presentation.theme.MiBridgeTheme
import com.lindepuu.mibridge.service.KeepAliveService
import kotlinx.coroutines.launch

@Composable
fun MiBridgeScreen(statusVersion: Long) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf(BridgeManager.getSnapshot(context)) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(statusVersion) {
        snapshot = BridgeManager.getSnapshot(context)
    }

    MiBridgeTheme {
        val scrollState = rememberScrollState()
        ScreenScaffold(scrollState = scrollState) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 14.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.header_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )

                StatusLine(snapshot = snapshot)

                StatusCard(
                    subtitle = buildSubtitle(snapshot),
                    note = note ?: snapshot.lastError,
                    isOk = snapshot.enabled && snapshot.bound,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (!busy) {
                            coroutineScope.launch {
                                busy = true
                                note = "Rebinding + requesting refresh..."
                                try {
                                    KeepAliveService.start(context, "manual")
                                    val refreshed = BridgeManager.requestComplicationRefresh(context)
                                    note = if (refreshed) {
                                        "Refresh requested from SysUI"
                                    } else {
                                        "Rebind sent (refresh not taken)"
                                    }
                                } catch (t: Throwable) {
                                    note = "Failed: ${t.message}"
                                } finally {
                                    snapshot = BridgeManager.getSnapshot(context)
                                    busy = false
                                }
                            }
                        }
                    },
                    enabled = !busy && snapshot.watchfaceInstalled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_refresh),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.btn_unfreeze_now),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                SwitchButton(
                    checked = snapshot.enabled,
                    onCheckedChange = { enabled ->
                        BridgeManager.setBridgeEnabled(context, enabled)
                        snapshot = BridgeManager.getSnapshot(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.toggle_bridge_title)) },
                    secondaryLabel = {
                        Text(
                            text = stringResource(R.string.toggle_bridge_desc),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun StatusLine(snapshot: BridgeManager.BridgeSnapshot) {
    val active = snapshot.enabled && snapshot.bound
    Text(
        text = when {
            snapshot.bindingInFlight -> "Connecting..."
            active -> "Active"
            snapshot.enabled -> "Retrying..."
            else -> "Paused"
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.tertiary,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun StatusCard(
    subtitle: String,
    note: String?,
    isOk: Boolean,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isOk) MaterialTheme.colorScheme.surfaceContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val accentColor = if (isOk) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.error

    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(containerColor)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(
                    id = if (isOk) R.drawable.ic_check_circle else R.drawable.ic_warning
                ),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isOk) stringResource(R.string.status_active)
                else stringResource(R.string.status_inactive),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isOk) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!note.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

private fun buildSubtitle(snapshot: BridgeManager.BridgeSnapshot): String {
    if (!snapshot.watchfaceInstalled) {
        return "Xiaomi watch-face package not found."
    }
    val since = if (snapshot.lastEventAtMs > 0L) {
        DateUtils.getRelativeTimeSpanString(
            snapshot.lastEventAtMs,
            System.currentTimeMillis(),
            DateUtils.SECOND_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    } else {
        "never"
    }
    return "Service: ${if (snapshot.serviceRunning) "running" else "stopped"}" +
        " • Binding: ${if (snapshot.bound) "held" else if (snapshot.bindingInFlight) "connecting" else "down"}" +
        " • Last event: $since"
}
