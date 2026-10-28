package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.probe.LocalEngineKind
import xyz.gaon.codeclear.probe.LocalTarget
import xyz.gaon.codeclear.probe.SystemEngineAvailability
import xyz.gaon.codeclear.probe.SystemTargetSupport

private data class EngineConsent(val engine: LocalEngineKind, val enable: Boolean)

@Composable
fun LocalTranslationSettings(options: MobileOptions, actions: MobileActions) {
    var enginePicker by remember { mutableStateOf(false) }
    var targetPicker by remember { mutableStateOf(false) }
    var proposal by remember { mutableStateOf<EngineConsent?>(null) }
    val availability = actions.systemAvailability
    val support = availability.targets[options.target] ?: SystemTargetSupport()
    val systemSelectable = availability.loaded && !availability.loading &&
        availability.platformSupported && support.available
    LaunchedEffect(Unit) { actions.refreshSystem(false) }
    SettingSection(stringResource(R.string.mobile_translation_settings)) {
        OutlinedButton(onClick = { enginePicker = true }) {
            Text(
                stringResource(R.string.mobile_engine, stringResource(engineLabel(options.engine)))
            )
        }
        Text(
            stringResource(
                if (options.engine == LocalEngineKind.ML_KIT) {
                    R.string.mobile_local_description
                } else {
                    R.string.mobile_system_description
                }
            )
        )
        TextButton(
            onClick = {
                if (options.localConsent) {
                    actions.configure(options.copy(localConsent = false))
                } else {
                    proposal = EngineConsent(options.engine, enable = true)
                }
            },
            enabled =
                options.localConsent || options.engine == LocalEngineKind.ML_KIT || systemSelectable
        ) {
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
        OutlinedButton(onClick = { targetPicker = true }) {
            Text(stringResource(R.string.mobile_target, languageName(options.target.languageTag)))
        }
        if (options.engine == LocalEngineKind.ML_KIT) {
            Text(
                stringResource(R.string.mobile_model_notice),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            SystemSupportStatus(availability, options.target)
            TextButton(
                onClick = actions.systemSettings,
                enabled =
                    availability.loaded && !availability.loading && availability.platformSupported
            ) { Text(stringResource(R.string.mobile_system_models_action)) }
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
                    LocalEngineKind.entries.forEach { engine ->
                        TextButton(
                            onClick = {
                                enginePicker = false
                                if (engine !=
                                    options.engine
                                ) {
                                    proposal = EngineConsent(engine, enable = false)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().semantics {
                                selected =
                                    engine == options.engine
                            },
                            enabled = engine == LocalEngineKind.ML_KIT || systemSelectable
                        ) { Text(stringResource(engineLabel(engine))) }
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
                ) { Text(stringResource(R.string.mobile_agree)) }
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
            stringResource(R.string.mobile_translation_settings),
            LocalTarget.entries.map { languageName(it.languageTag) },
            { targetPicker = false }
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

        !availability.platformSupported || availability.targets.values.none { it.available } ->
            stringResource(R.string.mobile_engine_unavailable)

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
