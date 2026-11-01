package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeBodyEvidence
import xyz.gaon.codeclear.capture.ProbeEnvironment
import xyz.gaon.codeclear.capture.ProbeRadio
import xyz.gaon.codeclear.capture.ProbeSessionState

internal fun LazyListScope.homeFeed(
    state: MobileState,
    environment: ProbeEnvironment,
    session: ProbeSessionState,
    sourceAvailable: Boolean,
    actions: MobileActions,
    openSettings: (SettingsPage) -> Unit,
    openHistory: () -> Unit,
    openNearby: () -> Unit,
    openTutorial: () -> Unit
) {
    item {
        HomeOverview(state, environment, session, {
            openSettings(SettingsPage.CAPTURE)
        }, {
            openSettings(SettingsPage.TRANSLATION)
        })
    }
    item {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.mobile_last_saved),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = openHistory) {
                Text(stringResource(R.string.mobile_home_view_history))
            }
        }
    }
    val latest = state.latest
    if (latest == null) {
        item { HomeEmptyState() }
    } else {
        item {
            AlertPresentation(
                latest,
                state.options.target.languageTag,
                state.options.translationAllowed,
                {
                    actions.retry(
                        latest.id,
                        state.options.target.languageTag,
                        state.options.engine
                    )
                },
                {
                    actions.prepare(
                        latest,
                        state.options.target.languageTag,
                        state.options.engine
                    )
                },
                { actions.detail(latest.id) },
                engine = state.options.engine,
                viewMode = state.options.messageViewMode,
                speechActions = actions
            )
        }
    }
    item {
        SettingSection(
            stringResource(
                if (environment.radio == ProbeRadio.ABSENT) {
                    R.string.mobile_reception_information
                } else {
                    R.string.mobile_capture_status
                }
            )
        ) {
            Text(
                stringResource(
                    when {
                        environment.radio == ProbeRadio.ABSENT ->
                            R.string.mobile_wifi_only

                        environment.radio != ProbeRadio.PRESENT ->
                            R.string.mobile_radio_unknown

                        environment.systemPackages.isEmpty() ->
                            R.string.mobile_source_unknown

                        (
                            !state.options.notificationConsent ||
                                !environment.notificationGranted ||
                                !session.notificationConnected
                            ) &&
                            (
                                !state.options.accessibilityConsent ||
                                    !environment.accessibilityGranted ||
                                    !session.accessibilityConnected
                                ) -> R.string.mobile_setup_needed

                        else -> R.string.mobile_ready
                    }
                )
            )
        }
    }
    item {
        SettingsGroup {
            TextButton(
                onClick = {
                    openSettings(SettingsPage.CAPTURE)
                },
                modifier = Modifier.fillMaxWidth().heightIn(
                    min = 56.dp
                )
            ) {
                Text(stringResource(R.string.mobile_manage_access))
            }
            SettingDivider()
            TextButton(
                onClick = {
                    openTutorial()
                },
                modifier = Modifier.fillMaxWidth().heightIn(
                    min = 56.dp
                )
            ) {
                Text(stringResource(R.string.mobile_tutorial))
            }
        }
    }
    if (state.options.nearbyEnabled) {
        item {
            SettingsGroup {
                SettingRow(
                    stringResource(R.string.mobile_nearby_inbox),
                    pluralStringResource(
                        R.plurals.mobile_nearby_count,
                        actions.nearby.originals.size,
                        actions.nearby.originals.size
                    ),
                    onClick = {
                        openNearby()
                    }
                )
            }
        }
    }
    val observed = session.records.lastOrNull()?.observation
    if (observed != null &&
        (
            observed.bodyEvidence == ProbeBodyEvidence.UNVERIFIED ||
                observed.limited ||
                observed.groupSummary ||
                observed.platformBoundaryPossible
            )
    ) {
        item {
            Text(
                stringResource(R.string.mobile_incomplete),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
    observed?.reportedDialogQueueSize?.takeIf {
        it > 1
    }?.let { count ->
        item { Text(stringResource(R.string.mobile_pending, count)) }
    }
    if (sourceAvailable) {
        item {
            Button(onClick = actions.openSource) {
                Text(stringResource(R.string.mobile_open_source))
            }
        }
    }
}
