package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.probe.LocalTarget
import xyz.gaon.codeclear.translation.TranslationEngineKind

fun languageName(tag: String): String = when (tag) {
    "ko" -> "한국어"
    "zh-Hans" -> "简体中文"
    "zh-Hant" -> "繁體中文"
    "ja" -> "日本語"
    "fr" -> "Français"
    "de" -> "Deutsch"
    else -> "English"
}

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
    speechActions: MobileActions? = null
) {
    val translatedReady = alert.translation?.phase == TranslationPhase.READY &&
        !alert.translation.text.isNullOrBlank() && !alert.translation.sameLanguage
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
                    Icon(
                        painterResource(R.drawable.ic_result),
                        null,
                        Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
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
                            nationalAlertType(alert.capture)?.let {
                                Text(
                                    stringResource(it.label),
                                    Modifier.testTag("national-alert-type"),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
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
                        nationalAlertType(alert.capture)?.let { category ->
                            Text(
                                stringResource(
                                    R.string.mobile_alert_type,
                                    stringResource(category.label)
                                ),
                                Modifier.testTag("national-alert-type"),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
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
                        headerActions = {
                            switchAction()
                            speechActions?.let {
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
                            headerActions = {
                                switchAction()
                                if (translatedReady) {
                                    speechActions?.let {
                                        SpeechAction(
                                            "${alert.id}:$target:translation",
                                            alert.translation!!.text!!,
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
                                    if (result.engine == TranslationEngineKind.MY_MEMORY) {
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
                    if (engine == TranslationEngineKind.MY_MEMORY) {
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

@Composable
private fun MessagePanel(
    label: String,
    body: String,
    translated: Boolean = false,
    headerActions: @Composable () -> Unit = {}
) {
    val modifier = if (translated) {
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer).padding(16.dp)
    } else {
        Modifier.fillMaxWidth()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = if (translated) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            headerActions()
        }
        SelectionContainer { Text(body, style = MaterialTheme.typography.bodyLarge) }
    }
}

internal fun translationStatusLabel(
    phase: TranslationPhase?,
    consent: Boolean,
    engine: TranslationEngineKind
): Int = if (!consent) {
    R.string.mobile_translation_off
} else {
    when (phase) {
        null -> R.string.mobile_not_translated

        TranslationPhase.QUEUED -> R.string.mobile_queued

        TranslationPhase.WORKING -> R.string.mobile_working

        TranslationPhase.NEEDS_MODELS -> if (engine == TranslationEngineKind.SYSTEM) {
            R.string.mobile_system_models_missing
        } else {
            R.string.mobile_models_missing
        }

        TranslationPhase.UNKNOWN_LANGUAGE -> R.string.mobile_unknown_language

        TranslationPhase.UNAVAILABLE -> if (engine == TranslationEngineKind.MY_MEMORY) {
            R.string.mobile_remote_unavailable
        } else {
            R.string.mobile_system_unavailable
        }

        TranslationPhase.UNSUPPORTED_PAIR -> R.string.mobile_pair_unsupported

        TranslationPhase.QUOTA -> R.string.mobile_remote_quota

        TranslationPhase.TOO_LONG -> R.string.mobile_remote_too_long

        TranslationPhase.FAILED -> R.string.mobile_failed

        TranslationPhase.READY -> R.string.mobile_failed
    }
}

@Composable
fun dateTime(time: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val zone = TimeZone.getDefault().id
    return remember(time, locale, zone) {
        DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            locale
        ).format(Date(time))
    }
}

@Composable
fun AlertMetadata(alert: SavedAlert) {
    SettingsGroup {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(20.dp).testTag("alert-metadata")
        ) {
            Text(
                stringResource(R.string.mobile_capture_information),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                if (alert.capture.source == CaptureSource.PEER) {
                    stringResource(R.string.mobile_nearby_unverified)
                } else {
                    stringResource(R.string.mobile_source, alert.capture.sourcePackage)
                },
                style = MaterialTheme.typography.bodyMedium
            )
            alert.capture.context.country?.let { country ->
                val locale = LocalConfiguration.current.locales[0].toLanguageTag()
                Text(
                    stringResource(
                        if (country.evidence ==
                            xyz.gaon.codeclear.reception.CountryEvidence.CBS_PLMN
                        ) {
                            R.string.mobile_country_cbs
                        } else if (country.evidence ==
                            xyz.gaon.codeclear.reception.CountryEvidence.PEER
                        ) {
                            R.string.mobile_country_peer
                        } else {
                            R.string.mobile_country_network
                        },
                        country.flag + " " + country.displayName(locale)
                    ),
                    modifier = Modifier.testTag("reception-country")
                )
            }
            alert.capture.receivedAtMillis?.let {
                Text(stringResource(R.string.mobile_received, dateTime(it)))
            }
            alert.capture.postedAtMillis?.let {
                Text(stringResource(R.string.mobile_posted, dateTime(it)))
            }
            Text(
                stringResource(R.string.mobile_times_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
