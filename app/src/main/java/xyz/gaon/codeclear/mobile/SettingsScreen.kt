package xyz.gaon.codeclear.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.probe.LocalEngineKind
import xyz.gaon.codeclear.probe.ProbeEnvironment
import xyz.gaon.codeclear.probe.ProbeSessionState

enum class SettingsPage(val title: Int) {
    CAPTURE(R.string.mobile_collection),
    TRANSLATION(R.string.mobile_translation_settings),
    APPEARANCE(R.string.mobile_appearance),
    NOTIFICATIONS(R.string.mobile_notifications),
    PRIVACY(R.string.mobile_privacy),
    ABOUT(R.string.mobile_about)
}

@Composable
fun SettingsScreen(
    state: MobileState,
    environment: ProbeEnvironment,
    session: ProbeSessionState,
    preparation: PreparationState,
    actions: MobileActions,
    selectedPage: SettingsPage?,
    selectPage: (SettingsPage?) -> Unit,
    tutorial: () -> Unit
) {
    val menuScroll = rememberLazyListState()
    val pageScroll = SettingsPage.entries.associateWith { rememberLazyListState() }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("settings-screen")) {
        val expanded = maxWidth >= 840.dp
        val page = selectedPage ?: SettingsPage.CAPTURE
        val menuWidth = (maxWidth * 0.36f).coerceIn(280.dp, 400.dp)
        BackHandler(enabled = !expanded && selectedPage != null) { selectPage(null) }
        if (expanded) {
            Row(Modifier.fillMaxSize().testTag("settings-split")) {
                SettingsMenu(
                    state,
                    actions,
                    menuScroll,
                    page,
                    true,
                    { selectPage(it) },
                    Modifier.fillMaxHeight().width(menuWidth)
                )
                SettingsDetail(
                    page, pageScroll.getValue(page), state, environment, session,
                    preparation, actions, tutorial, null, Modifier.weight(1f)
                )
            }
        } else if (selectedPage == null) {
            SettingsMenu(
                state,
                actions,
                menuScroll,
                null,
                false,
                { selectPage(it) },
                Modifier.fillMaxSize()
            )
        } else {
            SettingsDetail(
                page, pageScroll.getValue(page), state, environment, session,
                preparation, actions, tutorial, { selectPage(null) }, Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun SettingsMenu(
    state: MobileState,
    actions: MobileActions,
    scroll: LazyListState,
    selected: SettingsPage?,
    expanded: Boolean,
    select: (SettingsPage) -> Unit,
    modifier: Modifier
) {
    LazyColumn(
        modifier.testTag("settings-menu").selectableGroup(),
        state = scroll,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Box(
                Modifier.fillMaxWidth().heightIn(min = if (expanded) 72.dp else 160.dp),
                contentAlignment = if (expanded) Alignment.CenterStart else Alignment.Center
            ) {
                Text(
                    stringResource(R.string.mobile_settings),
                    Modifier.padding(horizontal = 16.dp),
                    style = if (expanded) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.headlineLarge
                    }
                )
            }
        }
        if (!expanded) item { SettingsStatus(state, actions) }
        items(SettingsPage.entries, key = { it.name }) { page ->
            val active = selected == page
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val focused by interaction.collectIsFocusedAsState()
            val hovered by interaction.collectIsHoveredAsState()
            Column(
                Modifier.fillMaxWidth().testTag("settings-category-${page.name}")
                    .background(
                        if (active || pressed || focused || hovered) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            Color.Transparent
                        },
                        RoundedCornerShape(20.dp)
                    ).selectable(
                        active,
                        role = Role.Tab,
                        interactionSource = interaction,
                        indication = null,
                        onClick = { select(page) }
                    )
                    .heightIn(min = 80.dp).padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    stringResource(page.title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    settingsSummary(page, state.options),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsDetail(
    page: SettingsPage,
    scroll: LazyListState,
    state: MobileState,
    environment: ProbeEnvironment,
    session: ProbeSessionState,
    preparation: PreparationState,
    actions: MobileActions,
    tutorial: () -> Unit,
    back: (() -> Unit)?,
    modifier: Modifier
) {
    Column(modifier.fillMaxHeight().testTag("settings-detail-pane")) {
        Row(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                .heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (back != null) {
                IconButton(onClick = back, modifier = Modifier.testTag("settings-back")) {
                    Icon(
                        painterResource(R.drawable.ic_back),
                        stringResource(R.string.mobile_back),
                        Modifier.size(24.dp)
                    )
                }
            }
            Text(
                stringResource(page.title),
                Modifier.padding(horizontal = 12.dp).testTag("settings-detail-title"),
                style = MaterialTheme.typography.titleLarge
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("settings-detail"),
                state = scroll,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { SettingsStatus(state, actions) }
                item(key = page) {
                    MobileSettings(page, state, environment, session, actions, tutorial)
                }
                if (page == SettingsPage.TRANSLATION &&
                    preparation.phase != PreparationPhase.IDLE &&
                    state.options.engine == LocalEngineKind.ML_KIT
                ) {
                    item { Text(stringResource(preparationMessage(preparation.phase))) }
                }
            }
        }
    }
}

@Composable
private fun SettingsStatus(state: MobileState, actions: MobileActions) {
    if (!state.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.storageError) {
        SettingSection(stringResource(R.string.mobile_storage_error)) {
            Text(stringResource(R.string.mobile_storage_notice))
            Button(onClick = actions.retryStorage) { Text(stringResource(R.string.mobile_retry)) }
        }
    }
}

@Composable
private fun settingsSummary(page: SettingsPage, options: MobileOptions): String = when (page) {
    SettingsPage.CAPTURE -> stringResource(R.string.mobile_notification_access) + " · " +
        stringResource(R.string.mobile_accessibility_access)

    SettingsPage.TRANSLATION -> stringResource(engineLabel(options.engine)) + " · " +
        languageName(options.target.languageTag)

    SettingsPage.APPEARANCE -> stringResource(themeLabel(options.theme))

    SettingsPage.NOTIFICATIONS -> stringResource(R.string.mobile_result_notifications)

    SettingsPage.PRIVACY -> stringResource(R.string.mobile_data_storage)

    SettingsPage.ABOUT -> "CodeClear"
}
