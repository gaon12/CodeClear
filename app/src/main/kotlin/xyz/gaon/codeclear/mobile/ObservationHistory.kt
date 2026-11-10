package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import xyz.gaon.codeclear.R

@Composable
internal fun ObservationHistory(alert: SavedAlert, state: MobileState, actions: MobileActions) {
    if (alert.observationCount <= 1) return
    var expanded by rememberSaveable(alert.id) { mutableStateOf(false) }
    val expansion =
        stringResource(if (expanded) R.string.mobile_expanded else R.string.mobile_collapsed)
    val expandAction =
        stringResource(if (expanded) R.string.mobile_collapse else R.string.mobile_expand)
    Column {
        SettingsGroup {
            Row(
                Modifier.fillMaxWidth().testTag("observation-history-toggle")
                    .clickable(role = Role.Button, onClickLabel = expandAction) {
                        expanded =
                            !expanded
                    }
                    .semantics { stateDescription = expansion }
                    .heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.mobile_observations),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        stringResource(R.string.mobile_observation_total, alert.observationCount),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                ObservationDisclosure(expanded)
            }
            if (expanded) {
                val entries = observationLabels(alert.observations)
                Column(
                    Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                        .testTag("observation-history")
                ) {
                    Text(
                        stringResource(R.string.mobile_observation_notice),
                        Modifier.padding(bottom = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    entries.forEachIndexed { index, entry ->
                        val samePreviousDay = entries.getOrNull(index - 1)?.date == entry.date
                        val sameNextDay = entries.getOrNull(index + 1)?.date == entry.date
                        if (!samePreviousDay) {
                            val topSpacing = if (index == 0) 0.dp else 16.dp
                            Text(
                                entry.date,
                                Modifier.padding(top = topSpacing, bottom = 4.dp)
                                    .semantics {
                                        heading()
                                    }.testTag("observation-date-${entry.source.id}"),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        key(alert.id, entry.source.id) {
                            ObservationEntry(entry, samePreviousDay, sameNextDay)
                        }
                    }
                }
            }
        }
        if (expanded && (state.observationBeforeId != Long.MAX_VALUE || alert.moreObservations)) {
            PageNavigation(
                previousLabel = stringResource(R.string.mobile_newest),
                nextLabel = stringResource(R.string.mobile_older),
                previous = actions.newestObservations.takeIf {
                    state.observationBeforeId != Long.MAX_VALUE
                },
                next = actions.olderObservations.takeIf { alert.moreObservations },
                tag = "observations-navigation"
            )
        }
    }
}

@Composable
private fun ObservationEntry(
    entry: ObservationLabels,
    connectedBefore: Boolean,
    connectedAfter: Boolean
) {
    val observation = entry.source
    val hasDetails = entry.received != null || entry.posted != null
    var expanded by rememberSaveable(observation.id) { mutableStateOf(false) }
    val expansion =
        stringResource(if (expanded) R.string.mobile_expanded else R.string.mobile_collapsed)
    val expandAction =
        stringResource(if (expanded) R.string.mobile_collapse else R.string.mobile_expand)
    val rail = MaterialTheme.colorScheme.outlineVariant
    val dot = MaterialTheme.colorScheme.onSurfaceVariant
    val background = MaterialTheme.colorScheme.surface
    val lineHeight = MaterialTheme.typography.labelLarge.lineHeight
    Column(
        Modifier.fillMaxWidth().testTag("observation-${observation.id}").drawBehind {
            val center = Offset(7.dp.toPx(), 12.dp.toPx() + lineHeight.toPx() / 2)
            drawLine(
                rail,
                Offset(center.x, if (connectedBefore) 0f else center.y),
                Offset(center.x, if (connectedAfter) size.height else center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawCircle(background, radius = 6.dp.toPx(), center = center)
            drawCircle(dot, radius = 3.dp.toPx(), center = center)
        }
    ) {
        Column(
            Modifier.fillMaxWidth().then(
                if (hasDetails) {
                    Modifier.clickable(role = Role.Button, onClickLabel = expandAction) {
                        expanded = !expanded
                    }.semantics { stateDescription = expansion }
                } else {
                    Modifier
                }
            ).testTag("observation-toggle-${observation.id}")
                .heightIn(min = 64.dp).padding(start = 26.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(observationSourceLabel(observation.source)),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge
                )
                if (hasDetails) ObservationDisclosure(expanded)
            }
            // The time uses the full content width rather than sharing the disclosure's narrow slot.
            Text(
                stringResource(R.string.mobile_captured, entry.captured),
                Modifier.fillMaxWidth().testTag("observation-time-${observation.id}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded && hasDetails) {
            Column(
                Modifier.padding(start = 26.dp, bottom = 16.dp)
                    .testTag("observation-times-${observation.id}"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                entry.received?.let {
                    Text(
                        stringResource(R.string.mobile_received, it),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                entry.posted?.let {
                    Text(
                        stringResource(R.string.mobile_posted, it),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ObservationDisclosure(expanded: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        val first = size.height * if (expanded) 0.6f else 0.4f
        val middle = size.height * if (expanded) 0.4f else 0.6f
        drawLine(
            color,
            Offset(size.width * 0.25f, first),
            Offset(size.width * 0.5f, middle),
            2.dp.toPx(),
            StrokeCap.Round
        )
        drawLine(
            color,
            Offset(size.width * 0.5f, middle),
            Offset(size.width * 0.75f, first),
            2.dp.toPx(),
            StrokeCap.Round
        )
    }
}

private data class ObservationLabels(
    val source: HistoryObservation,
    val date: String,
    val captured: String,
    val received: String?,
    val posted: String?
)

@Composable
private fun observationLabels(observations: List<HistoryObservation>): List<ObservationLabels> {
    val locale = LocalConfiguration.current.locales[0]
    val zone = TimeZone.getDefault()
    return remember(observations, locale, zone.id) {
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = zone }
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM, locale).apply { timeZone = zone }
        val full = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, locale)
            .apply { timeZone = zone }
        observations.map {
            ObservationLabels(
                it,
                date.format(Date(it.capturedAtMillis)),
                time.format(Date(it.capturedAtMillis)),
                it.receivedAtMillis?.let { value -> full.format(Date(value)) },
                it.postedAtMillis?.let { value -> full.format(Date(value)) }
            )
        }
    }
}

private fun observationSourceLabel(source: CaptureSource): Int = when (source) {
    CaptureSource.NOTIFICATION -> R.string.mobile_observation_notification
    CaptureSource.DIALOG -> R.string.mobile_observation_dialog
    CaptureSource.ORIGINAL_NOTIFICATION -> R.string.mobile_observation_original
    CaptureSource.PEER -> R.string.mobile_nearby_unverified
}
