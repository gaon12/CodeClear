package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R

@Composable
internal fun ObservationHistory(alert: SavedAlert, state: MobileState, actions: MobileActions) {
    if (alert.observationCount <= 1) return
    var expanded by rememberSaveable(alert.id) { mutableStateOf(false) }
    SettingsGroup {
        SettingRow(
            stringResource(R.string.mobile_observations),
            stringResource(R.string.mobile_observation_count, alert.observationCount),
            onClick = { expanded = !expanded }
        )
        if (expanded) {
            Column(
                Modifier.padding(horizontal = 20.dp).testTag("observation-history"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.mobile_observation_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                alert.observations.forEach { observation ->
                    Column(
                        Modifier.testTag("observation-${observation.id}"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            stringResource(observationSourceLabel(observation.source)),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            stringResource(
                                R.string.mobile_captured,
                                dateTime(observation.capturedAtMillis)
                            )
                        )
                        observation.receivedAtMillis?.let {
                            Text(stringResource(R.string.mobile_received, dateTime(it)))
                        }
                        observation.postedAtMillis?.let {
                            Text(stringResource(R.string.mobile_posted, dateTime(it)))
                        }
                    }
                }
                FlowRow {
                    if (state.observationBeforeId != Long.MAX_VALUE) {
                        TextButton(onClick = actions.newestObservations) {
                            Text(stringResource(R.string.mobile_newest))
                        }
                    }
                    if (alert.moreObservations) {
                        TextButton(onClick = actions.olderObservations) {
                            Text(stringResource(R.string.mobile_older))
                        }
                    }
                }
            }
        }
    }
}

private fun observationSourceLabel(source: CaptureSource): Int = when (source) {
    CaptureSource.NOTIFICATION -> R.string.mobile_observation_notification
    CaptureSource.DIALOG -> R.string.mobile_observation_dialog
    CaptureSource.ORIGINAL_NOTIFICATION -> R.string.mobile_observation_original
    CaptureSource.PEER -> R.string.mobile_nearby_unverified
}
