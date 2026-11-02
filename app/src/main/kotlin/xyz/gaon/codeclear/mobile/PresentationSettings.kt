package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import xyz.gaon.codeclear.R

@Composable
internal fun PresentationSettings(options: MobileOptions, actions: MobileActions) {
    var disclosure by remember { mutableStateOf(false) }
    val access = actions.presentationAccess
    SettingsGroup {
        SettingSwitchRow(
            stringResource(R.string.mobile_deferred_popup),
            stringResource(
                if (options.deferredOverlayEnabled) {
                    R.string.mobile_on
                } else {
                    R.string.mobile_off
                }
            ),
            checked = options.deferredOverlayEnabled,
            onCheckedChange = {
                if (it) {
                    disclosure = true
                } else {
                    actions.configure(options.copy(deferredOverlayEnabled = false))
                }
            }
        )
        SettingNote(stringResource(R.string.mobile_deferred_popup_summary))
        if (options.deferredOverlayEnabled) {
            SettingDivider()
            SettingRow(
                stringResource(R.string.mobile_overlay_access),
                stringResource(
                    if (access.overlayAllowed) R.string.mobile_on else R.string.mobile_off
                ),
                onClick = actions.overlaySettings
            )
        }
    }
    SettingsGroup {
        SettingRow(
            stringResource(R.string.mobile_battery_settings),
            stringResource(
                if (access.batteryExempt) {
                    R.string.mobile_battery_exempt
                } else {
                    R.string.mobile_battery_optimized
                }
            ),
            onClick = actions.batterySettings
        )
        SettingNote(stringResource(R.string.mobile_battery_guidance))
        if (access.backgroundRestricted) {
            SettingNote(stringResource(R.string.mobile_background_restricted))
        }
    }
    if (disclosure) {
        MobileConfirmationDialog(
            onDismissRequest = { disclosure = false },
            title = { Text(stringResource(R.string.mobile_deferred_popup)) },
            text = {
                Text(
                    stringResource(R.string.mobile_overlay_disclosure),
                    Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    disclosure = false
                    actions.configure(options.copy(deferredOverlayEnabled = true))
                }) { Text(stringResource(R.string.mobile_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { disclosure = false }) {
                    Text(stringResource(R.string.mobile_cancel))
                }
            }
        )
    }
}
