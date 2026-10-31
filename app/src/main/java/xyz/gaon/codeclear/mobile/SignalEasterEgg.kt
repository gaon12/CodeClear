package xyz.gaon.codeclear.mobile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.gaon.codeclear.R

@Composable
internal fun SignalEasterEgg(close: () -> Unit) {
    var signals by rememberSaveable { mutableIntStateOf(0) }
    val turn by animateFloatAsState((signals % 24) * 15f, label = "signal-turn")
    val color = MaterialTheme.colorScheme.primary
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize().testTag("signal-easter-egg")) {
            Box(Modifier.safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val middle = Offset(size.width / 2, size.height / 2)
                            for (ring in 1..3) {
                                val radius = size.minDimension * (0.18f + ring * 0.09f)
                                drawArc(
                                    color.copy(alpha = 1f - ring * 0.18f),
                                    turn + ring * 50f,
                                    240f,
                                    false,
                                    Offset(middle.x - radius, middle.y - radius),
                                    androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                                    style = Stroke(4.dp.toPx())
                                )
                            }
                        }
                        Button(
                            onClick = { signals = (signals + 1) % 240 },
                            modifier = Modifier.testTag("signal-egg-pulse")
                        ) {
                            Text("CC")
                        }
                    }
                    Text("CodeClear", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(R.string.mobile_labs_unlocked),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    TextButton(
                        onClick = close,
                        modifier = Modifier.fillMaxWidth()
                            .testTag("signal-egg-close")
                    ) {
                        Text(stringResource(R.string.mobile_done))
                    }
                }
            }
        }
    }
}
