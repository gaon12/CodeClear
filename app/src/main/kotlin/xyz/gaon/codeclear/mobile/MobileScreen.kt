package xyz.gaon.codeclear.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeEnvironment
import xyz.gaon.codeclear.capture.ProbeSessionState
import xyz.gaon.codeclear.translation.TranslationEngineKind

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
    val firstRun = state.loaded && !state.options.setupComplete
    val showTutorial = tutorial || firstRun
    val restoreAccess = state.loaded && state.options.setupComplete &&
        requiredAccessMissing(environment, actions.notificationsAllowed)
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
    BackHandler(enabled = showTutorial && (!firstRun || step > 0)) {
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
            if (!firstRun && !restoreAccess) {
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
                            if (destination == 1 &&
                                state.beforeId != Long.MAX_VALUE
                            ) {
                                actions.newest()
                            }
                        }
                    } else {
                        tab = destination
                    }
                }
            }
        }) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                if (restoreAccess && !showTutorial) {
                    RequiredAccessPage(state, environment, session, actions) {
                        tutorial = true
                        step =
                            0
                    }
                    return@Box
                }
                if (currentTab == 1 && nearbyPage && !isHistoryDetail && !showTutorial) {
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
                if (currentTab == 1 && isHistoryDetail && !showTutorial) {
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
                        back = if (showTutorial && (!firstRun || step > 0)) {
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
                                TutorialStep(
                                    currentStep,
                                    state,
                                    environment,
                                    session,
                                    actions,
                                    back = { step-- },
                                    next = {
                                        if (step < 2) {
                                            step++
                                        } else {
                                            tutorial = false
                                            actions.configure(
                                                state.options.copy(setupComplete = true)
                                            )
                                        }
                                    }
                                )
                            }

                            currentTab == 0 -> homeFeed(
                                state, environment, session, sourceAvailable, actions,
                                openSettings = {
                                    settingsPage = it
                                    tab = 2
                                },
                                openHistory = { tab = 1 },
                                openNearby = {
                                    tab = 1
                                    nearbyPage = true
                                },
                                openTutorial = {
                                    tutorial = true
                                    step = 0
                                }
                            )

                            currentTab == 1 -> historyFeed(
                                state,
                                actions,
                                openNearby = { nearbyPage = true },
                                openDetail = {
                                    historyDetailId = it
                                    actions.detail(it)
                                }
                            )
                        }
                        if (preparation.phase != PreparationPhase.IDLE &&
                            state.options.engine == TranslationEngineKind.ML_KIT
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
        if (state.detailId != null && !isHistoryDetail && !showTutorial && !restoreAccess) {
            AlertDetailDialog(state, actions) {
                historyDetailId = state.detailId
                tab = 1
            }
        }
        actions.modelDownload?.takeIf { it.visible && (!restoreAccess || showTutorial) }?.let {
            ModelDownloadDialog(it, actions)
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
