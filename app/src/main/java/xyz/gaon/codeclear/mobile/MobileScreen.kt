package xyz.gaon.codeclear.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.nearby.NearbyState
import xyz.gaon.codeclear.probe.LocalEngineKind
import xyz.gaon.codeclear.probe.ProbeBodyEvidence
import xyz.gaon.codeclear.probe.ProbeEnvironment
import xyz.gaon.codeclear.probe.ProbeRadio
import xyz.gaon.codeclear.probe.ProbeSessionState
import xyz.gaon.codeclear.probe.SystemEngineAvailability

class MobileActions(
    val configure: (MobileOptions) -> Unit,
    val search: (String) -> Unit,
    val detail: (Long?) -> Unit,
    val delete: (Long?) -> Unit,
    val retry: (Long, String, LocalEngineKind) -> Unit,
    val prepare: (SavedAlert, String, LocalEngineKind) -> Unit,
    val settings: (String) -> Unit,
    val openSource: () -> Unit,
    val notificationPermission: () -> Unit,
    val notificationsAllowed: Boolean,
    val older: () -> Unit,
    val newest: () -> Unit,
    val retryStorage: () -> Unit,
    val systemAvailability: SystemEngineAvailability = SystemEngineAvailability(),
    val refreshSystem: (Boolean) -> Unit = {},
    val systemSettings: () -> Unit = {},
    val changeDetailTarget: (Long, String, String) -> Unit = { _, _, _ -> },
    val identifyOriginal: (String, (String?) -> Unit) -> AutoCloseable = { _, complete ->
        complete(null)
        AutoCloseable {}
    },
    val nearby: NearbyState = NearbyState(),
    val nearbyPermission: () -> Unit = {},
    val refreshNearby: () -> Unit = {},
    val dismissNearby: (String) -> Unit = {},
    val saveNearby: (String, (Long?) -> Unit) -> Unit = { _, complete -> complete(null) },
    val speechState: SpeechState = SpeechState(),
    val readAloud: (String, String, String) -> Unit = { _, _, _ -> },
    val stopReading: () -> Unit = {},
    val modelInventory: ModelInventoryState = ModelInventoryState(),
    val modelPreparation: PreparationState = PreparationState(),
    val refreshModels: (Boolean) -> Unit = {},
    val prepareModel: (String) -> Unit = {}
)

