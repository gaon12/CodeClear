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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.SystemEngineAvailability
import xyz.gaon.codeclear.translation.SystemTargetSupport
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationTarget

private data class TranslationChange(
    val engine: TranslationEngineKind,
    val enable: Boolean,
    val serverRevision: Long? = null,
    val serverCredentialId: String? = null
)

@Composable
fun LocalTranslationSettings(options: MobileOptions, actions: MobileActions) {
    var enginePicker by remember { mutableStateOf(false) }
    var targetPicker by remember { mutableStateOf(false) }
    var proposal by remember { mutableStateOf<TranslationChange?>(null) }
    val availability = actions.systemAvailability
    val support = availability.targets[options.target] ?: SystemTargetSupport()
    val systemSelectable = availability.loaded && !availability.loading &&
        availability.platformSupported && !availability.queryFailed && support.available
    val remoteSelectable = options.labsEnabled && options.serverAllowed
    val access = actions.serverAccess
    val ownedSelectable = options.labsEnabled && options.ownedServerAllowed &&
        access.configured && access.loaded && access.ready && !access.busy &&
        access.credentialId == options.ownedServerCredentialId
    LaunchedEffect(Unit) { actions.refreshSystem(false) }
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsGroup(tag = "translation-control-group") {
            SettingSwitchRow(
                stringResource(R.string.mobile_auto_translation),
                stringResource(
                    if (options.translationAllowed) R.string.mobile_on else R.string.mobile_off
                ),
                checked = options.translationAllowed,
                enabled = options.translationAllowed || when (options.engine) {
                    TranslationEngineKind.ML_KIT -> true
                    TranslationEngineKind.SYSTEM -> systemSelectable
                    TranslationEngineKind.MY_MEMORY -> remoteSelectable
                    TranslationEngineKind.OWNED_SERVER -> ownedSelectable
                },
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        actions.configure(
                            if (options.engine == TranslationEngineKind.OWNED_SERVER) {
                                options.copy(
                                    ownedServerAllowed = false,
                                    ownedServerCredentialId = ""
                                )
                            } else if (options.engine == TranslationEngineKind.MY_MEMORY) {
                                options.copy(serverAllowed = false)
                            } else {
                                options.copy(localConsent = false)
                            }
                        )
                    } else {
                        proposal = TranslationChange(
                            options.engine,
                            enable = true,
                            serverRevision = access.revision,
                            serverCredentialId = access.credentialId
                        )
                    }
                }
            )
        }
        SettingsGroup(
            title = stringResource(R.string.mobile_settings_translation_group),
            tag = "translation-options-group",
            footer = stringResource(
                when (options.engine) {
                    TranslationEngineKind.OWNED_SERVER -> R.string.mobile_owned_description
                    TranslationEngineKind.MY_MEMORY -> R.string.mobile_server_description
                    TranslationEngineKind.ML_KIT -> R.string.mobile_local_description
                    TranslationEngineKind.SYSTEM -> R.string.mobile_system_description
                }
            )
        ) {
            SettingRow(
                stringResource(R.string.mobile_target_language),
                languageName(options.target.languageTag),
                onClick = { targetPicker = true },
                navigation = true,
                value = true
            )
            SettingDivider()
            SettingRow(
                stringResource(R.string.mobile_engine_title),
                stringResource(engineLabel(options.engine)),
                onClick = { enginePicker = true },
                navigation = true,
                value = true
            )
            if (options.engine == TranslationEngineKind.SYSTEM) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    SystemSupportStatus(availability, options.target)
                }
            }
        }
        if (availability.platformSupported || options.engine == TranslationEngineKind.SYSTEM) {
            SettingsGroup {
                SettingRow(
                    stringResource(R.string.mobile_system_models_action),
                    onClick = actions.systemSettings,
                    enabled = availability.loaded && !availability.loading &&
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
                    Modifier.verticalScroll(rememberScrollState()).testTag("engine-picker"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.selectableGroup()) {
                        TranslationEngineKind.entries.filter {
                            (it != TranslationEngineKind.MY_MEMORY || remoteSelectable) &&
                                (it != TranslationEngineKind.OWNED_SERVER || ownedSelectable)
                        }.forEach { engine ->
                            val selectable = when (engine) {
                                TranslationEngineKind.ML_KIT -> true
                                TranslationEngineKind.SYSTEM -> systemSelectable
                                TranslationEngineKind.MY_MEMORY -> remoteSelectable
                                TranslationEngineKind.OWNED_SERVER -> ownedSelectable
                            }
                            RadioChoiceRow(
                                stringResource(engineLabel(engine)),
                                selected = engine == options.engine,
                                enabled = selectable,
                                onClick = {
                                    if (selectable && engine != options.engine) {
                                        enginePicker = false
                                        proposal =
                                            TranslationChange(
                                                engine,
                                                enable =
                                                    (
                                                        engine == TranslationEngineKind.MY_MEMORY ||
                                                            engine ==
                                                            TranslationEngineKind.OWNED_SERVER
                                                        ) &&
                                                        options.translationAllowed,
                                                serverRevision = access.revision,
                                                serverCredentialId = access.credentialId
                                            )
                                    }
                                }
                            )
                            if (engine == TranslationEngineKind.SYSTEM) {
                                SystemSupportStatus(
                                    availability,
                                    options.target,
                                    Modifier.padding(start = 46.dp, bottom = 8.dp)
                                )
                            }
                        }
                    }
                    TextButton(onClick = {
                        actions.refreshSystem(true)
                    }, enabled = !availability.loading) {
                        Text(stringResource(R.string.mobile_engine_refresh))
                    }
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
        val remoteApproval = request.engine == TranslationEngineKind.OWNED_SERVER ||
            (request.enable && request.engine == TranslationEngineKind.MY_MEMORY)
        val selectable = when (request.engine) {
            TranslationEngineKind.ML_KIT -> true

            TranslationEngineKind.SYSTEM -> systemSelectable

            TranslationEngineKind.MY_MEMORY -> remoteSelectable

            TranslationEngineKind.OWNED_SERVER ->
                ownedSelectable &&
                    request.serverRevision == access.revision &&
                    request.serverCredentialId == access.credentialId
        }
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
                            when (request.engine) {
                                TranslationEngineKind.ML_KIT -> R.string.mobile_local_disclosure

                                TranslationEngineKind.SYSTEM -> R.string.mobile_system_disclosure

                                TranslationEngineKind.MY_MEMORY -> R.string.mobile_server_disclosure

                                TranslationEngineKind.OWNED_SERVER -> {
                                    R.string.mobile_owned_disclosure
                                }
                            }
                        )
                    )
                    if (request.engine == TranslationEngineKind.MY_MEMORY) ProviderTermsLink()
                    if (request.engine ==
                        TranslationEngineKind.OWNED_SERVER
                    ) {
                        OwnedProviderTermsLink()
                    }
                    if (!request.enable) Text(stringResource(R.string.mobile_engine_switch_notice))
                    if (request.engine == TranslationEngineKind.SYSTEM) {
                        SystemSupportStatus(availability, options.target)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = selectable, onClick = {
                    proposal = null
                    if (selectable) {
                        if (request.engine == TranslationEngineKind.OWNED_SERVER) {
                            actions.allowServer(
                                requireNotNull(request.serverRevision),
                                requireNotNull(request.serverCredentialId)
                            )
                        } else {
                            actions.configure(
                                options.copy(
                                    engine = request.engine,
                                    localConsent = if (request.engine ==
                                        TranslationEngineKind.MY_MEMORY
                                    ) {
                                        options.localConsent
                                    } else {
                                        request.enable || options.localConsent
                                    },
                                    serverAllowed = if (request.engine ==
                                        TranslationEngineKind.MY_MEMORY
                                    ) {
                                        request.enable || options.serverAllowed
                                    } else {
                                        options.serverAllowed
                                    }
                                )
                            )
                        }
                    }
                }) {
                    Text(
                        stringResource(
                            if (remoteApproval) {
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
            TranslationTarget.entries.map { languageName(it.languageTag) },
            { targetPicker = false },
            selectedIndex = options.target.ordinal
        ) {
            targetPicker = false
            val selected = TranslationTarget.entries[it]
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
private fun SystemSupportStatus(
    availability: SystemEngineAvailability,
    target: TranslationTarget,
    modifier: Modifier = Modifier
) {
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
    Text(label, modifier, style = MaterialTheme.typography.bodySmall)
}

fun engineLabel(engine: TranslationEngineKind): Int = when (engine) {
    TranslationEngineKind.ML_KIT -> R.string.mobile_engine_mlkit
    TranslationEngineKind.SYSTEM -> R.string.mobile_engine_system
    TranslationEngineKind.MY_MEMORY -> R.string.mobile_engine_mymemory
    TranslationEngineKind.OWNED_SERVER -> R.string.mobile_owned_server
}
