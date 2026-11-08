package xyz.gaon.codeclear.mobile

import android.os.Build
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
import xyz.gaon.codeclear.capture.ProbeEnvironment
import xyz.gaon.codeclear.capture.ProbeRadio
import xyz.gaon.codeclear.capture.ProbeSessionState

private enum class SettingsInfo { RESTRICTED_ACCESS, PRIVACY }
private enum class CaptureAccess { NOTIFICATION, ACCESSIBILITY }

@Composable
fun MobileSettings(
    page: SettingsPage,
    state: MobileState,
    environment: ProbeEnvironment,
    session: ProbeSessionState,
    actions: MobileActions,
    tutorial: () -> Unit,
    navigate: (SettingsPage) -> Unit = {},
    showTutorialLink: Boolean = true
) {
    val options = state.options
    var disclosure by remember { mutableStateOf<CaptureAccess?>(null) }
    var info by remember { mutableStateOf<SettingsInfo?>(null) }
    var themePicker by remember { mutableStateOf(false) }
    var uiLanguagePicker by remember { mutableStateOf(false) }
    var messageViewPicker by remember { mutableStateOf(false) }
    val captureAvailable = environment.radio != ProbeRadio.ABSENT
    if (page == SettingsPage.CAPTURE) {
        SettingsGroup(
            title = stringResource(R.string.mobile_settings_access_group),
            footer = when {
                !captureAvailable -> stringResource(R.string.mobile_wifi_only)

                options.accessibilityConsent && !environment.accessibilityGranted ->
                    stringResource(R.string.mobile_accessibility_restart_notice)

                else -> null
            },
            tag = "capture-access-group"
        ) {
            AccessRow(
                stringResource(R.string.mobile_notification_access),
                options.notificationConsent,
                environment.notificationGranted,
                session.notificationConnected,
                captureAvailable,
                { disclosure = CaptureAccess.NOTIFICATION },
                { actions.configure(options.copy(notificationConsent = false)) }
            )
            SettingDivider()
            AccessRow(
                stringResource(R.string.mobile_accessibility_access),
                options.accessibilityConsent,
                environment.accessibilityGranted,
                session.accessibilityConnected,
                captureAvailable,
                { disclosure = CaptureAccess.ACCESSIBILITY },
                { actions.configure(options.copy(accessibilityConsent = false)) }
            )
            if (captureAvailable && options.accessibilityConsent &&
                environment.accessibilityGranted && !session.accessibilityConnected
            ) {
                SettingRow(
                    stringResource(R.string.mobile_open_settings),
                    stringResource(R.string.mobile_reconnect_needed),
                    onClick = { actions.settings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
                )
            }
        }
        if ((captureAvailable && Build.VERSION.SDK_INT >= 33) || showTutorialLink) {
            SettingsGroup(title = stringResource(R.string.mobile_settings_help_group)) {
                if (captureAvailable && Build.VERSION.SDK_INT >= 33) {
                    SettingRow(stringResource(R.string.mobile_permission_help), onClick = {
                        info = SettingsInfo.RESTRICTED_ACCESS
                    }, navigation = true)
                }
                if (showTutorialLink) {
                    if (captureAvailable && Build.VERSION.SDK_INT >= 33) SettingDivider()
                    SettingRow(
                        stringResource(R.string.mobile_tutorial),
                        onClick = tutorial,
                        navigation = true
                    )
                }
            }
        }
    }
    if (page == SettingsPage.SHARING) NearbySettings(state, actions)
    if (page == SettingsPage.LABS) {
        SettingsGroup(
            footer = stringResource(R.string.mobile_labs_notice),
            tag = "labs-control-group"
        ) {
            SettingSwitchRow(
                stringResource(R.string.mobile_labs_enable),
                stringResource(
                    if (options.labsEnabled) R.string.mobile_on else R.string.mobile_off
                ),
                checked = options.labsEnabled,
                enabled = state.loaded && !state.storageError
            ) {
                actions.configure(
                    options.copy(
                        labsEnabled = it,
                        labsUnlocked = true,
                        nearbyEnabled = false,
                        serverAllowed = false,
                        ownedServerAllowed = false
                    )
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.mobile_settings_features_group)) {
            SettingRow(
                stringResource(R.string.mobile_nearby),
                onClick = { navigate(SettingsPage.SHARING) },
                enabled = options.labsEnabled,
                navigation = true
            )
            SettingDivider()
            SettingRow(
                stringResource(R.string.mobile_server_translation),
                "MyMemory",
                onClick = { navigate(SettingsPage.SERVER) },
                enabled = options.labsEnabled,
                navigation = true
            )
            if (actions.serverAccess.configured) {
                SettingDivider()
                SettingRow(
                    stringResource(R.string.mobile_owned_server),
                    onClick = { navigate(SettingsPage.OWNED_SERVER) },
                    enabled = options.labsEnabled,
                    navigation = true
                )
            }
        }
    }
    if (page == SettingsPage.SERVER) {
        ServerTranslationSettings(options, actions)
    }
    if (page == SettingsPage.OWNED_SERVER) OwnedServerSettings(options, actions)
    if (page == SettingsPage.TRANSLATION) {
        LocalTranslationSettings(options, actions)
    }
    if (page == SettingsPage.APPEARANCE) {
        SettingsGroup(
            title = stringResource(R.string.mobile_settings_display_group),
            tag = "appearance-display-group"
        ) {
            SettingRow(
                stringResource(R.string.mobile_message_view),
                stringResource(
                    if (options.messageViewMode == MessageViewMode.SWITCH) {
                        R.string.mobile_message_switch
                    } else {
                        R.string.mobile_message_both
                    }
                ),
                onClick = { messageViewPicker = true },
                navigation = true,
                value = true
            )
            SettingDivider()
            SettingRow(
                stringResource(R.string.mobile_screen_mode),
                stringResource(themeLabel(options.theme)),
                onClick = { themePicker = true },
                navigation = true,
                value = true
            )
        }
        SettingsGroup(
            title = stringResource(R.string.mobile_settings_language_group),
            tag = "appearance-language-group"
        ) {
            SettingRow(
                stringResource(R.string.mobile_ui_language_title),
                if (options.uiLanguage.isEmpty()) {
                    stringResource(
                        R.string.mobile_system
                    )
                } else {
                    languageName(options.uiLanguage)
                },
                onClick = { uiLanguagePicker = true },
                navigation = true,
                value = true
            )
        }
    }
    if (page == SettingsPage.NOTIFICATIONS) {
        SettingsGroup(
            footer = stringResource(R.string.mobile_result_notification_summary),
            tag = "notification-control-group"
        ) {
            SettingSwitchRow(
                stringResource(R.string.mobile_result_notifications),
                stringResource(
                    if (actions.notificationsAllowed) {
                        R.string.mobile_on
                    } else {
                        R.string.mobile_off
                    }
                ),
                checked = actions.notificationsAllowed,
                onCheckedChange = { actions.notificationPermission() }
            )
        }
        PresentationSettings(options, actions)
    }
    if (page == SettingsPage.PRIVACY) {
        SettingsGroup(title = stringResource(R.string.mobile_settings_data_group)) {
            SettingRow(stringResource(R.string.mobile_data_storage), onClick = {
                info =
                    SettingsInfo.PRIVACY
            }, navigation = true)
            SettingDivider()
            PrivacyPolicyLink()
        }
        SettingsGroup(
            title = stringResource(R.string.mobile_history),
            tag = "history-delete-group"
        ) {
            SettingRow(stringResource(R.string.mobile_delete_all), onClick = {
                actions.delete(null)
            }, destructive = true)
        }
    }
    if (page == SettingsPage.COMPATIBILITY) {
        CompatibilityChecklist(state, environment, session, actions)
    }
    if (page == SettingsPage.ABOUT) {
        AppAboutSettings(
            labsVisible = options.labsVisible,
            unlockLabs = { actions.configure(options.copy(labsUnlocked = true)) },
            compatibility = { navigate(SettingsPage.COMPATIBILITY) }
        )
    }
    disclosure?.let { route ->
        val notification = route == CaptureAccess.NOTIFICATION
        MobileConfirmationDialog(
            onDismissRequest = { disclosure = null },
            title = {
                Text(
                    stringResource(
                        if (notification) {
                            R.string.mobile_notification_access
                        } else {
                            R.string.mobile_accessibility_access
                        }
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (notification) {
                            R.string.mobile_notification_disclosure
                        } else {
                            R.string.mobile_accessibility_disclosure
                        }
                    ),
                    Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    disclosure = null
                    if (notification) {
                        actions.configure(options.copy(notificationConsent = true))
                        if (!environment.notificationGranted) {
                            actions.settings(
                                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                            )
                        }
                    } else {
                        actions.configure(options.copy(accessibilityConsent = true))
                        if (!environment.accessibilityGranted) {
                            actions.settings(
                                Settings.ACTION_ACCESSIBILITY_SETTINGS
                            )
                        }
                    }
                }) {
                    Text(
                        stringResource(
                            if ((notification && environment.notificationGranted) ||
                                (!notification && environment.accessibilityGranted)
                            ) {
                                R.string.mobile_enable
                            } else {
                                R.string.mobile_open_settings
                            }
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    disclosure = null
                }) { Text(stringResource(R.string.mobile_cancel)) }
            }
        )
    }
    info?.let { page ->
        val title = when (page) {
            SettingsInfo.RESTRICTED_ACCESS -> R.string.mobile_permission_help
            SettingsInfo.PRIVACY -> R.string.mobile_data_storage
        }
        MobileConfirmationDialog(
            onDismissRequest = { info = null },
            title = { Text(stringResource(title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        stringResource(
                            when (page) {
                                SettingsInfo.RESTRICTED_ACCESS -> R.string.mobile_restricted
                                SettingsInfo.PRIVACY -> R.string.mobile_privacy_description
                            }
                        )
                    )
                    if (page == SettingsInfo.RESTRICTED_ACCESS) {
                        TextButton(onClick = {
                            actions.settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        }) { Text(stringResource(R.string.mobile_app_info)) }
                        TextButton(onClick = {
                            actions.settings(Settings.ACTION_APPLICATION_SETTINGS)
                        }) { Text(stringResource(R.string.mobile_app_list)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    info = null
                }) { Text(stringResource(R.string.mobile_close)) }
            }
        )
    }
    if (themePicker) {
        ChoiceDialog(
            stringResource(R.string.mobile_screen_mode),
            ThemeChoice.entries.map { stringResource(themeLabel(it)) },
            { themePicker = false },
            selectedIndex = options.theme.ordinal
        ) {
            themePicker = false
            actions.configure(options.copy(theme = ThemeChoice.entries[it]))
        }
    }
    if (messageViewPicker) {
        ChoiceDialog(
            stringResource(R.string.mobile_message_view),
            listOf(
                stringResource(R.string.mobile_message_both),
                stringResource(R.string.mobile_message_switch)
            ),
            { messageViewPicker = false },
            options.messageViewMode.ordinal
        ) {
            messageViewPicker = false
            actions.configure(options.copy(messageViewMode = MessageViewMode.entries[it]))
        }
    }
    if (uiLanguagePicker) {
        val languages = listOf("") + UiLanguages.supported
        ChoiceDialog(
            stringResource(R.string.mobile_ui_language_title),
            languages.map {
                if (it.isEmpty()) stringResource(R.string.mobile_system) else languageName(it)
            },
            { uiLanguagePicker = false },
            selectedIndex = languages.indexOf(options.uiLanguage)
        ) {
            uiLanguagePicker = false
            actions.configure(options.copy(uiLanguage = languages[it]))
        }
    }
}
