package xyz.gaon.codeclear.mobile

import android.view.Gravity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Keep decision actions reachable while long explanations scroll within the available height. */
@Composable
fun MobileConfirmationDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val previousGravity = window?.attributes?.gravity
            window?.setGravity(Gravity.BOTTOM)
            onDispose { previousGravity?.let { window?.setGravity(it) } }
        }
        BoxWithConstraints(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(16.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.heightIn(max = maxHeight).padding(24.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                    Box(Modifier.weight(1f, fill = false).padding(top = 16.dp, bottom = 12.dp)) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                    }
                    Row(
                        Modifier.fillMaxWidth().testTag("confirmation-actions"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (dismissButton != null) {
                            Box(
                                Modifier.weight(1f).heightIn(min = 56.dp),
                                contentAlignment = Alignment.Center,
                                propagateMinConstraints = true
                            ) {
                                dismissButton()
                            }
                            VerticalDivider(
                                Modifier.height(20.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                        Box(
                            Modifier.weight(1f).heightIn(min = 56.dp),
                            contentAlignment = Alignment.Center,
                            propagateMinConstraints = true
                        ) {
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}
