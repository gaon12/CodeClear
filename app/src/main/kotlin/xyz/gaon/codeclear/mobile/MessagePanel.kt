package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R

@Composable
internal fun MessagePanel(
    label: String,
    body: String,
    translated: Boolean = false,
    bodyActions: MobileActions? = null,
    headerActions: @Composable () -> Unit = {}
) {
    val modifier = if (translated) {
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer).padding(16.dp)
    } else {
        Modifier.fillMaxWidth()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                label,
                modifier = Modifier.align(Alignment.CenterVertically),
                style = MaterialTheme.typography.labelLarge,
                color = if (translated) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            FlowRow(
                Modifier.align(Alignment.CenterVertically),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                headerActions()
                bodyActions?.let { MessageActions(label, body, it) }
            }
        }
        SelectionContainer { Text(body, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun MessageActions(label: String, body: String, actions: MobileActions) {
    var expanded by remember(label, body) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painterResource(R.drawable.ic_more),
                stringResource(R.string.mobile_message_actions, label)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mobile_copy_body)) },
                onClick = {
                    expanded = false
                    actions.copyBody(body)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mobile_share_body)) },
                onClick = {
                    expanded = false
                    actions.shareBody(body)
                }
            )
        }
    }
}
