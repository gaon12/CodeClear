package xyz.gaon.codeclear.mobile

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import xyz.gaon.codeclear.R
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
    details: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    painterResource(R.drawable.ic_result),
                    null,
                    Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
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
                                ?: stringResource(phaseLabel(alert.translation?.phase, consent))
                        )
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
private fun MessagePanel(label: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        SelectionContainer { Text(body, style = MaterialTheme.typography.bodyLarge) }
    }
}

private fun phaseLabel(phase: TranslationPhase?, consent: Boolean): Int = if (!consent) {
    R.string.mobile_translation_off
} else {
    when (phase) {
        TranslationPhase.QUEUED, null -> R.string.mobile_queued
        TranslationPhase.WORKING -> R.string.mobile_working
        TranslationPhase.NEEDS_MODELS -> R.string.mobile_models_missing
        TranslationPhase.UNKNOWN_LANGUAGE -> R.string.mobile_unknown_language
        TranslationPhase.FAILED -> R.string.mobile_failed
        TranslationPhase.READY -> R.string.mobile_failed
    }
}

fun dateTime(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

@Composable
fun AlertMetadata(alert: SavedAlert) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(horizontal = 6.dp)
    ) {
        Text(
            stringResource(R.string.mobile_times_notice),
            style = MaterialTheme.typography.bodySmall
        )
        alert.capture.receivedAtMillis?.let {
            Text(stringResource(R.string.mobile_received, dateTime(it)))
        }
        alert.capture.postedAtMillis?.let {
            Text(stringResource(R.string.mobile_posted, dateTime(it)))
        }
        Text(stringResource(R.string.mobile_source, alert.capture.sourcePackage))
        Text(stringResource(R.string.mobile_official_unknown))
        val category = when (alert.capture.headingHint) {
            "NATIONAL" -> R.string.mobile_national
            "EXTREME" -> R.string.mobile_extreme
            "SEVERE" -> R.string.mobile_severe
            "PUBLIC_SAFETY" -> R.string.mobile_public_safety
            "AMBER" -> R.string.mobile_amber
            "TEST" -> R.string.mobile_test
            "OPERATOR_DEFINED" -> R.string.mobile_operator_defined
            "EARTHQUAKE" -> R.string.mobile_earthquake
            "TSUNAMI" -> R.string.mobile_tsunami
            "EARTHQUAKE_AND_TSUNAMI" -> R.string.mobile_earthquake_tsunami
            "OTHER_EMERGENCY" -> R.string.mobile_other_emergency
            "OTHER" -> R.string.mobile_other_alert
            else -> R.string.mobile_unknown
        }
        Text(stringResource(R.string.mobile_estimate, stringResource(category)))
        alert.capture.broadcast?.let {
            Text(
                stringResource(
                    R.string.mobile_numeric_metadata,
                    it.category,
                    it.priority,
                    it.cmasClass?.toString() ?: "—",
                    it.etwsType?.toString() ?: "—"
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
