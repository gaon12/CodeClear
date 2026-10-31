package xyz.gaon.codeclear.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R

@Composable
internal fun NearbyInboxPage(actions: MobileActions, back: () -> Unit, saved: (Long) -> Unit) {
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(alive) { onDispose { alive.set(false) } }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember(selected) { mutableStateOf(false) }
    var failed by remember(selected) { mutableStateOf(false) }
    val goBack = { if (selected != null) selected = null else back() }
    BackHandler(onBack = goBack)
    Column(Modifier.fillMaxSize().testTag("nearby-inbox")) {
        MobilePageHeader(
            stringResource(R.string.mobile_nearby_inbox),
            back = goBack,
            backTag = "nearby-back"
        )
        LazyColumn(
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsGroup {
                    SettingNote(stringResource(R.string.mobile_nearby_unverified))
                    SettingNote(
                        pluralStringResource(
                            R.plurals.mobile_nearby_count,
                            actions.nearby.originals.size,
                            actions.nearby.originals.size
                        )
                    )
                }
            }
            val original = actions.nearby.originals.firstOrNull { it.root == selected }
            val retainedId = actions.nearby.retainedIds[selected]
            if (selected != null && original != null) {
                item {
                    SettingsGroup {
                        Column(
                            Modifier.padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                original.title.ifBlank { stringResource(R.string.mobile_alert) },
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                stringResource(R.string.mobile_original),
                                style = MaterialTheme.typography.labelLarge
                            )
                            SelectionContainer { Text(original.body) }
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            if (retainedId != null) {
                                saved(retainedId)
                                return@Button
                            }
                            saving = true
                            failed = false
                            actions.saveNearby(original.root) { id ->
                                if (!alive.get()) return@saveNearby
                                saving = false
                                if (id == null) failed = true else saved(id)
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(
                            min = 56.dp
                        ).testTag("save-nearby")
                    ) {
                        Text(
                            stringResource(
                                if (retainedId == null) {
                                    R.string.mobile_nearby_save
                                } else {
                                    R.string.mobile_home_view_history
                                }
                            )
                        )
                    }
                    if (failed) {
                        Text(
                            stringResource(R.string.mobile_nearby_save_failed),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                item {
                    OutlinedButton(onClick = {
                        actions.dismissNearby(original.root)
                        selected = null
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.mobile_nearby_dismiss))
                    }
                }
            } else if (selected != null || actions.nearby.originals.isEmpty()) {
                item { Text(stringResource(R.string.mobile_nearby_empty)) }
            } else {
                items(actions.nearby.originals, key = { it.root }) { entry ->
                    SettingsGroup {
                        SettingRow(
                            entry.title.ifBlank { stringResource(R.string.mobile_alert) },
                            historyPreview(entry.body),
                            onClick = { selected = entry.root }
                        )
                    }
                }
            }
        }
    }
}
