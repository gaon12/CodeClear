package xyz.gaon.codeclear.mobile

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R

internal fun LazyListScope.historyFeed(
    state: MobileState,
    actions: MobileActions,
    rowActions: HistoryRowActionState,
    openNearby: () -> Unit,
    openDetail: (Long) -> Unit
) {
    if (state.options.nearbyEnabled ||
        actions.nearby.originals.isNotEmpty()
    ) {
        item {
            SettingsGroup {
                SettingRow(
                    stringResource(R.string.mobile_nearby_inbox),
                    pluralStringResource(
                        R.plurals.mobile_nearby_count,
                        actions.nearby.originals.size,
                        actions.nearby.originals.size
                    ),
                    onClick = {
                        openNearby()
                    }
                )
            }
        }
    }
    // A first install has nothing to search, so the field appears once history exists.
    val noHistory = state.loaded && !state.loading && state.query.isBlank() &&
        state.records.isEmpty() && state.beforeId == Long.MAX_VALUE
    if (!noHistory) {
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.search,
                label = {
                    Text(stringResource(R.string.mobile_search))
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_search), null)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor =
                        MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor =
                        MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )
        }
    }
    if (state.loading || !state.loaded) {
        item { HistoryLoadingPlaceholder() }
    } else {
        if (noHistory) {
            item {
                HistoryEmptyState(
                    icon = R.drawable.ic_history,
                    title = stringResource(R.string.mobile_history_empty_title),
                    body = stringResource(R.string.mobile_history_empty_body),
                    tag = "history-empty"
                )
            }
        } else if (state.records.isEmpty()) {
            item {
                HistoryEmptyState(
                    icon = R.drawable.ic_search,
                    title = stringResource(R.string.mobile_no_results),
                    body = if (state.query.isBlank()) {
                        null
                    } else {
                        stringResource(R.string.mobile_no_results_body, state.query)
                    },
                    tag = "history-no-results"
                )
            }
        }
        itemsIndexed(state.records, key = { _, alert ->
            alert.id
        }) { index, alert ->
            HistoryActionRow(
                alert = alert,
                options = state.options,
                first = index == 0,
                last = index == state.records.lastIndex,
                actions = actions,
                coordinator = rowActions,
                open = {
                    openDetail(alert.id)
                }
            )
        }
        // Pages hold up to 50 records; reselecting the History tab also returns to the newest page.
        val olderPage = state.beforeId != Long.MAX_VALUE
        val morePossible = state.records.size == 50
        if (olderPage || morePossible) {
            item {
                Row(Modifier.padding(vertical = 12.dp)) {
                    if (olderPage) {
                        TextButton(onClick = actions.newest) {
                            Text(stringResource(R.string.mobile_newest))
                        }
                    }
                    if (morePossible) {
                        TextButton(onClick = actions.older) {
                            Text(stringResource(R.string.mobile_older))
                        }
                    }
                }
            }
        }
    }
}

/** Centers an icon, title, and optional explanation in the remaining list height. */
@Composable
private fun LazyItemScope.HistoryEmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String?,
    tag: String
) {
    Column(
        Modifier.fillParentMaxHeight(0.7f).fillMaxWidth().padding(horizontal = 24.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp)
        )
        body?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
