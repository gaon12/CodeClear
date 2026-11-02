package xyz.gaon.codeclear.mobile

import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import xyz.gaon.codeclear.R

@Stable
internal class HistoryRowActionState {
    var openId by mutableStateOf<Long?>(null)
}

private enum class HistoryAnchor { CLOSED, OPEN }

@Composable
internal fun HistoryActionRow(
    alert: SavedAlert,
    options: MobileOptions,
    first: Boolean,
    last: Boolean,
    actions: MobileActions,
    coordinator: HistoryRowActionState,
    open: () -> Unit
) {
    val strings = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var menu by remember(alert.id) { mutableStateOf(false) }
    val currentCoordinator by rememberUpdatedState(coordinator)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = (80.dp * density.fontScale.coerceIn(1f, 1.3f)).coerceAtMost(maxWidth / 2)
        val reveal = with(density) { (cellWidth * 2).toPx() }
        // Layout changes close transient actions rather than restoring an obsolete pixel offset.
        val drag = remember(alert.id, reveal) {
            AnchoredDraggableState(
                HistoryAnchor.CLOSED,
                DraggableAnchors {
                    HistoryAnchor.CLOSED at 0f
                    HistoryAnchor.OPEN at -reveal
                }
            )
        }
        val revealed by remember(drag) {
            derivedStateOf { drag.currentValue == HistoryAnchor.OPEN }
        }
        LaunchedEffect(drag) {
            snapshotFlow { drag.currentValue }.collect { value ->
                if (value == HistoryAnchor.OPEN) {
                    currentCoordinator.openId = alert.id
                } else if (currentCoordinator.openId == alert.id) {
                    currentCoordinator.openId = null
                }
            }
        }
        LaunchedEffect(coordinator.openId, drag) {
            if (coordinator.openId != alert.id && drag.currentValue != HistoryAnchor.CLOSED) {
                drag.animateTo(HistoryAnchor.CLOSED, tween(180))
            }
        }
        DisposableEffect(coordinator, alert.id) {
            onDispose { if (coordinator.openId == alert.id) coordinator.openId = null }
        }
        val close: (() -> Unit) -> Unit = { action ->
            coordinator.openId = null
            menu = false
            scope.launch {
                drag.snapTo(HistoryAnchor.CLOSED)
                action()
            }
        }
        val delete = { close { actions.delete(alert.id) } }
        val share = {
            close {
                actions.shareBody(
                    alertShareText(alert, options.target.languageTag, options.engine, strings)
                )
            }
        }
        val shape = RoundedCornerShape(
            topStart = if (first) 24.dp else 0.dp,
            topEnd = if (first) 24.dp else 0.dp,
            bottomStart = if (last) 24.dp else 0.dp,
            bottomEnd = if (last) 24.dp else 0.dp
        )
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().clip(shape)) {
            androidx.compose.foundation.layout.Box(Modifier.matchParentSize()) {
                Row(
                    Modifier.align(
                        AbsoluteAlignment.CenterRight
                    ).fillMaxHeight().width(cellWidth * 2)
                        .then(if (revealed) Modifier else Modifier.clearAndSetSemantics {})
                ) {
                    HistorySwipeAction(
                        R.string.mobile_share,
                        R.drawable.ic_share,
                        cellWidth,
                        revealed,
                        false,
                        "history-share-${alert.id}",
                        share
                    )
                    HistorySwipeAction(
                        R.string.mobile_delete,
                        R.drawable.ic_delete,
                        cellWidth,
                        revealed,
                        true,
                        "history-delete-${alert.id}",
                        delete
                    )
                }
            }
            AlertHistoryRow(
                alert, options.target.languageTag, options.engine,
                options.translationAllowed, first, last,
                open = {
                    if (drag.offset < -0.5f) close {} else open()
                },
                modifier = Modifier.absoluteOffset {
                    IntOffset(drag.requireOffset().roundToInt(), 0)
                }
                    .anchoredDraggable(
                        drag,
                        reverseDirection = false,
                        orientation = Orientation.Horizontal
                    ),
                longPress = { close { menu = true } },
                delete = delete,
                share = share
            )
        }
        if (menu) {
            HistoryActionMenu(alert, { menu = false }, delete, share)
        }
    }
}

@Composable
private fun HistorySwipeAction(
    label: Int,
    icon: Int,
    width: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    destructive: Boolean,
    tag: String,
    action: () -> Unit
) {
    Surface(
        color = if (destructive) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (destructive) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        modifier = Modifier.width(width).fillMaxHeight().testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClick = action)
    ) {
        Column(
            Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(
                8.dp,
                Alignment.CenterVertically
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(painterResource(icon), null, Modifier.size(24.dp))
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun HistoryActionMenu(
    alert: SavedAlert,
    dismiss: () -> Unit,
    delete: () -> Unit,
    share: () -> Unit
) {
    MobileConfirmationDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.mobile_history_actions)) },
        text = {
            Column(Modifier.fillMaxWidth().testTag("history-action-menu")) {
                Text(
                    alert.capture.title.ifBlank { stringResource(R.string.mobile_alert) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2
                )
                TextButton(
                    onClick = delete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(20.dp))
                    Text(stringResource(R.string.mobile_delete), Modifier.padding(start = 12.dp))
                }
                TextButton(onClick = share, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_share), null, Modifier.size(20.dp))
                    Text(stringResource(R.string.mobile_share), Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.mobile_close)) }
        }
    )
}
