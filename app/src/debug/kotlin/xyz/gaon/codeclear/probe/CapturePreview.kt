package xyz.gaon.codeclear.probe

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.TranslationInput
import xyz.gaon.codeclear.translation.TranslationResult
import xyz.gaon.codeclear.translation.TranslationState
import xyz.gaon.codeclear.translation.TranslationTarget

@Composable
internal fun CapturePreview(
    environment: ProbeEnvironment,
    openSettings: (String) -> Unit,
    openSource: () -> Unit = {}
) {
    var destination by rememberSaveable { mutableStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showConsent by remember { mutableStateOf(false) }
    var downloadInput by remember { mutableStateOf<TranslationInput?>(null) }
    val session = ProbeSession.state
    val selected = if (detailId == null) {
        session.records.lastOrNull()
    } else {
        session.records.find { it.id == detailId }
    }
    val input = selected?.localInput(ProbeLocalTranslation.target)
    LaunchedEffect(input, ProbeLocalTranslation.enabled) {
        ProbeLocalTranslation.select(input.takeIf { ProbeLocalTranslation.enabled })
    }
    DisposableEffect(Unit) { onDispose { ProbeLocalTranslation.select(null) } }
    val translation = if (input == ProbeLocalTranslation.input) {
        ProbeLocalTranslation.state
    } else {
        TranslationState.Working
    }
    val labels = listOf(R.string.preview_home, R.string.preview_history, R.string.preview_settings)
    val icons =
        listOf(R.drawable.preview_home, R.drawable.preview_history, R.drawable.preview_settings)
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = destination == index,
                        onClick = { destination = index },
                        icon = { Icon(painterResource(icons[index]), contentDescription = null) },
                        label = { Text(stringResource(label)) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when (destination) {
                0 -> PreviewHome(
                    environment, session, selected, translation,
                    configure = { destination = 2 },
                    consent = { showConsent = true },
                    download = { downloadInput = input },
                    retry = { input?.let { ProbeLocalTranslation.retry(it) } },
                    details = { detailId = selected?.id }
                )

                1 -> PreviewHistory(session.records) { detailId = it }

                else -> ProbeSettings(environment, openSettings, openSource)
            }
        }
    }
    if (detailId != null) {
        AlertDialog(
            onDismissRequest = { detailId = null },
            title = { Text(stringResource(R.string.preview_details)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (selected == null) {
                        Text(stringResource(R.string.preview_removed))
                    } else {
                        OriginalText(selected.observation)
                        TranslationText(translation, input, { showConsent = true }, {
                            downloadInput =
                                input
                        })
                        HorizontalDivider()
                        Text(
                            sourceLabel(selected.observation),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            stringResource(
                                R.string.probe_captured_at,
                                formatTime(selected.observation.capturedAtMillis)
                            )
                        )
                        selected.observation.postedAtMillis?.let {
                            Text(stringResource(R.string.probe_posted_at, formatTime(it)))
                        }
                        val broadcast = selected.observation.broadcastFields
                        if (broadcast == null) {
                            Text(stringResource(R.string.preview_level_unknown))
                        } else {
                            Text(
                                stringResource(
                                    R.string.probe_structured_fields,
                                    broadcast.format,
                                    broadcast.category,
                                    broadcast.priority,
                                    broadcast.cmasClass?.toString() ?: "—",
                                    broadcast.etwsType?.toString() ?: "—"
                                )
                            )
                            Text(
                                stringResource(
                                    R.string.probe_source_received,
                                    formatTime(broadcast.receivedAtMillis)
                                )
                            )
                            Text(stringResource(R.string.probe_parcel_profile_notice))
                        }
                        Text(
                            selected.observation.packageName,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(R.string.probe_completeness_unknown),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    detailId = null
                }) { Text(stringResource(R.string.preview_close)) }
            }
        )
    }
    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text(stringResource(R.string.preview_local_enable)) },
            text = { Text(stringResource(R.string.preview_local_privacy)) },
            confirmButton = {
                TextButton(onClick = {
                    ProbeLocalTranslation.enable(true)
                    showConsent = false
                }) {
                    Text(stringResource(R.string.preview_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showConsent = false
                }) { Text(stringResource(R.string.probe_cancel)) }
            }
        )
    }
    downloadInput?.let { approved ->
        AlertDialog(
            onDismissRequest = { downloadInput = null },
            title = { Text(stringResource(R.string.preview_download)) },
            text = { Text(stringResource(R.string.preview_download_disclosure)) },
            confirmButton = {
                TextButton(onClick = {
                    if (approved == input) ProbeLocalTranslation.retry(approved, download = true)
                    downloadInput = null
                }) { Text(stringResource(R.string.preview_download_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    downloadInput = null
                }) { Text(stringResource(R.string.probe_cancel)) }
            }
        )
    }
}

