package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
internal fun AlertDetailBody(state: MobileState, actions: MobileActions) {
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
        AlertPresentation(alert, target, active, {
            actions.retry(alert.id, target, engine)
        }, { actions.prepare(alert, target, engine) }, engine = engine)
        AlertMetadata(alert)
        TextButton(onClick = { actions.delete(alert.id) }) {
            Text(stringResource(R.string.mobile_delete))
        }
    }
}
