package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import xyz.gaon.codeclear.R

@Composable
internal fun ModelManagement(actions: MobileActions) {
    val inventory = actions.modelInventory
    val preparation = actions.modelPreparation
    val displayLocale = LocalConfiguration.current.locales[0]
    val busy =
        preparation.phase in setOf(PreparationPhase.IDENTIFYING, PreparationPhase.WAITING_FOR_WIFI)
    LaunchedEffect(Unit) { actions.refreshModels(false) }
    Column(Modifier.testTag("model-management")) {
        SettingsGroup {
            SettingRow(
                stringResource(R.string.mobile_models_title),
                stringResource(
                    when {
                        busy -> R.string.mobile_downloading
                        inventory.checking || !inventory.loaded -> R.string.mobile_engine_checking
                        inventory.failed -> R.string.mobile_models_query_failed
                        else -> R.string.mobile_models_refresh
                    }
                ),
                onClick = {
                    if (busy) actions.showModelDownload() else actions.refreshModels(true)
                },
                enabled = busy || !inventory.checking
            )
            SettingNote(stringResource(R.string.mobile_models_update_notice))
            (primaryModelLanguages + inventory.downloaded).forEach { language ->
                SettingDivider()
                val label =
                    remember(language, displayLocale) { modelLanguageName(language, displayLocale) }
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
                        onClick = {
                            if (busy) {
                                actions.showModelDownload()
                            } else {
                                actions.prepareModel(
                                    language
                                )
                            }
                        },
                        enabled =
                            (busy && language in preparation.languages) ||
                                (inventory.loaded && !inventory.checking && !busy)
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
}