@Composable
private fun PreviewHome(
    environment: ProbeEnvironment,
    session: ProbeSessionState,
    record: ProbeRecord?,
    translation: TranslationState,
    configure: () -> Unit,
    consent: () -> Unit,
    download: () -> Unit,
    retry: () -> Unit,
    details: () -> Unit
) {
    LazyColumn(
        Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text(
                "CodeClear",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                stringResource(R.string.preview_heading),
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                stringResource(R.string.preview_subtitle),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        item { TargetPicker() }
        val lastDialog = session.records.lastOrNull {
            it.observation.route ==
                ProbeRoute.ACCESSIBILITY
        }
            ?.observation
        if (record?.observation?.reportedDialogQueueSize == null) {
            lastDialog?.reportedDialogQueueSize?.let { count ->
                item { Text(stringResource(R.string.preview_queued_alerts, count)) }
            }
        }
        if (environment.radio == ProbeRadio.ABSENT) {
            item {
                Text(
                    stringResource(R.string.probe_radio_absent),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (!session.notificationConsent || !session.accessibilityConsent ||
            !environment.notificationGranted || !environment.accessibilityGranted ||
            !session.notificationConnected || !session.accessibilityConnected
        ) {
            item {
                PreviewCard {
                    Text(
                        stringResource(R.string.preview_setup_needed),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.preview_setup_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = configure) {
                        Text(stringResource(R.string.preview_setup))
                    }
                }
            }
        }
        item {
            if (record == null) {
                PreviewCard {
                    Icon(
                        painterResource(R.drawable.preview_warning),
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        stringResource(R.string.preview_waiting),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(stringResource(R.string.probe_empty))
                    if (!ProbeLocalTranslation.enabled) {
                        OutlinedButton(onClick = consent) {
                            Text(stringResource(R.string.preview_local_enable))
                        }
                    }
                }
            } else {
                PreviewCard {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(R.drawable.preview_warning),
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                record.observation.title.ifBlank {
                                    stringResource(R.string.preview_alert)
                                },
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                sourceLabel(record.observation),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OriginalText(record.observation)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TranslationText(
                                translation,
                                record.localInput(ProbeLocalTranslation.target),
                                consent,
                                download,
                                retry
                            )
                        }
                    }
                    Text(
                        stringResource(
                            R.string.probe_captured_at,
                            formatTime(record.observation.capturedAtMillis)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = details, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.preview_details))
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.probe_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OriginalText(observation: ProbeObservation) {
    if (observation.headingHint != ProbeHeadingKind.UNKNOWN) {
        Text(
            stringResource(R.string.preview_heading_hint, headingName(observation.headingHint)),
            style = MaterialTheme.typography.labelLarge
        )
    }
    observation.reportedDialogQueueSize?.let { count ->
        Text(
            stringResource(R.string.preview_queued_alerts, count),
            style = MaterialTheme.typography.bodyMedium
        )
    }
    Text(
        stringResource(R.string.preview_original),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SelectionContainer {
        Text(
            observation.body.ifEmpty {
                stringResource(R.string.probe_body_unavailable)
            },
            fontSize = 18.sp,
            lineHeight = 28.sp
        )
    }
    if (observation.limited) Text(stringResource(R.string.probe_limited))
    if (observation.groupSummary) Text(stringResource(R.string.probe_group_summary))
    if (observation.bodyEvidence == ProbeBodyEvidence.UNVERIFIED) {
        Text(
            stringResource(R.string.preview_individual_unknown),
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (observation.platformBoundaryPossible) {
        Text(
            stringResource(R.string.probe_platform_boundary),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun headingName(kind: ProbeHeadingKind): String = stringResource(
    when (kind) {
        ProbeHeadingKind.NATIONAL -> R.string.preview_kind_national
        ProbeHeadingKind.EXTREME -> R.string.preview_kind_extreme
        ProbeHeadingKind.SEVERE -> R.string.preview_kind_severe
        ProbeHeadingKind.AMBER -> R.string.preview_kind_amber
        ProbeHeadingKind.PUBLIC_SAFETY -> R.string.preview_kind_public
        ProbeHeadingKind.TEST -> R.string.preview_kind_test
        ProbeHeadingKind.OPERATOR_DEFINED -> R.string.preview_kind_operator
        ProbeHeadingKind.EARTHQUAKE -> R.string.preview_kind_earthquake
        ProbeHeadingKind.TSUNAMI -> R.string.preview_kind_tsunami
        ProbeHeadingKind.EARTHQUAKE_AND_TSUNAMI -> R.string.preview_kind_earthquake_tsunami
        ProbeHeadingKind.OTHER_EMERGENCY -> R.string.preview_kind_emergency
        else -> R.string.preview_kind_other
    }
)

@Composable
private fun TranslationText(
    state: TranslationState,
    input: TranslationInput?,
    consent: () -> Unit,
    download: () -> Unit,
    retry: () -> Unit = { input?.let { ProbeLocalTranslation.retry(it) } }
) {
    Text(
        stringResource(R.string.preview_translation, targetName(ProbeLocalTranslation.target)),
        style = MaterialTheme.typography.labelLarge
    )
    when {
        !ProbeLocalTranslation.enabled -> {
            Text(stringResource(R.string.preview_local_hint))
            OutlinedButton(onClick = consent) {
                Text(stringResource(R.string.preview_local_enable))
            }
        }

        input == null -> Text(stringResource(R.string.preview_not_translatable))

        state == TranslationState.Working -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.preview_working))
            }
        }

        state is TranslationState.Finished -> when (val result = state.result) {
            is TranslationResult.Ready -> {
                if (result.sameLanguage) {
                    Text(
                        stringResource(
                            if (input.target.modelCode ==
                                "zh"
                            ) {
                                R.string.preview_script_conversion
                            } else {
                                R.string.preview_same_language
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                SelectionContainer { Text(result.text, fontSize = 18.sp, lineHeight = 28.sp) }
                Text(
                    stringResource(R.string.preview_machine_notice),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            TranslationResult.NeedsModels -> {
                Text(stringResource(R.string.preview_needs_models))
                Button(onClick = download) { Text(stringResource(R.string.preview_download)) }
            }

            else -> {
                Text(
                    stringResource(
                        if (result ==
                            TranslationResult.UnknownLanguage
                        ) {
                            R.string.preview_unknown_language
                        } else {
                            R.string.preview_failed
                        }
                    )
                )
                OutlinedButton(onClick = retry) { Text(stringResource(R.string.preview_retry)) }
            }
        }

        else -> Text(stringResource(R.string.preview_working))
    }
}

@Composable
private fun PreviewHistory(records: List<ProbeRecord>, details: (Long) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(records, query) {
        records.asReversed().filter {
            it.observation.body.contains(query, true) ||
                it.observation.title.contains(query, true)
        }
    }
    LazyColumn(
        Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                stringResource(R.string.preview_history),
                style = MaterialTheme.typography.headlineLarge
            )
        }
        item {
            Text(
                stringResource(R.string.probe_not_history),
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            OutlinedTextField(query, {
                query = it.take(256)
            }, modifier = Modifier.fillMaxWidth(), label = {
                Text(stringResource(R.string.preview_search))
            }, singleLine = true, shape = RoundedCornerShape(16.dp))
        }
        item {
            TextButton(onClick = ProbeSession::clear, enabled = records.isNotEmpty()) {
                Text(stringResource(R.string.probe_clear))
            }
        }
        if (filtered.isEmpty()) item { Text(stringResource(R.string.preview_no_results)) }
        items(filtered, key = { it.id }) { record ->
            Card(
                onClick = {
                    details(record.id)
                },
                shape = RoundedCornerShape(
                    20.dp
                ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        sourceLabel(record.observation),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        record.observation.body,
                        maxLines = 3,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        formatTime(record.observation.capturedAtMillis),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetPicker() {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.preview_target, targetName(ProbeLocalTranslation.target)))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            TranslationTarget.entries.forEach { target ->
                DropdownMenuItem(text = { Text(targetName(target)) }, onClick = {
                    ProbeLocalTranslation.chooseTarget(target)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun targetName(target: TranslationTarget): String = stringResource(
    when (target) {
        TranslationTarget.KOREAN -> R.string.preview_ko
        TranslationTarget.SIMPLIFIED_CHINESE -> R.string.preview_zh_hans
        TranslationTarget.TRADITIONAL_CHINESE -> R.string.preview_zh_hant
        TranslationTarget.JAPANESE -> R.string.preview_ja
        TranslationTarget.ENGLISH -> R.string.preview_en
        TranslationTarget.FRENCH -> R.string.preview_fr
        TranslationTarget.GERMAN -> R.string.preview_de
    }
)

@Composable
private fun sourceLabel(observation: ProbeObservation): String = stringResource(
    when {
        observation.origin == ProbeOrigin.SHELL_FIXTURE -> R.string.preview_shell
        observation.route == ProbeRoute.NOTIFICATION -> R.string.preview_notification
        else -> R.string.preview_dialog
    }
)

@Composable
private fun PreviewCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content
        )
    }
}
