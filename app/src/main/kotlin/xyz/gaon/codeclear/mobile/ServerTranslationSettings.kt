package xyz.gaon.codeclear.mobile

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.probe.LocalTarget
import xyz.gaon.codeclear.translation.TranslationEngineKind

@Composable
internal fun ServerTranslationSettings(options: MobileOptions, actions: MobileActions) {
    var disclosure by remember { mutableStateOf(false) }
    var targetPicker by remember { mutableStateOf(false) }
    val active = options.engine == TranslationEngineKind.MY_MEMORY && options.translationAllowed
    SettingsGroup {
        SettingNote(stringResource(R.string.mobile_server_description))
        SettingSwitchRow(
            stringResource(R.string.mobile_server_permission),
            stringResource(if (active) R.string.mobile_on else R.string.mobile_off),
            checked = active,
            enabled = options.labsEnabled,
            onCheckedChange = {
                if (it) {
                    disclosure = true
                } else {
                    actions.configure(
                        options.copy(serverAllowed = false)
                    )
                }
            }
        )
        SettingDivider()
        SettingRow(
            stringResource(
                R.string.mobile_target_language
            ),
            languageName(options.target.languageTag),
            onClick = {
                targetPicker =
                    true
            }
        )
        SettingNote(stringResource(R.string.mobile_server_translation_notice))
        ProviderTermsLink()
    }
    if (targetPicker) {
        ChoiceDialog(
            stringResource(R.string.mobile_target_language),
            LocalTarget.entries.map {
                languageName(it.languageTag)
            },
            {
                targetPicker =
                    false
            },
            options.target.ordinal
        ) {
            targetPicker = false
            actions.configure(options.copy(target = LocalTarget.entries[it]))
        }
    }
    if (disclosure) {
        MobileConfirmationDialog(
            onDismissRequest = { disclosure = false },
            title = { Text(stringResource(R.string.mobile_server_permission)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.mobile_server_disclosure))
                    ProviderTermsLink()
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    disclosure = false
                    if (options.labsEnabled) {
                        actions.configure(
                            options.copy(
                                engine = TranslationEngineKind.MY_MEMORY,
                                serverAllowed = true
                            )
                        )
                    }
                }, enabled = options.labsEnabled) { Text(stringResource(R.string.mobile_allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    disclosure = false
                }) { Text(stringResource(R.string.mobile_cancel)) }
            }
        )
    }
}

@Composable
internal fun ProviderTermsLink() {
    val handler = LocalUriHandler.current
    val context = LocalContext.current
    TextButton(onClick = {
        try {
            handler.openUri("https://mymemory.translated.net/terms-and-conditions")
        } catch (_: RuntimeException) {
            Toast.makeText(context, R.string.mobile_link_unavailable, Toast.LENGTH_LONG).show()
        }
    }) { Text(stringResource(R.string.mobile_server_terms)) }
}
