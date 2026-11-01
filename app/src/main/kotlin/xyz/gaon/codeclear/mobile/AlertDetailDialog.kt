package xyz.gaon.codeclear.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.gaon.codeclear.R

@Composable
internal fun AlertDetailDialog(
    state: MobileState,
    actions: MobileActions,
    openDetails: () -> Unit
) {
    var frame by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    val dismiss by rememberUpdatedState({ actions.detail(null) })
    BackHandler { actions.detail(null) }
    Dialog(onDismissRequest = {
        actions.detail(null)
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().testTag("alert-modal-backdrop")
                .onGloballyPositioned { frame = it }
                .pointerInput(Unit) {
                    detectTapGestures { position ->
                        val coordinates = frame?.takeIf { it.isAttached }
                        if (coordinates != null && cardBounds != Rect.Zero &&
                            !cardBounds.contains(coordinates.localToRoot(position))
                        ) {
                            dismiss()
                        }
                    }
                }.safeDrawingPadding().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
                    .heightIn(
                        max = maxHeight * if (maxHeight <
                            400.dp
                        ) {
                            0.92f
                        } else {
                            0.78f
                        }
                    ).onGloballyPositioned { cardBounds = it.boundsInRoot() }
                    .testTag("alert-modal-card")
            ) {
                Column(Modifier.padding(12.dp)) {
                    Column(
                        Modifier.weight(
                            1f,
                            fill = false
                        ).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        AlertDetailBody(state, actions, compact = true, openDetails = {
                            openDetails()
                        })
                    }
                    TextButton(
                        onClick = {
                            actions.detail(null)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .testTag("alert-modal-close")
                    ) {
                        Text(stringResource(R.string.mobile_close))
                    }
                }
            }
        }
    }
}
