package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.TranslationEngineKind

@Composable
fun AlertPresentation(
    alert: SavedAlert,
    target: String,
    consent: Boolean,
    retry: () -> Unit,
    prepare: () -> Unit,
    details: (() -> Unit)? = null,
    engine: TranslationEngineKind = alert.translation?.engine ?: TranslationEngineKind.ML_KIT,
    viewMode: MessageViewMode = MessageViewMode.BOTH,
    compact: Boolean = false,
    messageActions: MobileActions? = null
) {
    val symbol = alertSymbol(alert.capture)
    // Script conversion can change the text even when the language codes match.
    val translatedReady = alert.translation?.phase == TranslationPhase.READY &&
        !alert.translation.text.isNullOrBlank() &&
        (!alert.translation.sameLanguage || alert.translation.text != alert.capture.original)
    var showOriginal by rememberSaveable(alert.id, target, viewMode) {
        mutableStateOf(!translatedReady)
    }
    LaunchedEffect(translatedReady) {
        if (translatedReady) showOriginal = false
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.padding(if (compact) 8.dp else 22.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 18.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!compact) {
                    AlertSymbolIcon(symbol, Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        alert.capture.title.ifBlank {
                            stringResource(R.string.mobile_alert)
                        },
                        style = if (compact) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        maxLines = if (compact) 1 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (compact) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                dateTime(alert.capture.capturedAtMillis),
                                style = MaterialTheme.typography.labelMedium
                            )
                            AlertTypeLabel(symbol)
                            alert.capture.context.country?.let {
                                Text(
                                    it.flag + " " +
                                        it.displayName(
                                            LocalConfiguration.current.locales[0].toLanguageTag()
                                        ),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    } else {
                        Text(
                            stringResource(
                                if (alert.capture.source == CaptureSource.PEER) {
                                    R.string.mobile_nearby_saved_at
                                } else {
                                    R.string.mobile_captured
                                },
                                dateTime(alert.capture.capturedAtMillis)
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                        AlertTypeLabel(symbol, expanded = true)
                        alert.capture.context.country?.let { country ->
                            Text(
                                country.flag + " " + country.displayName(
                                    LocalConfiguration.current.locales[0].toLanguageTag()
                                ),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
                if (compact && details != null) {
                    TextButton(
                        onClick = details,
                        modifier = Modifier.testTag("alert-modal-details")
                    ) {
                        Text(stringResource(R.string.mobile_details))
                    }
                }
            }
            if (alert.capture.source == CaptureSource.PEER) {
                Text(
                    stringResource(R.string.mobile_nearby_unverified),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BoxWithConstraints {
                val switchAction: @Composable () -> Unit = {
                    if (viewMode == MessageViewMode.SWITCH && translatedReady) {
                        TextButton(onClick = {
                            showOriginal = !showOriginal
                        }, modifier = Modifier.testTag("switch-message")) {
                            Text(
                                stringResource(
                                    if (showOriginal) {
                                        R.string.mobile_view_translation
                                    } else {
                                        R.string.mobile_view_original
                                    }
                                )
                            )
                        }
                    }
                }
                val original: @Composable () -> Unit = {
                    MessagePanel(
                        stringResource(R.string.mobile_original),
                        alert.capture.original,
                        bodyActions = messageActions,
                        headerActions = {
                            switchAction()
                            messageActions?.let {
                                SpeechAction(
                                    "${alert.id}:$target:original",
                                    alert.capture.original,
                                    alert.translation?.sourceLanguage,
                                    "speech-original",
                                    it
                                )
                            }
                        }
                    )
                }
                val translated: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MessagePanel(
                            stringResource(R.string.mobile_translation, languageName(target)),
                            alert.translation?.text
                                ?: stringResource(
                                    translationStatusLabel(
                                        alert.translation?.phase,
                                        consent,
                                        engine
                                    )
                                ),
                            translated = !compact,
                            bodyActions = messageActions.takeIf {
                                alert.translation?.phase == TranslationPhase.READY &&
                                    !alert.translation.text.isNullOrBlank()
                            },
                            headerActions = {
                                switchAction()
                                if (translatedReady) {
                                    messageActions?.let {
                                        SpeechAction(
                                            "${alert.id}:$target:translation",
                                            alert.translation.text,
                                            target,
                                            "speech-translation",
                                            it
                                        )
                                    }
                                }
                            }
                        )
                        alert.translation?.takeIf {
                            it.phase == TranslationPhase.READY && !compact &&
                                viewMode == MessageViewMode.BOTH
                        }?.let { result ->
                            Text(
                                stringResource(
                                    if (result.standard != null) {
                                        R.string.mobile_result_standard
                                    } else if (result.engine ==
                                        TranslationEngineKind.OWNED_SERVER
                                    ) {
                                        R.string.mobile_owned_server
                                    } else if (result.engine == TranslationEngineKind.MY_MEMORY) {
                                        R.string.mobile_result_mymemory
                                    } else if (result.engine == TranslationEngineKind.SYSTEM) {
                                        R.string.mobile_result_system
                                    } else {
                                        R.string.mobile_result_mlkit
                                    }
                                ),
                                style = MaterialTheme.typography.labelMedium
                            )
                            if (result.sameLanguage) {
                                Text(
                                    stringResource(R.string.mobile_result_same_language),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (!compact && consent &&
                            alert.translation?.phase != TranslationPhase.READY &&
                            alert.translation?.phase != TranslationPhase.WORKING
                        ) {
                            OutlinedButton(
                                onClick = if (alert.translation?.phase ==
                                    TranslationPhase.NEEDS_MODELS
                                ) {
                                    prepare
                                } else {
                                    retry
                                }
                            ) {
                                Text(
                                    stringResource(
                                        if (alert.translation?.phase ==
                                            TranslationPhase.NEEDS_MODELS
                                        ) {
                                            if (engine == TranslationEngineKind.SYSTEM) {
                                                R.string.mobile_system_models_action
                                            } else {
                                                R.string.mobile_prepare
                                            }
                                        } else {
                                            R.string.mobile_retry
                                        }
                                    )
                                )
                            }
                            if (engine == TranslationEngineKind.SYSTEM &&
                                alert.translation?.phase == TranslationPhase.NEEDS_MODELS
                            ) {
                                TextButton(onClick = retry) {
                                    Text(stringResource(R.string.mobile_retry))
                                }
                            }
                        }
                    }
                }
                if (viewMode == MessageViewMode.SWITCH) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (showOriginal || !translatedReady) original() else translated()
                        if (!translatedReady && alert.translation?.sameLanguage != true) {
                            Text(
                                stringResource(
                                    translationStatusLabel(
                                        alert.translation?.phase,
                                        consent,
                                        engine
                                    )
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!compact && consent && alert.translation?.phase !in setOf(
                                    TranslationPhase.WORKING,
                                    TranslationPhase.QUEUED
                                )
                            ) {
                                TextButton(
                                    onClick = if (alert.translation?.phase ==
                                        TranslationPhase.NEEDS_MODELS
                                    ) {
                                        prepare
                                    } else {
                                        retry
                                    }
                                ) {
                                    Text(
                                        stringResource(
                                            if (alert.translation?.phase ==
                                                TranslationPhase.NEEDS_MODELS
                                            ) {
                                                R.string.mobile_prepare
                                            } else {
                                                R.string.mobile_retry
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }
                } else if (maxWidth >= 600.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f)) { original() }
                        Column(Modifier.weight(1f)) { translated() }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        original()
                        HorizontalDivider()
                        translated()
                    }
                }
            }
            Text(
                stringResource(
                    if (engine == TranslationEngineKind.MY_MEMORY ||
                        engine == TranslationEngineKind.OWNED_SERVER
                    ) {
                        R.string.mobile_server_translation_notice
                    } else if (viewMode == MessageViewMode.SWITCH || compact) {
                        R.string.mobile_translation_short_notice
                    } else {
                        R.string.mobile_translation_notice
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            details?.takeIf { !compact }?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.mobile_details))
                }
            }
        }
    }
}
