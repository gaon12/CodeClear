package xyz.gaon.codeclear.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.BuildConfig
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeEnvironment
import xyz.gaon.codeclear.capture.ProbeSessionState
import xyz.gaon.codeclear.translation.TranslationEngineKind

enum class SettingsPage(val title: Int) {
    CAPTURE(R.string.mobile_collection),
    TRANSLATION(R.string.mobile_translation_settings),
    APPEARANCE(R.string.mobile_appearance),
    NOTIFICATIONS(R.string.mobile_notifications),
    LABS(R.string.mobile_labs),
    SHARING(R.string.mobile_nearby),
    SERVER(R.string.mobile_server_translation),
    OWNED_SERVER(R.string.mobile_owned_server),
    PRIVACY(R.string.mobile_privacy),
    ABOUT(R.string.mobile_about),
    COMPATIBILITY(R.string.mobile_compatibility)
}

internal val SettingsPage.parent: SettingsPage?
    get() = when (this) {
        SettingsPage.SHARING, SettingsPage.SERVER, SettingsPage.OWNED_SERVER -> SettingsPage.LABS
        SettingsPage.COMPATIBILITY -> SettingsPage.ABOUT
        else -> null
    }

internal data class SettingsMenuGroup(val title: Int?, val pages: List<SettingsPage>)

internal fun settingsMenuGroups(options: MobileOptions): List<SettingsMenuGroup> = buildList {
    add(
        SettingsMenuGroup(
            R.string.mobile_settings_alerts_group,
            listOf(SettingsPage.CAPTURE, SettingsPage.TRANSLATION, SettingsPage.NOTIFICATIONS)
        )
    )
    add(SettingsMenuGroup(R.string.mobile_settings_general_group, listOf(SettingsPage.APPEARANCE)))
    add(
        SettingsMenuGroup(
            R.string.mobile_settings_information_group,
            listOf(SettingsPage.PRIVACY, SettingsPage.ABOUT)
        )
    )
    if (options.labsVisible) add(SettingsMenuGroup(null, listOf(SettingsPage.LABS)))
}

internal fun settingsMenuPages(options: MobileOptions = MobileOptions()): List<SettingsPage> =
    settingsMenuGroups(options).flatMap { it.pages }

@Composable
fun SettingsScreen(
    state: MobileState,
    environment: ProbeEnvironment,
    session: ProbeSessionState,
    actions: MobileActions,
    selectedPage: SettingsPage?,
    selectPage: (SettingsPage?) -> Unit,
    tutorial: () -> Unit
) {
    val menuScroll = rememberLazyListState()
    val pageScroll = SettingsPage.entries.associateWith { rememberLazyListState() }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("settings-screen")) {
        val expanded = maxWidth >= 840.dp
        val selection = selectedPage?.takeUnless {
            (
                (!state.options.labsVisible && it == SettingsPage.LABS) ||
                    (
                        (!state.options.labsVisible || !state.options.labsEnabled) &&
                            it.parent == SettingsPage.LABS
                        )
                ) ||
                (it == SettingsPage.OWNED_SERVER && !actions.serverAccess.configured)
        }
        val page = selection ?: SettingsPage.CAPTURE
        val menuWidth = (maxWidth * 0.42f).coerceIn(320.dp, 640.dp)
        BackHandler(enabled = selection != null && (!expanded || page.parent != null)) {
            selectPage(page.parent)
        }
        if (expanded) {
            Row(Modifier.fillMaxSize().testTag("settings-split")) {
                SettingsMenu(
                    state,
                    actions,
                    menuScroll,
                    page.parent ?: page,
                    true,
                    { selectPage(it) },
                    Modifier.fillMaxHeight().width(menuWidth)
                )
                SettingsDetail(
                    page, pageScroll.getValue(page), state, environment, session,
                    actions, tutorial, selectPage,
                    page.parent?.let { { selectPage(it) } }, Modifier.weight(1f)
                )
            }
        } else if (selection == null) {
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
                actions, tutorial, selectPage,
                { selectPage(page.parent) }, Modifier.fillMaxSize()
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
    val headingState = rememberPageHeading(scroll, expandable = !expanded)
    Column(modifier.nestedScroll(headingState)) {
        CollapsingPageHeading(
            stringResource(R.string.mobile_settings),
            headingState,
            titleTag = "settings-menu-title"
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("settings-menu").selectableGroup(),
            state = scroll,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!expanded && (!state.loaded || state.storageError)) {
                item { SettingsStatus(state, actions) }
            }
            items(settingsMenuGroups(state.options), key = { it.pages.first().name }) { group ->
                group.title?.let { title ->
                    Text(
                        stringResource(title),
                        Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp).semantics {
                            heading()
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column {
                        group.pages.forEachIndexed { index, page ->
                            if (index > 0) {
                                HorizontalDivider(
                                    Modifier.padding(start = 76.dp, end = 20.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                            SettingsCategory(page, state.options, selected == page, expanded) {
                                select(page)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategory(
    page: SettingsPage,
    options: MobileOptions,
    active: Boolean,
    expanded: Boolean,
    select: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().testTag("settings-category-${page.name}")
            .background(
                if (active || pressed || focused ||
                    hovered
                ) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    Color.Transparent
                },
                RoundedCornerShape(20.dp)
            ).then(
                if (expanded) {
                    Modifier.selectable(
                        active,
                        role = Role.Tab,
                        interactionSource = interaction,
                        indication = null,
                        onClick = select
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = select
                    )
                }
            ).heightIn(min = 84.dp).padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsCategoryIcon(page)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                stringResource(page.title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                settingsSummary(page, options),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
    actions: MobileActions,
    tutorial: () -> Unit,
    navigate: (SettingsPage) -> Unit,
    back: (() -> Unit)?,
    modifier: Modifier
) {
    Column(modifier.fillMaxHeight().testTag("settings-detail-pane")) {
        MobilePageHeader(
            stringResource(page.title),
            Modifier.widthIn(max = 760.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            back = back,
            titleTag = "settings-detail-title",
            backTag = "settings-back"
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("settings-detail"),
                state = scroll,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { SettingsStatus(state, actions) }
                item(key = page) {
                    MobileSettings(page, state, environment, session, actions, tutorial, navigate)
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

    SettingsPage.SHARING -> stringResource(
        if (options.nearbyEnabled) R.string.mobile_on else R.string.mobile_off
    )

    SettingsPage.LABS -> stringResource(R.string.mobile_labs_summary)

    SettingsPage.SERVER -> "MyMemory"

    SettingsPage.OWNED_SERVER -> stringResource(R.string.mobile_owned_server)

    SettingsPage.PRIVACY -> stringResource(R.string.mobile_data_storage)

    SettingsPage.ABOUT -> BuildConfig.VERSION_NAME

    SettingsPage.COMPATIBILITY -> stringResource(R.string.mobile_compatibility)
}