@Composable
fun MobileScreen(
    state: MobileState,
    environment: ProbeEnvironment,
    session: ProbeSessionState,
    sourceAvailable: Boolean,
    preparation: PreparationState,
    actions: MobileActions
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var tutorial by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var nearbyPage by rememberSaveable { mutableStateOf(false) }
    var historyDetailId by rememberSaveable { mutableStateOf<Long?>(null) }
    val isHistoryDetail = state.detailId != null && state.detailId == historyDetailId
    val homeScroll = rememberLazyListState()
    val historyScroll = rememberLazyListState()
    val historyDetailScroll = key(state.detailId) { rememberScrollState() }
    val settingsState = rememberSaveableStateHolder()
    var settingsEntry by rememberSaveable { mutableIntStateOf(0) }
    var scrollResetTarget by remember { mutableIntStateOf(-1) }
    var scrollResetSequence by remember { mutableIntStateOf(0) }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val tutorialScroll = rememberLazyListState()
    // A lazy item provider can update before its parent replaces the attached scroll state.
    val currentTab = tab
    val showTutorial = tutorial
    val currentStep = step
    LaunchedEffect(showTutorial, currentStep) {
        if (showTutorial) tutorialScroll.scrollToItem(0)
    }
    LaunchedEffect(state.detailId) {
        if (state.detailId != historyDetailId) historyDetailId = null
    }
    LaunchedEffect(state.query, state.beforeId) { historyScroll.scrollToItem(0) }
    val feedScroll = if (showTutorial) {
        tutorialScroll
    } else if (currentTab ==
        1
    ) {
        historyScroll
    } else {
        homeScroll
    }
    BackHandler(enabled = showTutorial) {
        if (step > 0) step-- else tutorial = false
    }
    LaunchedEffect(scrollResetSequence) {
        when (scrollResetTarget) {
            0 -> homeScroll.scrollToItem(0)
            1 -> historyScroll.scrollToItem(0)
        }
    }
    MobileTheme(state.options.theme) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
            MobileNavigation(currentTab) { destination ->
                tutorial = false
                if (destination == tab) {
                    step = 0
                    if (destination == 1) nearbyPage = false
                    if (destination == 1 && isHistoryDetail) {
                        historyDetailId = null
                        actions.detail(null)
                    }
                    if (destination == 2) {
                        settingsState.removeState(settingsEntry)
                        settingsEntry++
                        settingsPage = null
                    } else {
                        scrollResetTarget = destination
                        scrollResetSequence++
                        if (destination == 1 && state.beforeId != Long.MAX_VALUE) actions.newest()
                    }
                } else {
                    tab = destination
                }
            }
        }) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                if (currentTab == 1 && nearbyPage && !isHistoryDetail) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.widthIn(max = 880.dp).fillMaxSize()
                    ) {
                        NearbyInboxPage(actions, { nearbyPage = false }, { id ->
                            nearbyPage = false
                            historyDetailId = id
                            actions.detail(id)
                        })
                    }
                    return@Box
                }
                if (currentTab == 1 && isHistoryDetail) {
                    val back = {
                        historyDetailId = null
                        actions.detail(null)
                    }
                    BackHandler(onBack = back)
                    key(state.detailId) {
                        AlertDetailPage(
                            state,
                            preparation,
                            actions,
                            historyDetailScroll,
                            back,
                            Modifier.widthIn(max = 880.dp)
                        )
                    }
                    return@Box
                }
                if (currentTab == 2 && !showTutorial) {
                    settingsState.SaveableStateProvider(settingsEntry) {
                        SettingsScreen(
                            state = state,
                            environment = environment,
                            session = session,
                            preparation = preparation,
                            actions = actions,
                            selectedPage = settingsPage,
                            selectPage = { settingsPage = it },
                            tutorial = {
                                tutorial = true
                                step = 0
                            }
                        )
                    }
                    return@Box
                }
                Column(Modifier.widthIn(max = 880.dp).fillMaxSize()) {
                    MobilePageHeader(
                        stringResource(
                            if (showTutorial) {
                                R.string.mobile_tutorial
                            } else if (currentTab ==
                                0
                            ) {
                                R.string.mobile_home
                            } else {
                                R.string.mobile_history
                            }
                        ),
                        back = if (showTutorial) {
                            { if (step > 0) step-- else tutorial = false }
                        } else {
                            null
                        }
                    )
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().testTag("mobile-feed"),
                        state = feedScroll,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(
                            if (!showTutorial &&
                                currentTab == 1
                            ) {
                                0.dp
                            } else {
                                14.dp
                            }
                        )
                    ) {
                        if (!state.loaded && (currentTab != 1 || showTutorial)) {
                            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        }
                        if (state.storageError) {
                            item {
                                SettingSection(stringResource(R.string.mobile_storage_error)) {
                                    Text(stringResource(R.string.mobile_storage_notice))
                                    Button(onClick = actions.retryStorage) {
                                        Text(stringResource(R.string.mobile_retry))
                                    }
                                }
                            }
                        }
                        when {
                            showTutorial -> item {
                                SettingSection(
                                    stringResource(
                                        listOf(
                                            R.string.mobile_tutorial_receive,
                                            R.string.mobile_tutorial_read,
                                            R.string.mobile_tutorial_translate
                                        )[currentStep]
                                    )
                                ) {
                                    Text(
                                        stringResource(
                                            listOf(
                                                R.string.mobile_tutorial_receive_body,
                                                R.string.mobile_tutorial_read_body,
                                                R.string.mobile_tutorial_translate_body
                                            )[currentStep]
                                        )
                                    )
                                    if (currentStep == 2) ModelManagement(actions)
                                    Row {
                                        if (currentStep >
                                            0
                                        ) {
                                            TextButton(onClick = {
                                                step--
                                            }) { Text(stringResource(R.string.mobile_back)) }
                                        }
                                        Button(onClick = {
                                            if (step <
                                                2
                                            ) {
                                                step++
                                            } else {
                                                tutorial = false
                                                actions.configure(
                                                    state.options.copy(setupComplete = true)
                                                )
                                            }
                                        }) {
                                            Text(
                                                stringResource(
                                                    if (currentStep ==
                                                        2
                                                    ) {
                                                        R.string.mobile_done
                                                    } else {
                                                        R.string.mobile_next
                                                    }
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            currentTab == 0 -> {
                                item {
                                    HomeOverview(state, environment, session, {
                                        settingsPage = SettingsPage.CAPTURE
                                        tab = 2
                                    }, {
                                        settingsPage = SettingsPage.TRANSLATION
                                        tab = 2
                                    })
                                }
                                item {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            stringResource(R.string.mobile_last_saved),
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        TextButton(onClick = { tab = 1 }) {
                                            Text(stringResource(R.string.mobile_home_view_history))
                                        }
                                    }
                                }
                                val latest = state.latest
                                if (latest == null) {
                                    item { HomeEmptyState() }
                                } else {
                                    item {
                                        AlertPresentation(
                                            latest,
                                            state.options.target.languageTag,
                                            state.options.localConsent,
                                            {
                                                actions.retry(
                                                    latest.id,
                                                    state.options.target.languageTag,
                                                    state.options.engine
                                                )
                                            },
                                            {
                                                actions.prepare(
                                                    latest,
                                                    state.options.target.languageTag,
                                                    state.options.engine
                                                )
                                            },
                                            { actions.detail(latest.id) },
                                            engine = state.options.engine,
                                            viewMode = state.options.messageViewMode,
                                            speechActions = actions
                                        )
                                    }
                                }
                                item {
                                    SettingSection(
                                        stringResource(
                                            if (environment.radio == ProbeRadio.ABSENT) {
                                                R.string.mobile_reception_information
                                            } else {
                                                R.string.mobile_capture_status
                                            }
                                        )
                                    ) {
                                        Text(
                                            stringResource(
                                                when {
                                                    environment.radio == ProbeRadio.ABSENT ->
                                                        R.string.mobile_wifi_only

                                                    environment.radio != ProbeRadio.PRESENT ->
                                                        R.string.mobile_radio_unknown

                                                    environment.systemPackages.isEmpty() ->
                                                        R.string.mobile_source_unknown

                                                    (
                                                        !state.options.notificationConsent ||
                                                            !environment.notificationGranted ||
                                                            !session.notificationConnected
                                                        ) &&
                                                        (
                                                            !state.options.accessibilityConsent ||
                                                                !environment.accessibilityGranted ||
                                                                !session.accessibilityConnected
                                                            ) -> R.string.mobile_setup_needed

                                                    else -> R.string.mobile_ready
                                                }
                                            )
                                        )
                                    }
                                }
                                item {
                                    SettingsGroup {
                                        TextButton(
                                            onClick = {
                                                settingsPage = SettingsPage.CAPTURE
                                                tab = 2
                                            },
                                            modifier = Modifier.fillMaxWidth().heightIn(
                                                min = 56.dp
                                            )
                                        ) {
                                            Text(stringResource(R.string.mobile_manage_access))
                                        }
                                        SettingDivider()
                                        TextButton(
                                            onClick = {
                                                tutorial = true
                                                step = 0
                                            },
                                            modifier = Modifier.fillMaxWidth().heightIn(
                                                min = 56.dp
                                            )
                                        ) {
                                            Text(stringResource(R.string.mobile_tutorial))
                                        }
                                    }
                                }
                                if (state.options.nearbyEnabled) {
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
                                                    tab = 1
                                                    nearbyPage = true
                                                }
                                            )
                                        }
                                    }
                                }
                                val observed = session.records.lastOrNull()?.observation
                                if (observed != null &&
                                    (
                                        observed.bodyEvidence == ProbeBodyEvidence.UNVERIFIED ||
                                            observed.limited ||
                                            observed.groupSummary ||
                                            observed.platformBoundaryPossible
                                        )
                                ) {
                                    item {
                                        Text(
                                            stringResource(R.string.mobile_incomplete),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                observed?.reportedDialogQueueSize?.takeIf {
                                    it > 1
                                }?.let { count ->
                                    item { Text(stringResource(R.string.mobile_pending, count)) }
                                }
                                if (sourceAvailable) {
                                    item {
                                        Button(onClick = actions.openSource) {
                                            Text(stringResource(R.string.mobile_open_source))
                                        }
                                    }
                                }
                            }

                            currentTab == 1 -> {
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
                                                    nearbyPage =
                                                        true
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
                                            localEnabled = state.options.localConsent,
                                            first = index == 0,
                                            last = index == state.records.lastIndex,
                                            open = {
                                                historyDetailId = alert.id
                                                actions.detail(alert.id)
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
                        }
                        if (preparation.phase != PreparationPhase.IDLE &&
                            state.options.engine == LocalEngineKind.ML_KIT
                        ) {
                            item {
                                Text(
                                    stringResource(
                                        preparationMessage(preparation.phase)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        if (state.detailId != null && !isHistoryDetail) {
            BackHandler { actions.detail(null) }
            Dialog(onDismissRequest = {
                actions.detail(null)
            }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                BoxWithConstraints(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
                            .heightIn(
                                max = maxHeight * if (maxHeight <
                                    400.dp
                                ) {
                                    0.92f
                                } else {
                                    0.78f
                                }
                            ).testTag("alert-modal-card")
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Column(
                                Modifier.weight(
                                    1f,
                                    fill = false
                                ).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                AlertDetailBody(state, actions, compact = true, openDetails = {
                                    historyDetailId = state.detailId
                                    tab = 1
                                })
                            }
                            TextButton(
                                onClick = {
                                    actions.detail(null)
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .testTag("alert-modal-close")
                            ) {
                                Text(stringResource(R.string.mobile_close))
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun preparationMessage(phase: PreparationPhase): Int = when (phase) {
    PreparationPhase.IDENTIFYING -> R.string.mobile_working
    PreparationPhase.WAITING_FOR_WIFI -> R.string.mobile_downloading
    PreparationPhase.READY -> R.string.mobile_models_ready
    PreparationPhase.UNKNOWN_LANGUAGE -> R.string.mobile_unknown_language
    else -> R.string.mobile_failed
}
