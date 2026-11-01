package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R

@Composable
internal fun ModelDownloadDialog(request: ModelDownloadRequest, actions: MobileActions) {
    val inventory = actions.modelInventory
    val preparation = actions.modelPreparation
    val locale = LocalConfiguration.current.locales[0]
    val languages = if (request.started && preparation.phase != PreparationPhase.IDENTIFYING) {
        preparation.languages
    } else {
        request.languages
    }
    val completed = if (request.started) {
        preparation.completedLanguages
    } else if (request.checkUpdates) {
        emptySet()
    } else {
        inventory.downloaded.intersect(languages)
    }
    val remaining = (languages - completed).size
    val estimatedPacks = if (!request.started && !request.sourceKnown) {
        remaining + 1
    } else {
        remaining
    }
    val checking = inventory.checking || !inventory.loaded
    val busy = request.started && preparation.phase in setOf(
        PreparationPhase.IDENTIFYING,
        PreparationPhase.WAITING_FOR_WIFI
    )
    val status = when {
        !request.started -> if (checking) {
            R.string.mobile_engine_checking
        } else {
            R.string.mobile_model_review
        }

        preparation.phase == PreparationPhase.IDENTIFYING -> R.string.mobile_identifying_original

        busy -> R.string.mobile_model_preparing

        preparation.phase == PreparationPhase.READY && checking -> R.string.mobile_model_verifying

        preparation.phase == PreparationPhase.READY && !inventory.failed &&
            inventory.downloaded.containsAll(languages) -> R.string.mobile_models_ready

        preparation.phase == PreparationPhase.IDLE -> R.string.mobile_model_stopped

        preparation.phase == PreparationPhase.UNKNOWN_LANGUAGE -> R.string.mobile_unknown_language

        else -> R.string.mobile_failed
    }
    MobileConfirmationDialog(
        onDismissRequest = actions.closeModelDownload,
        title = { Text(stringResource(R.string.mobile_prepare)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).testTag("model-download-dialog"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(status), Modifier.testTag("model-download-status"))
                if (busy || checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!checking && inventory.failed) {
                    Text(stringResource(R.string.mobile_models_query_failed))
                    TextButton(onClick = { actions.refreshModels(true) }) {
                        Text(stringResource(R.string.mobile_models_refresh))
                    }
                }
                for (language in languages) {
                    val label = when {
                        language in completed -> R.string.mobile_model_pack_ready

                        !request.started && request.checkUpdates &&
                            language in inventory.downloaded -> R.string.mobile_model_update_pending

                        request.started && language ==
                            preparation.activeLanguage -> R.string.mobile_model_preparing

                        else -> R.string.mobile_model_waiting
                    }
                    Text(modelLanguageName(language, locale) + " · " + stringResource(label))
                }
                if (request.started && preparation.phase != PreparationPhase.IDENTIFYING) {
                    Text(
                        stringResource(
                            R.string.mobile_model_progress_count,
                            completed.size,
                            languages.size
                        ),
                        Modifier.testTag("model-download-count")
                    )
                }
                if (!request.started || busy) {
                    Text(stringResource(R.string.mobile_model_size_estimate, estimatedPacks * 30))
                    if (!request.sourceKnown &&
                        (!request.started || preparation.phase == PreparationPhase.IDENTIFYING)
                    ) {
                        Text(stringResource(R.string.mobile_model_source_plan))
                    }
                }
                Text(
                    stringResource(
                        if (request.started) {
                            R.string.mobile_model_progress_notice
                        } else {
                            R.string.mobile_model_download_notice
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = if (request.started) {
                    actions.closeModelDownload
                } else {
                    actions.startModelDownload
                },
                modifier = Modifier.testTag(
                    if (request.started) {
                        "model-download-close"
                    } else {
                        "model-download-confirm"
                    }
                ),
                enabled =
                    request.started ||
                        (
                            !checking && !inventory.failed && preparation.phase !in setOf(
                                PreparationPhase.IDENTIFYING,
                                PreparationPhase.WAITING_FOR_WIFI
                            )
                            )
            ) {
                Text(
                    stringResource(
                        if (request.started) {
                            R.string.mobile_close
                        } else {
                            R.string.mobile_models_download
                        }
                    )
                )
            }
        },
        dismissButton = if (request.started) {
            null
        } else {
            {
                TextButton(onClick = actions.closeModelDownload) {
                    Text(stringResource(R.string.mobile_cancel))
                }
            }
        }
    )
}
