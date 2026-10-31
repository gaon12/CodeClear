package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R

@Composable
fun MobileNavigation(selected: Int, select: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.widthIn(max = 880.dp).fillMaxWidth().padding(horizontal = 12.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf(R.string.mobile_home, R.string.mobile_history, R.string.mobile_settings)
                    .forEachIndexed { index, label ->
                        val interaction = remember { MutableInteractionSource() }
                        val focused by interaction.collectIsFocusedAsState()
                        val pressed by interaction.collectIsPressedAsState()
                        val hovered by interaction.collectIsHoveredAsState()
                        Box(
                            Modifier.weight(1f).heightIn(min = 72.dp)
                                .testTag("destination-$index")
                                .background(
                                    if (focused || pressed || hovered) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        Color.Transparent
                                    },
                                    RoundedCornerShape(24.dp)
                                )
                                .selectable(
                                    selected == index,
                                    interactionSource = interaction,
                                    indication = null,
                                    role = Role.Tab
                                ) { select(index) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val color = if (selected == index) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    painterResource(
                                        listOf(
                                            R.drawable.ic_home,
                                            R.drawable.ic_history,
                                            R.drawable.ic_settings
                                        )[index]
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = color
                                )
                                Text(
                                    stringResource(label),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected ==
                                        index
                                    ) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Medium
                                    },
                                    textAlign = TextAlign.Center,
                                    color = color
                                )
                            }
                        }
                    }
            }
        }
    }
}
