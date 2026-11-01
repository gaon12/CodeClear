package xyz.gaon.codeclear.mobile

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeEnvironment
import xyz.gaon.codeclear.capture.ProbeRadio
import xyz.gaon.codeclear.capture.ProbeSessionState

@Composable
internal fun CompatibilityChecklist(
    state: MobileState,
    environment: ProbeEnvironment,
    session: ProbeSessionState,
    actions: MobileActions
) {
    val context = LocalContext.current
    val supportedForm = remember(context) {
        val manager = context.packageManager
        listOf(
            PackageManager.FEATURE_WATCH,
            PackageManager.FEATURE_LEANBACK,
            PackageManager.FEATURE_LEANBACK_ONLY,
            PackageManager.FEATURE_AUTOMOTIVE
        ).none(manager::hasSystemFeature)
    }
    val options = state.options
    Column(
        Modifier.fillMaxWidth().testTag("compatibility-page"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingSection(stringResource(R.string.mobile_compat_hardware)) {
            CompatibilityCheck(
                "android",
                stringResource(
                    R.string.mobile_compat_android,
                    Build.VERSION.RELEASE,
                    Build.VERSION.SDK_INT
                ),
                Build.VERSION.SDK_INT >= 30
            )
            CompatibilityCheck("form", stringResource(R.string.mobile_compat_form), supportedForm)
            CompatibilityCheck(
                "radio",
                stringResource(R.string.mobile_compat_radio),
                when (environment.radio) {
                    ProbeRadio.PRESENT -> true
                    ProbeRadio.ABSENT -> false
                    ProbeRadio.UNKNOWN -> null
                }
            )
            if (environment.radio == ProbeRadio.ABSENT) {
                Text(stringResource(R.string.mobile_wifi_only))
            }
            CompatibilityCheck(
                "source",
                stringResource(R.string.mobile_compat_source),
                environment.systemPackages.isNotEmpty()
            )
        }
        SettingSection(stringResource(R.string.mobile_compat_os_grants)) {
            CompatibilityCheck(
                "notification-grant",
                stringResource(R.string.mobile_notification_access),
                environment.notificationGranted
            )
            CompatibilityCheck(
                "accessibility-grant",
                stringResource(R.string.mobile_accessibility_access),
                environment.accessibilityGranted
            )
        }
        SettingSection(stringResource(R.string.mobile_compat_consent)) {
            CompatibilityCheck(
                "notification-consent",
                stringResource(R.string.mobile_notification_access),
                options.notificationConsent
            )
            CompatibilityCheck(
                "accessibility-consent",
                stringResource(R.string.mobile_accessibility_access),
                options.accessibilityConsent
            )
        }
        SettingSection(stringResource(R.string.mobile_compat_connections)) {
            CompatibilityCheck(
                "notification-connected",
                stringResource(R.string.mobile_notification_access),
                session.notificationConnected
            )
            CompatibilityCheck(
                "accessibility-connected",
                stringResource(R.string.mobile_accessibility_access),
                session.accessibilityConnected
            )
        }
        SettingSection(stringResource(R.string.mobile_notifications)) {
            CompatibilityCheck(
                "results",
                stringResource(R.string.mobile_result_notifications),
                actions.notificationsAllowed
            )
        }
        Text(
            stringResource(R.string.mobile_compatibility_description),
            Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun CompatibilityCheck(id: String, label: String, available: Boolean?) {
    Row(
        Modifier.fillMaxWidth().testTag("compatibility-$id"),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (available == true) {
                "✓"
            } else if (available == false) {
                "✕"
            } else {
                "?"
            }
        )
        Text(label, Modifier.weight(1f))
        Text(
            stringResource(
                when (available) {
                    true -> R.string.mobile_compat_yes
                    false -> R.string.mobile_compat_no
                    null -> R.string.mobile_compat_unknown
                }
            ),
            Modifier.testTag("compatibility-$id-status")
        )
    }
}
