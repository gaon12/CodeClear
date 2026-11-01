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
import xyz.gaon.codeclear.probe.LocalEngineKind
import xyz.gaon.codeclear.probe.LocalTarget
import xyz.gaon.codeclear.probe.SystemEngineAvailability
import xyz.gaon.codeclear.probe.SystemTargetSupport

private data class TranslationChange(val engine: LocalEngineKind, val enable: Boolean)

@Composable
fun LocalTranslationSettings(options: MobileOptions, actions: MobileActions) {
    var enginePicker by remember { mutableStateOf(false) }
    var targetPicker by remember { mutableStateOf(false) }
    var proposal by remember { mutableStateOf<TranslationChange?>(null) }
    val availability = actions.systemAvailability
    val support = availability.targets[options.target] ?: SystemTargetSupport()
    val systemSelectable = availability.loaded && !availability.loading &&
        availability.platformSupported && !availability.queryFailed && support.available
    LaunchedEffect(Unit) { actions.refreshSystem(false) }
    SettingsGroup {
        SettingSwitchRow(
            stringResource(R.string.mobile_auto_translation),
            stringResource(if (options.localConsent) R.string.mobile_on else R.string.mobile_off),
            checked = options.localConsent,
            enabled =
                options.localConsent || options.engine == LocalEngineKind.ML_KIT ||
                    systemSelectable,
            onCheckedChange = { enabled ->
                if (!enabled) {
                    actions.configure(options.copy(localConsent = false))
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
                if (options.engine ==
                    LocalEngineKind.ML_KIT
                ) {
                    R.string.mobile_local_description
                } else {
                    R.string.mobile_system_description
                }
            )
        )
        if (options.engine == LocalEngineKind.SYSTEM) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                SystemSupportStatus(availability, options.target)
            }
            SettingRow(
                stringResource(R.string.mobile_system_models_action),
                onClick = actions.systemSettings,
                enabled =
                    availability.loaded && !availability.loading && availability.platformSupported
            )
        }
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
                        LocalEngineKind.entries.forEach { engine ->
                            RadioChoiceRow(
                                stringResource(engineLabel(engine)),
                                selected = engine == options.engine,
                                enabled = engine == LocalEngineKind.ML_KIT || systemSelectable,
                                onClick = {
                                    enginePicker = false
                                    if (engine !=
                                        options.engine
                                    ) {
                                        proposal =
                                            TranslationChange(engine, enable = false)
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
                            if (request.engine ==
                                LocalEngineKind.SYSTEM
                            ) {
                                R.string.mobile_system_disclosure
                            } else {
                                R.string.mobile_local_disclosure
                            }
                        )
                    )
                    if (!request.enable) Text(stringResource(R.string.mobile_engine_switch_notice))
                    if (request.engine ==
                        LocalEngineKind.SYSTEM
                    ) {
                        SystemSupportStatus(availability, options.target)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        proposal = null
                        if (request.engine == LocalEngineKind.ML_KIT || systemSelectable) {
                            actions.configure(
                                options.copy(
                                    engine = request.engine,
                                    localConsent = request.enable || options.localConsent
                                )
                            )
                        }
                    },
                    enabled = request.engine == LocalEngineKind.ML_KIT || systemSelectable
                ) {
                    Text(
                        stringResource(
                            if (request.enable) R.string.mobile_enable else R.string.mobile_change
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
        ChoiceDialog(
            stringResource(R.string.mobile_target_language),
            LocalTarget.entries.map { languageName(it.languageTag) },
            { targetPicker = false },
            selectedIndex = options.target.ordinal
        ) {
            targetPicker = false
            actions.configure(options.copy(target = LocalTarget.entries[it]))
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

fun engineLabel(engine: LocalEngineKind): Int = when (engine) {
    LocalEngineKind.ML_KIT -> R.string.mobile_engine_mlkit
    LocalEngineKind.SYSTEM -> R.string.mobile_engine_system
}
