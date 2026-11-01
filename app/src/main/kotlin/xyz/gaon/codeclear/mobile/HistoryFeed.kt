package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R

internal fun LazyListScope.historyFeed(
    state: MobileState,
    actions: MobileActions,
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
    if (state.loading || !state.loaded) {
        item { HistoryLoadingPlaceholder() }
    } else {
        if (state.records.isEmpty()) {
            item { Text(stringResource(R.string.mobile_no_results)) }
        }
        itemsIndexed(state.records, key = { _, alert ->
            alert.id
        }) { index, alert ->
            AlertHistoryRow(
                alert = alert,
                target = state.options.target.languageTag,
                engine = state.options.engine,
                localEnabled = state.options.translationAllowed,
                first = index == 0,
                last = index == state.records.lastIndex,
                open = {
                    openDetail(alert.id)
                }
            )
        }
        item {
            Row(Modifier.padding(vertical = 12.dp)) {
                TextButton(onClick = actions.newest) {
                    Text(stringResource(R.string.mobile_newest))
                }
                if (state.records.size ==
                    50
                ) {
                    TextButton(onClick = actions.older) {
                        Text(stringResource(R.string.mobile_older))
                    }
                }
            }
        }
    }
}
