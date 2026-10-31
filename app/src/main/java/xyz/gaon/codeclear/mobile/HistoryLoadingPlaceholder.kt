package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R

@Composable
internal fun HistoryLoadingPlaceholder() {
    val label = stringResource(R.string.mobile_working)
    val shade = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("history-loading").semantics {
            contentDescription = label
            liveRegion = LiveRegionMode.Polite
        },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column {
            repeat(5) { index ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(Modifier.size(44.dp).background(shade, CircleShape))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(
                                Modifier.weight(1f).height(16.dp)
                                    .background(shade, RoundedCornerShape(8.dp))
                            )
                            Box(
                                Modifier.width(52.dp).height(12.dp)
                                    .background(shade, RoundedCornerShape(8.dp))
                            )
                        }
                        Box(
                            Modifier.fillMaxWidth().height(12.dp)
                                .background(shade, RoundedCornerShape(8.dp))
                        )
                        Box(
                            Modifier.fillMaxWidth(if (index % 2 == 0) 0.7f else 0.85f).height(12.dp)
                                .background(shade, RoundedCornerShape(8.dp))
                        )
                        Box(
                            Modifier.fillMaxWidth(0.4f).height(10.dp)
                                .background(shade, RoundedCornerShape(8.dp))
                        )
                    }
                }
                if (index < 4) {
                    HorizontalDivider(
                        Modifier.padding(start = 80.dp, end = 20.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}
