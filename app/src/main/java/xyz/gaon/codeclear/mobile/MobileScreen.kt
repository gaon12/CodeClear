package xyz.gaon.codeclear.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.gaon.codeclear.R
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
    val systemSettings: () -> Unit = {}
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
    val homeScroll = rememberLazyListState()
    val historyScroll = rememberLazyListState()
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
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (!state.loaded) {
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
                                    Text(
                                        stringResource(R.string.mobile_last_saved),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                val latest = state.latest
                                if (latest == null) {
                                    item { Text(stringResource(R.string.mobile_empty)) }
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
                                            engine = state.options.engine
                                        )
                                    }
                                }
                                item {
                                    SettingSection(stringResource(R.string.mobile_capture_status)) {
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
                                        TextButton(onClick = {
                                            settingsPage = SettingsPage.CAPTURE
                                            tab = 2
                                        }) { Text(stringResource(R.string.mobile_manage_access)) }
                                        TextButton(onClick = {
                                            tutorial = true
                                            step = 0
                                        }) { Text(stringResource(R.string.mobile_tutorial)) }
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
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (state.loading) {
                                    item {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                }
                                if (!state.loading &&
                                    state.records.isEmpty()
                                ) {
                                    item { Text(stringResource(R.string.mobile_no_results)) }
                                }
                                items(state.records, key = { it.id }) { alert ->
                                    AlertPresentation(
                                        alert,
                                        state.options.target.languageTag,
                                        state.options.localConsent,
                                        {
                                            actions.retry(
                                                alert.id,
                                                state.options.target.languageTag,
                                                state.options.engine
                                            )
                                        },
                                        {
                                            actions.prepare(
                                                alert,
                                                state.options.target.languageTag,
                                                state.options.engine
                                            )
                                        },
                                        { actions.detail(alert.id) },
                                        engine = state.options.engine
                                    )
                                }
                                item {
                                    Row {
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
        if (state.detailId != null) {
            BackHandler { actions.detail(null) }
            Dialog(onDismissRequest = {
                actions.detail(null)
            }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.padding(16.dp).widthIn(max = 880.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Column(
                            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            val alert = state.detail
                            if (alert ==
                                null
                            ) {
                                Text(
                                    stringResource(
                                        if (state.loading) {
                                            R.string.mobile_working
                                        } else {
                                            R.string.mobile_deleted
                                        }
                                    )
                                )
                            } else {
                                val target = state.detailTarget ?: state.options.target.languageTag
                                val engine = state.detailEngine ?: state.options.engine
                                val active =
                                    state.options.localConsent && engine == state.options.engine
                                AlertPresentation(alert, target, active, {
                                    actions.retry(alert.id, target, engine)
                                }, { actions.prepare(alert, target, engine) }, engine = engine)
                                AlertMetadata(alert)
                                TextButton(onClick = {
                                    actions.delete(alert.id)
                                }) { Text(stringResource(R.string.mobile_delete)) }
                            }
                        }
                        TextButton(onClick = {
                            actions.detail(null)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.mobile_close))
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
