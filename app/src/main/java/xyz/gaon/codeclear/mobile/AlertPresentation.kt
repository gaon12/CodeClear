package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.probe.LocalEngineKind
import xyz.gaon.codeclear.probe.LocalTarget

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
    engine: LocalEngineKind = alert.translation?.engine ?: LocalEngineKind.ML_KIT
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    painterResource(R.drawable.ic_result),
                    null,
                    Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Column {
                    Text(
                        alert.capture.title.ifBlank {
                            stringResource(R.string.mobile_alert)
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        stringResource(
                            R.string.mobile_captured,
                            dateTime(alert.capture.capturedAtMillis)
                        ),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            BoxWithConstraints {
                val original: @Composable () -> Unit = {
                    MessagePanel(stringResource(R.string.mobile_original), alert.capture.original)
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
                            translated = true
                        )
                        alert.translation?.takeIf {
                            it.phase == TranslationPhase.READY
                        }?.let { result ->
                            Text(
                                stringResource(
                                    if (result.engine == LocalEngineKind.SYSTEM) {
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
                        if (consent && alert.translation?.phase != TranslationPhase.READY &&
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
                                            if (engine == LocalEngineKind.SYSTEM) {
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
                            if (engine == LocalEngineKind.SYSTEM &&
                                alert.translation?.phase == TranslationPhase.NEEDS_MODELS
                            ) {
                                TextButton(onClick = retry) {
                                    Text(stringResource(R.string.mobile_retry))
                                }
                            }
                        }
                    }
                }
                if (maxWidth >= 600.dp) {
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
                stringResource(R.string.mobile_translation_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            details?.let {
                TextButton(onClick = it) { Text(stringResource(R.string.mobile_details)) }
            }
        }
    }
}

@Composable
private fun MessagePanel(label: String, body: String, translated: Boolean = false) {
    val modifier = if (translated) {
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer).padding(16.dp)
    } else {
        Modifier.fillMaxWidth()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (translated) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        SelectionContainer { Text(body, style = MaterialTheme.typography.bodyLarge) }
    }
}

internal fun translationStatusLabel(
    phase: TranslationPhase?,
    consent: Boolean,
    engine: LocalEngineKind
): Int = if (!consent) {
    R.string.mobile_translation_off
} else {
    when (phase) {
        null -> R.string.mobile_not_translated

        TranslationPhase.QUEUED -> R.string.mobile_queued

        TranslationPhase.WORKING -> R.string.mobile_working

        TranslationPhase.NEEDS_MODELS -> if (engine == LocalEngineKind.SYSTEM) {
            R.string.mobile_system_models_missing
        } else {
            R.string.mobile_models_missing
        }

        TranslationPhase.UNKNOWN_LANGUAGE -> R.string.mobile_unknown_language

        TranslationPhase.UNAVAILABLE -> R.string.mobile_system_unavailable

        TranslationPhase.UNSUPPORTED_PAIR -> R.string.mobile_pair_unsupported

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
                stringResource(R.string.mobile_source, alert.capture.sourcePackage),
                style = MaterialTheme.typography.bodyMedium
            )
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
