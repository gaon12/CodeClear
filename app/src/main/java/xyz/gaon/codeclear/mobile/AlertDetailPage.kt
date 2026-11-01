package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.probe.LocalEngineKind

@Composable
internal fun AlertDetailPage(
    state: MobileState,
    preparation: PreparationState,
    actions: MobileActions,
    scroll: ScrollState,
    back: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().testTag("history-detail")) {
        MobilePageHeader(
            stringResource(R.string.mobile_details),
            back = back,
            titleTag = "history-detail-title",
            backTag = "history-detail-back"
        )
        Column(
            Modifier.weight(1f).verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 12.dp).testTag("history-detail-body"),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            AlertDetailBody(state, actions)
            if (preparation.phase != PreparationPhase.IDLE &&
                state.options.engine == LocalEngineKind.ML_KIT
            ) {
                Text(stringResource(preparationMessage(preparation.phase)))
            }
        }
    }
}

@Composable
internal fun AlertDetailBody(
    state: MobileState,
    actions: MobileActions,
    compact: Boolean = false,
    openDetails: (() -> Unit)? = null
) {
    val alert = state.detail
    if (alert == null) {
        Text(
            stringResource(
                if (state.loading) R.string.mobile_working else R.string.mobile_deleted
            )
        )
    } else {
        val target = state.detailTarget ?: state.options.target.languageTag
        val engine = state.detailEngine ?: state.options.engine
        val active = state.options.localConsent && engine == state.options.engine
        if (!compact) DetailTargetPicker(alert, state, actions)
        AlertPresentation(
            alert,
            target,
            active,
            {
                actions.retry(alert.id, target, engine)
            },
            { actions.prepare(alert, target, engine) },
            details = openDetails,
            engine = engine,
            viewMode = state.options.messageViewMode,
            compact = compact,
            speechActions = actions
        )
        if (!compact) AlertMetadata(alert)
        if (!compact) {
            OutlinedButton(
                onClick = { actions.delete(alert.id) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("delete-alert"),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.mobile_delete))
            }
        }
    }
}
