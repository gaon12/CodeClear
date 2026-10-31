package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.probe.LocalTarget

@Composable
internal fun DetailTargetPicker(alert: SavedAlert, state: MobileState, actions: MobileActions) {
    val savedSource = state.detailSourceLanguage ?: alert.translation?.sourceLanguage
    var source by remember(alert.id, alert.capture.original) {
        mutableStateOf(originalLanguageCode(savedSource))
    }
    var open by remember(alert.id) { mutableStateOf(false) }
    var identifying by remember(alert.id) { mutableStateOf(false) }
    val alive = remember(alert.id, alert.capture.original) { AtomicBoolean(true) }
    val request = remember(alert.id) { arrayOfNulls<AutoCloseable>(1) }
    DisposableEffect(alive) {
        onDispose {
            alive.set(false)
            request[0]?.close()
        }
    }
    LaunchedEffect(savedSource) {
        originalLanguageCode(savedSource)?.let { source = it }
    }
    val target = state.detailTarget ?: state.options.target.languageTag
    SettingsGroup {
        SettingRow(
            stringResource(
                R.string.mobile_target_language
            ),
            languageName(target),
            onClick = {
                open = true
                if (source == null && !identifying) {
                    identifying = true
                    request[0] = actions.identifyOriginal(alert.capture.original) {
                        if (alive.get()) {
                            source = originalLanguageCode(it)
                            identifying = false
                        }
                    }
                }
            }
        )
    }
    if (open) {
        MobileConfirmationDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.mobile_target_language)) },
            text = {
                Column(
                    Modifier.testTag("detail-target-options").verticalScroll(rememberScrollState())
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (source == null) {
                        Text(
                            stringResource(
                                if (identifying) {
                                    R.string.mobile_identifying_original
                                } else {
                                    R.string.mobile_original_unknown
                                }
                            )
                        )
                    }
                    for (choice in LocalTarget.entries) {
                        val allowed = canTranslateDetailTo(choice, source)
                        RadioChoiceRow(
                            if (source != null && !allowed) {
                                stringResource(
                                    R.string.mobile_original_language_choice,
                                    languageName(choice.languageTag)
                                )
                            } else {
                                languageName(choice.languageTag)
                            },
                            selected = allowed && target == choice.languageTag,
                            enabled = allowed,
                            onClick = {
                                val original = source
                                if (original != null && canTranslateDetailTo(choice, original)) {
                                    open = false
                                    actions.changeDetailTarget(
                                        alert.id,
                                        choice.languageTag,
                                        original
                                    )
                                }
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                }) { Text(stringResource(R.string.mobile_close)) }
            }
        )
    }
}
