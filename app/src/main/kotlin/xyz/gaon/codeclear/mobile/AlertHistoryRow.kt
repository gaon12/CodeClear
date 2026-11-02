package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.TranslationEngineKind

@Composable
fun AlertHistoryRow(
    alert: SavedAlert,
    target: String,
    engine: TranslationEngineKind,
    localEnabled: Boolean,
    first: Boolean,
    last: Boolean,
    open: () -> Unit,
    modifier: Modifier = Modifier,
    longPress: (() -> Unit)? = null,
    delete: (() -> Unit)? = null,
    share: (() -> Unit)? = null
) {
    val symbol = alertSymbol(alert.capture)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val translated = alert.translation?.takeIf {
        it.phase == TranslationPhase.READY && it.engine == engine && !it.text.isNullOrBlank()
    }
    val source = translated?.text ?: alert.capture.original
    val preview = remember(source) { historyPreview(source) }
    val title = alert.capture.title.ifBlank { stringResource(R.string.mobile_alert) }
    val largeText = LocalDensity.current.fontScale > 1.3f
    val deleteLabel = stringResource(R.string.mobile_delete)
    val shareLabel = stringResource(R.string.mobile_share)
    val actionsLabel = stringResource(R.string.mobile_history_actions)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = if (first) 24.dp else 0.dp,
            topEnd = if (first) 24.dp else 0.dp,
            bottomStart = if (last) 24.dp else 0.dp,
            bottomEnd = if (last) 24.dp else 0.dp
        ),
        color = if (pressed || focused || hovered) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().testTag("history-row-${alert.id}")
                    .semantics {
                        customActions = buildList {
                            delete?.let { action ->
                                add(
                                    CustomAccessibilityAction(deleteLabel) {
                                        action()
                                        true
                                    }
                                )
                            }
                            share?.let { action ->
                                add(
                                    CustomAccessibilityAction(shareLabel) {
                                        action()
                                        true
                                    }
                                )
                            }
                        }
                    }
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = open,
                        onLongClickLabel = actionsLabel,
                        onLongClick = longPress
                    )
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        AlertSymbolIcon(symbol, Modifier.size(24.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            title,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!largeText) HistoryCaptureTime(alert.capture.capturedAtMillis)
                    }
                    if (largeText) HistoryCaptureTime(alert.capture.capturedAtMillis)
                    AlertTypeLabel(symbol)
                    Text(
                        preview,
                        Modifier.testTag("history-preview-${alert.id}"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (alert.capture.source == CaptureSource.PEER) {
                        Text(
                            stringResource(R.string.mobile_nearby_unverified),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (alert.observationCount > 1) {
                        Text(
                            stringResource(
                                R.string.mobile_observation_count,
                                alert.observationCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        if (translated != null) {
                            stringResource(R.string.mobile_translation, languageName(target))
                        } else {
                            stringResource(
                                translationStatusLabel(
                                    alert.translation?.phase,
                                    localEnabled,
                                    engine
                                )
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!last) {
                HorizontalDivider(
                    Modifier.padding(start = 80.dp, end = 20.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@Composable
private fun HistoryCaptureTime(time: Long) {
    val locale = LocalConfiguration.current.locales[0]
    val zone = TimeZone.getDefault().id
    val date = remember(time, locale, zone) {
        DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(time))
    }
    val fullTime = stringResource(R.string.mobile_captured, dateTime(time))
    Text(
        stringResource(R.string.mobile_captured, date),
        Modifier.widthIn(max = 112.dp).semantics { contentDescription = fullTime },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

// Keep list text layout bounded without splitting a Unicode code point; details retain the full text.
internal fun historyPreview(text: String): String {
    val end = text.offsetByCodePoints(0, minOf(text.codePointCount(0, text.length), 256))
    return text.substring(0, end).replace('\n', ' ').replace('\r', ' ') +
        if (end < text.length) "…" else ""
}
