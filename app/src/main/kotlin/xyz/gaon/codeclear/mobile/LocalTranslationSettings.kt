package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.probe.LocalTarget
import xyz.gaon.codeclear.probe.SystemEngineAvailability
import xyz.gaon.codeclear.probe.SystemTargetSupport
import xyz.gaon.codeclear.translation.TranslationEngineKind

private data class TranslationChange(val engine: TranslationEngineKind, val enable: Boolean)

@Composable
fun LocalTranslationSettings(options: MobileOptions, actions: MobileActions) {
    var enginePicker by remember { mutableStateOf(false) }
    var targetPicker by remember { mutableStateOf(false) }
    var systemPreparation by remember { mutableStateOf(false) }
    var proposal by remember { mutableStateOf<TranslationChange?>(null) }
    val availability = actions.systemAvailability
    val support = availability.targets[options.target] ?: SystemTargetSupport()
    val systemSelectable = availability.loaded && !availability.loading &&
        availability.platformSupported && !availability.queryFailed && support.available
    LaunchedEffect(Unit) { actions.refreshSystem(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsGroup {
            SettingSwitchRow(
                stringResource(R.string.mobile_auto_translation),
                stringResource(
                    if (options.translationAllowed) R.string.mobile_on else R.string.mobile_off
                ),
                checked = options.translationAllowed,
                enabled =
                    options.translationAllowed || options.engine == TranslationEngineKind.ML_KIT ||
                        (
                            options.engine == TranslationEngineKind.MY_MEMORY && options.labsEnabled
                            ) ||
                        systemSelectable,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        actions.configure(
                            if (options.engine ==
                                TranslationEngineKind.MY_MEMORY
                            ) {
                                options.copy(serverAllowed = false)
                            } else {
                                options.copy(localConsent = false)
                            }
                        )
                    } else {
                        proposal = TranslationChange(options.engine, enable = true)
                    }
                }
            )
            SettingDivider()
            SettingRow(
                stringResource(R.string.mobile_target_language),
                languageName(options.target.languageTag),
                onClick = { targetPicker = true }
            )
            SettingDivider()
            SettingRow(
                stringResource(R.string.mobile_engine_title),
                stringResource(engineLabel(options.engine)),
                onClick = { enginePicker = true }
            )
            SettingNote(
                stringResource(
                    if (options.engine == TranslationEngineKind.MY_MEMORY) {
                        R.string.mobile_server_description
                    } else if (options.engine == TranslationEngineKind.ML_KIT) {
                        R.string.mobile_local_description
                    } else {
                        R.string.mobile_system_description
                    }
                )
            )
            if (options.engine == TranslationEngineKind.SYSTEM) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    SystemSupportStatus(availability, options.target)
                }
                SettingRow(
                    stringResource(R.string.mobile_system_models_action),
                    onClick = actions.systemSettings,
                    enabled =
                        availability.loaded && !availability.loading &&
                            availability.platformSupported
                )
            }
        }
        if (options.engine == TranslationEngineKind.ML_KIT) ModelManagement(actions)
    }
    if (enginePicker) {
        MobileConfirmationDialog(
            onDismissRequest = { enginePicker = false },
            title = { Text(stringResource(R.string.mobile_engine_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.selectableGroup()) {
                        TranslationEngineKind.entries.filter {
                            it != TranslationEngineKind.MY_MEMORY ||
                                options.labsEnabled
                        }.forEach { engine ->
                            RadioChoiceRow(
                                stringResource(engineLabel(engine)),
                                selected = engine == options.engine,
                                enabled =
                                    engine != TranslationEngineKind.SYSTEM || !availability.loading,
                                onClick = {
                                    enginePicker = false
                                    if (engine == TranslationEngineKind.SYSTEM &&
                                        !systemSelectable
                                    ) {
                                        systemPreparation = true
                                    } else if (engine != options.engine) {
                                        proposal =
                                            TranslationChange(
                                                engine,
                                                enable =
                                                    engine == TranslationEngineKind.MY_MEMORY &&
                                                        options.translationAllowed
                                            )
                                    }
                                }
                            )
                        }
                    }
                    SystemSupportStatus(availability, options.target)
                    TextButton(onClick = {
                        actions.refreshSystem(true)
                    }, enabled = !availability.loading) {
                        Text(stringResource(R.string.mobile_engine_refresh))
                    }
                    TextButton(
                        onClick = actions.systemSettings,
                        enabled =
                            availability.loaded && !availability.loading &&
                                availability.platformSupported
                    ) { Text(stringResource(R.string.mobile_system_models_action)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    enginePicker = false
                }) { Text(stringResource(R.string.mobile_close)) }
            }
        )
    }
    if (systemPreparation) {
        MobileConfirmationDialog(
            onDismissRequest = {
                systemPreparation = false
                enginePicker = true
            },
            title = { Text(stringResource(R.string.mobile_engine_system)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SystemSupportStatus(availability, options.target)
                    Text(stringResource(R.string.mobile_system_preparation_explanation))
                    TextButton(onClick = {
                        actions.refreshSystem(true)
                    }, enabled = !availability.loading) {
                        Text(stringResource(R.string.mobile_engine_refresh))
                    }
                    TextButton(
                        onClick = actions.systemSettings,
                        enabled = availability.platformSupported
                    ) {
                        Text(stringResource(R.string.mobile_system_models_action))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    systemPreparation = false
                    enginePicker = true
                }) {
                    Text(stringResource(R.string.mobile_close))
                }
            }
        )
    }
    proposal?.let { request ->
        MobileConfirmationDialog(
            onDismissRequest = { proposal = null },
            title = { Text(stringResource(engineLabel(request.engine))) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        stringResource(
                            if (request.engine == TranslationEngineKind.MY_MEMORY) {
                                R.string.mobile_server_disclosure
                            } else if (request.engine == TranslationEngineKind.SYSTEM) {
                                R.string.mobile_system_disclosure
                            } else {
                                R.string.mobile_local_disclosure
                            }
                        )
                    )
                    if (request.engine == TranslationEngineKind.MY_MEMORY) ProviderTermsLink()
                    if (!request.enable) Text(stringResource(R.string.mobile_engine_switch_notice))
                    if (request.engine ==
                        TranslationEngineKind.SYSTEM
                    ) {
                        SystemSupportStatus(availability, options.target)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        proposal = null
                        if ((request.engine != TranslationEngineKind.SYSTEM || systemSelectable) &&
                            (
                                request.engine != TranslationEngineKind.MY_MEMORY ||
                                    options.labsEnabled
                                )
                        ) {
                            actions.configure(
                                options.copy(
                                    engine = request.engine,
                                    localConsent = if (request.engine ==
                                        TranslationEngineKind.MY_MEMORY
                                    ) {
                                        options.localConsent
                                    } else {
                                        request.enable ||
                                            options.localConsent
                                    },
                                    serverAllowed = if (request.engine ==
                                        TranslationEngineKind.MY_MEMORY
                                    ) {
                                        request.enable ||
                                            options.serverAllowed
                                    } else {
                                        options.serverAllowed
                                    }
                                )
                            )
                        }
                    },
                    enabled =
                        (request.engine != TranslationEngineKind.SYSTEM || systemSelectable) &&
                            (
                                request.engine != TranslationEngineKind.MY_MEMORY ||
                                    options.labsEnabled
                                )
                ) {
                    Text(
                        stringResource(
                            if (request.enable &&
                                request.engine == TranslationEngineKind.MY_MEMORY
                            ) {
                                R.string.mobile_allow
                            } else if (request.enable) {
                                R.string.mobile_enable
                            } else {
                                R.string.mobile_change
                            }
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    proposal = null
                }) { Text(stringResource(R.string.mobile_cancel)) }
            }
        )
    }
    if (targetPicker) {
        LaunchedEffect(Unit) {
            if (options.engine ==
                TranslationEngineKind.ML_KIT
            ) {
                actions.refreshModels(true)
            }
        }
        ChoiceDialog(
            stringResource(R.string.mobile_target_language),
            LocalTarget.entries.map { languageName(it.languageTag) },
            { targetPicker = false },
            selectedIndex = options.target.ordinal
        ) {
            targetPicker = false
            val selected = LocalTarget.entries[it]
            actions.configure(options.copy(target = selected))
            if (options.engine == TranslationEngineKind.ML_KIT && selected.modelCode != "en" &&
                (
                    !actions.modelInventory.loaded ||
                        selected.modelCode !in actions.modelInventory.downloaded
                    )
            ) {
                actions.prepareTargetModels(selected, null)
            }
        }
    }
}

@Composable
private fun SystemSupportStatus(availability: SystemEngineAvailability, target: LocalTarget) {
    val support = availability.targets[target] ?: SystemTargetSupport()
    val label = when {
        !availability.loaded || availability.loading -> stringResource(
            R.string.mobile_engine_checking
        )

        !availability.platformSupported ->
            stringResource(R.string.mobile_engine_unavailable)

        availability.queryFailed -> stringResource(R.string.mobile_engine_query_failed)

        availability.targets.values.none {
            it.available
        } -> stringResource(R.string.mobile_engine_no_pairs)

        !support.available -> stringResource(
            R.string.mobile_engine_pair_missing,
            languageName(target.languageTag)
        )

        else -> stringResource(
            R.string.mobile_engine_pairs,
            languageName(target.languageTag),
            support.readyPairs,
            support.unpreparedPairs
        )
    }
    Text(label, style = MaterialTheme.typography.bodySmall)
}

fun engineLabel(engine: TranslationEngineKind): Int = when (engine) {
    TranslationEngineKind.ML_KIT -> R.string.mobile_engine_mlkit
    TranslationEngineKind.SYSTEM -> R.string.mobile_engine_system
    TranslationEngineKind.MY_MEMORY -> R.string.mobile_engine_mymemory
}
