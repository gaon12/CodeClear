package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Navigation belongs to the surrounding page; a missing action never moves its peer. */
@Composable
internal fun PageNavigation(
    previousLabel: String,
    nextLabel: String,
    previous: (() -> Unit)?,
    next: (() -> Unit)?,
    tag: String,
    primaryNext: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (previous != null) {
                TextButton(
                    onClick = previous,
                    modifier = Modifier.widthIn(
                        min = 88.dp
                    ).heightIn(min = 48.dp).testTag("$tag-previous")
                ) {
                    Text(previousLabel, textAlign = TextAlign.Center)
                }
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (next != null) {
                val modifier = Modifier.widthIn(
                    min = 96.dp
                ).heightIn(min = 48.dp).testTag("$tag-next")
                if (primaryNext) {
                    Button(onClick = next, modifier = modifier) {
                        Text(nextLabel, textAlign = TextAlign.Center)
                    }
                } else {
                    TextButton(onClick = next, modifier = modifier) {
                        Text(nextLabel, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
