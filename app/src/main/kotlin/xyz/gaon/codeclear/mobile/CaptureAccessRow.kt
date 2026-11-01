package xyz.gaon.codeclear.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import xyz.gaon.codeclear.R

@Composable
internal fun AccessRow(
    title: String,
    enabled: Boolean,
    granted: Boolean,
    connected: Boolean,
    captureAvailable: Boolean,
    enable: () -> Unit,
    disable: () -> Unit
) {
    SettingSwitchRow(
        title,
        stringResource(
            when {
                !captureAvailable -> R.string.mobile_capture_unavailable
                !granted -> R.string.mobile_access_needed
                !enabled -> R.string.mobile_capture_paused
                !connected -> R.string.mobile_reconnect_needed
                else -> R.string.mobile_connected
            }
        ),
        checked = captureAvailable && enabled && granted,
        enabled = captureAvailable || enabled,
        onCheckedChange = { if (enabled && (!captureAvailable || granted)) disable() else enable() }
    )
}
