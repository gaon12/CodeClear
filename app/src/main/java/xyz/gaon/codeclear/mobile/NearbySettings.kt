package xyz.gaon.codeclear.mobile

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.nearby.NearbyStatus

@Composable
internal fun NearbySettings(state: MobileState, actions: MobileActions) {
    val options = state.options
    var disclosure by remember { mutableStateOf(false) }
    SettingsGroup {
        SettingSwitchRow(
            stringResource(R.string.mobile_nearby_allow),
            stringResource(nearbyStatusLabel(actions.nearby.status)),
            options.nearbyEnabled,
            enabled = state.loaded && !state.storageError
        ) {
            if (it) disclosure = true else actions.configure(options.copy(nearbyEnabled = false))
        }
        SettingDivider()
        SettingNote(stringResource(R.string.mobile_nearby_foreground))
        if (android.os.Build.VERSION.SDK_INT == 30) {
            SettingNote(stringResource(R.string.mobile_nearby_legacy_location))
        }
        SettingNote(stringResource(R.string.mobile_nearby_disclosure))
        if (actions.nearby.retentionProblem) {
            SettingNote(stringResource(R.string.mobile_nearby_retention_problem))
        }
        when (actions.nearby.status) {
            NearbyStatus.PERMISSION_REQUIRED -> {
                SettingRow(
                    stringResource(R.string.mobile_allow),
                    onClick = actions.nearbyPermission
                )
                SettingRow(stringResource(R.string.mobile_open_settings), onClick = {
                    actions.settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                })
            }

            NearbyStatus.BLUETOOTH_OFF -> SettingRow(
                stringResource(
                    R.string.mobile_open_settings
                ),
                onClick = {
                    actions.settings(Settings.ACTION_BLUETOOTH_SETTINGS)
                }
            )

            NearbyStatus.LOCATION_DISABLED -> SettingRow(
                stringResource(
                    R.string.mobile_open_settings
                ),
                onClick = {
                    actions.settings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                }
            )

            NearbyStatus.ERROR -> SettingRow(
                stringResource(R.string.mobile_retry),
                onClick = actions.refreshNearby
            )

            else -> Unit
        }
    }
    if (disclosure) {
        MobileConfirmationDialog(
            onDismissRequest = { disclosure = false },
            title = { Text(stringResource(R.string.mobile_nearby)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.mobile_nearby_disclosure))
                    Text(stringResource(R.string.mobile_nearby_foreground))
                    if (android.os.Build.VERSION.SDK_INT == 30) {
                        Text(stringResource(R.string.mobile_nearby_legacy_location))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    disclosure = false
                    actions.nearbyPermission()
                }) { Text(stringResource(R.string.mobile_allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    disclosure = false
                }) { Text(stringResource(R.string.mobile_cancel)) }
            }
        )
    }
}

internal fun nearbyStatusLabel(status: NearbyStatus): Int = when (status) {
    NearbyStatus.OFF -> R.string.mobile_off
    NearbyStatus.PAUSED -> R.string.mobile_nearby_paused
    NearbyStatus.PERMISSION_REQUIRED -> R.string.mobile_nearby_permission
    NearbyStatus.BLUETOOTH_OFF -> R.string.mobile_nearby_bluetooth
    NearbyStatus.LOCATION_DISABLED -> R.string.mobile_nearby_location
    NearbyStatus.UNAVAILABLE -> R.string.mobile_nearby_unavailable
    NearbyStatus.LISTENING -> R.string.mobile_nearby_listening
    NearbyStatus.RECEIVE_ONLY -> R.string.mobile_nearby_receive_only
    NearbyStatus.TRANSFERRING -> R.string.mobile_nearby_transferring
    NearbyStatus.COOLDOWN -> R.string.mobile_nearby_cooldown
    NearbyStatus.ERROR -> R.string.mobile_nearby_error
}
