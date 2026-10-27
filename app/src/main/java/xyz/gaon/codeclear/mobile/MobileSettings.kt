package xyz.gaon.codeclear.mobile

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import xyz.gaon.codeclear.probe.LocalTarget
import xyz.gaon.codeclear.probe.ProbeEnvironment
import xyz.gaon.codeclear.probe.ProbeSessionState

@Composable
fun MobileSettings(
    state: MobileState,
    environment: ProbeEnvironment,
    session: ProbeSessionState,
    actions: MobileActions,
    tutorial: () -> Unit
) {
    val options = state.options
    var disclosure by remember { mutableStateOf<String?>(null) }
    var targetPicker by remember { mutableStateOf(false) }
    var themePicker by remember { mutableStateOf(false) }
    SettingSection(stringResource(R.string.mobile_collection)) {
        AccessRow(
            stringResource(R.string.mobile_notification_access),
            options.notificationConsent,
            environment.notificationGranted,
            session.notificationConnected,
            {
                disclosure = "notification"
            },
            { actions.configure(options.copy(notificationConsent = false)) }
        )
        AccessRow(
            stringResource(R.string.mobile_accessibility_access),
            options.accessibilityConsent,
            environment.accessibilityGranted,
            session.accessibilityConnected,
            {
                disclosure = "accessibility"
            },
            { actions.configure(options.copy(accessibilityConsent = false)) }
        )
        if (Build.VERSION.SDK_INT >= 33) {
            Text(
                stringResource(R.string.mobile_restricted),
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = {
                actions.settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            }) { Text(stringResource(R.string.mobile_app_info)) }
        }
        TextButton(onClick = tutorial) { Text(stringResource(R.string.mobile_tutorial)) }
    }
    SettingSection(stringResource(R.string.mobile_translation_settings)) {
        Text(stringResource(R.string.mobile_local_description))
        TextButton(onClick = {
            if (options.localConsent) {
                actions.configure(options.copy(localConsent = false))
            } else {
                disclosure =
                    "local"
            }
        }) {
            Text(
                stringResource(
                    if (options.localConsent) {
                        R.string.mobile_disable_translation
                    } else {
                        R.string.mobile_enable_translation
                    }
                )
            )
        }
        OutlinedButton(onClick = {
            targetPicker = true
        }) {
            Text(stringResource(R.string.mobile_target, languageName(options.target.languageTag)))
        }
        Text(
            stringResource(R.string.mobile_model_notice),
            style = MaterialTheme.typography.bodySmall
        )
    }
    SettingSection(stringResource(R.string.mobile_appearance)) {
        OutlinedButton(onClick = {
            themePicker = true
        }) { Text(stringResource(themeLabel(options.theme))) }
    }
    SettingSection(stringResource(R.string.mobile_result_notifications)) {
        Text(
            stringResource(
                if (actions.notificationsAllowed) {
                    R.string.mobile_notifications_on
                } else {
                    R.string.mobile_notifications_off
                }
            )
        )
        OutlinedButton(onClick = actions.notificationPermission) {
            Text(stringResource(R.string.mobile_notification_settings))
        }
    }
    SettingSection(stringResource(R.string.mobile_privacy)) {
        Text(stringResource(R.string.mobile_privacy_description))
        OutlinedButton(onClick = {
            actions.delete(null)
        }) { Text(stringResource(R.string.mobile_delete_all)) }
    }
    SettingSection(stringResource(R.string.mobile_compatibility)) {
        Text(stringResource(R.string.mobile_compatibility_description))
    }
    disclosure?.let { route ->
        AlertDialog(
            onDismissRequest = {
                disclosure = null
            },
            title = { Text(stringResource(R.string.mobile_consent_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(
                            when (route) {
                                "notification" -> R.string.mobile_notification_disclosure
                                "accessibility" -> R.string.mobile_accessibility_disclosure
                                else -> R.string.mobile_local_disclosure
                            }
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    disclosure = null
                    when (route) {
                        "notification" -> {
                            actions.configure(options.copy(notificationConsent = true))
                            actions.settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        }

                        "accessibility" -> {
                            actions.configure(options.copy(accessibilityConsent = true))
                            actions.settings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        }

                        else -> actions.configure(options.copy(localConsent = true))
                    }
                }) { Text(stringResource(R.string.mobile_agree)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    disclosure = null
                }) { Text(stringResource(R.string.mobile_cancel)) }
            }
        )
    }
    if (targetPicker) {
        ChoiceDialog(
            stringResource(R.string.mobile_translation_settings),
            LocalTarget.entries.map {
                languageName(it.languageTag)
            },
            {
                targetPicker =
                    false
            }
        ) {
            targetPicker = false
            actions.configure(options.copy(target = LocalTarget.entries[it]))
        }
    }
    if (themePicker) {
        ChoiceDialog(
            stringResource(R.string.mobile_appearance),
            ThemeChoice.entries.map {
                stringResource(themeLabel(it))
            },
            {
                themePicker =
                    false
            }
        ) {
            themePicker = false
            actions.configure(options.copy(theme = ThemeChoice.entries[it]))
        }
    }
}

@Composable
private fun AccessRow(
    title: String,
    consent: Boolean,
    granted: Boolean,
    connected: Boolean,
    enable: () -> Unit,
    withdraw: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(
                when {
                    !consent -> R.string.mobile_consent_needed
                    !granted -> R.string.mobile_access_needed
                    !connected -> R.string.mobile_reconnect_needed
                    else -> R.string.mobile_connected
                }
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = enable) { Text(stringResource(R.string.mobile_review_access)) }
            if (consent) {
                TextButton(onClick = withdraw) {
                    Text(stringResource(R.string.mobile_withdraw))
                }
            }
        }
    }
}

@Composable
fun SettingSection(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
fun ChoiceDialog(title: String, choices: List<String>, dismiss: () -> Unit, choose: (Int) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            choices.forEachIndexed { index, label ->
                TextButton(onClick = {
                    choose(index)
                }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        }
    }, confirmButton = {
        TextButton(onClick = dismiss) { Text(stringResource(R.string.mobile_cancel)) }
    })
}

fun themeLabel(choice: ThemeChoice): Int = when (choice) {
    ThemeChoice.SYSTEM -> R.string.mobile_system
    ThemeChoice.LIGHT -> R.string.mobile_light
    ThemeChoice.DARK -> R.string.mobile_dark
}
