package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.probe.ProbeEnvironment
import xyz.gaon.codeclear.probe.ProbeRadio
import xyz.gaon.codeclear.probe.ProbeSessionState

internal fun requiredAccessMissing(
    environment: ProbeEnvironment,
    notificationsAllowed: Boolean
): Boolean = !notificationsAllowed || (
    environment.radio == ProbeRadio.PRESENT &&
        (!environment.notificationGranted || !environment.accessibilityGranted)
    )

@Composable
internal fun RequiredAccessPage(
    state: MobileState,
    environment: ProbeEnvironment,
    session: ProbeSessionState,
    actions: MobileActions,
    tutorial: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().testTag("required-access"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MobilePageHeader(stringResource(R.string.mobile_required_access))
        Column(
            Modifier.weight(
                1f
            ).widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsGroup { SettingNote(stringResource(R.string.mobile_required_access_notice)) }
            if (!actions.notificationsAllowed) NotificationPermissionSetup(actions)
            if (environment.radio == ProbeRadio.PRESENT) {
                MobileSettings(SettingsPage.CAPTURE, state, environment, session, actions, tutorial)
            } else {
                SettingsGroup {
                    SettingNote(
                        stringResource(
                            if (environment.radio ==
                                ProbeRadio.ABSENT
                            ) {
                                R.string.mobile_wifi_only
                            } else {
                                R.string.mobile_radio_unknown
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
internal fun NotificationPermissionSetup(actions: MobileActions) {
    SettingsGroup {
        SettingRow(
            stringResource(R.string.mobile_result_notifications),
            stringResource(
                if (actions.notificationsAllowed) {
                    R.string.mobile_on
                } else {
                    R.string.mobile_notification_permission_reason
                }
            ),
            onClick = actions.notificationPermission
        )
    }
}
