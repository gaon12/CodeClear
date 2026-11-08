package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R

@Composable
fun SettingsGroup(
    title: String? = null,
    description: String? = null,
    footer: String? = null,
    tag: String? = null,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) SettingsSectionHeading(title)
        if (description != null) SettingsDescription(description)
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().then(
                if (tag == null) Modifier else Modifier.testTag(tag)
            ),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(vertical = 2.dp)) { content() }
        }
        if (footer != null) SettingsDescription(footer)
    }
}

@Composable
private fun SettingsSectionHeading(title: String) {
    Text(
        title,
        Modifier.padding(horizontal = 24.dp, vertical = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun SettingsDescription(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeading(title)
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                content()
            }
        }
    }
}

@Composable
fun ChoiceDialog(
    title: String,
    choices: List<String>,
    dismiss: () -> Unit,
    selectedIndex: Int,
    choose: (Int) -> Unit
) {
    MobileConfirmationDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
            choices.forEachIndexed { index, label ->
                RadioChoiceRow(label, selected = index == selectedIndex, onClick = {
                    choose(index)
                })
            }
        }
    }, confirmButton = {
        TextButton(onClick = dismiss) { Text(stringResource(R.string.mobile_cancel)) }
    })
}

fun themeLabel(choice: ThemeChoice): Int = when (choice) {
    ThemeChoice.SYSTEM -> R.string.mobile_system
    ThemeChoice.LIGHT -> R.string.mobile_light
    ThemeChoice.DARK -> R.string.mobile_dark
}
