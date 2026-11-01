package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import java.util.Locale
import xyz.gaon.codeclear.R

@Composable
internal fun ModelManagement(actions: MobileActions) {
    val inventory = actions.modelInventory
    val preparation = actions.modelPreparation
    val displayLocale = LocalConfiguration.current.locales[0]
    var requested by remember { mutableStateOf<String?>(null) }
    val busy =
        preparation.phase in setOf(PreparationPhase.IDENTIFYING, PreparationPhase.WAITING_FOR_WIFI)
    LaunchedEffect(Unit) { actions.refreshModels(false) }
    Column(Modifier.testTag("model-management")) {
        SettingsGroup {
            SettingRow(
                stringResource(R.string.mobile_models_title),
                stringResource(
                    when {
                        inventory.checking || !inventory.loaded -> R.string.mobile_engine_checking
                        inventory.failed -> R.string.mobile_models_query_failed
                        else -> R.string.mobile_models_refresh
                    }
                ),
                onClick = { actions.refreshModels(true) },
                enabled = !inventory.checking
            )
            SettingNote(stringResource(R.string.mobile_models_update_notice))
            (primaryModelLanguages + inventory.downloaded).forEach { language ->
                SettingDivider()
                val label = remember(language, displayLocale) {
                    when {
                        language == "zh" -> "简体中文 / 繁體中文"
                        language in primaryModelLanguages -> languageName(language)
                        else -> Locale.forLanguageTag(language).getDisplayLanguage(displayLocale)
                    }
                }
                val status = when {
                    busy && preparation.modelLanguage == language -> R.string.mobile_downloading
                    !inventory.loaded || inventory.checking -> R.string.mobile_engine_checking
                    inventory.failed -> R.string.mobile_models_query_failed
                    language in inventory.failedUpdates -> R.string.mobile_failed
                    language in inventory.downloaded -> R.string.mobile_models_installed
                    else -> R.string.mobile_models_not_installed
                }
                Column(Modifier.testTag("model-$language")) {
                    SettingRow(
                        label,
                        stringResource(status),
                        onClick = { requested = language },
                        enabled = inventory.loaded && !inventory.checking && !busy
                    )
                    inventory.updateChecks[language]?.let {
                        SettingNote(
                            stringResource(R.string.mobile_models_last_checked, dateTime(it))
                        )
                    }
                }
            }
        }
    }
    requested?.let { language ->
        MobileConfirmationDialog(
            onDismissRequest = { requested = null },
            title = {
                Text(
                    stringResource(
                        if (language in
                            inventory.downloaded
                        ) {
                            R.string.mobile_models_update
                        } else {
                            R.string.mobile_prepare
                        }
                    )
                )
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.mobile_models_update_notice))
                    Text(stringResource(R.string.mobile_model_notice))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    requested = null
                    actions.prepareModel(language)
                }, enabled = !busy) {
                    Text(stringResource(R.string.mobile_models_download))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    requested = null
                }) { Text(stringResource(R.string.mobile_cancel)) }
            }
        )
    }
}
