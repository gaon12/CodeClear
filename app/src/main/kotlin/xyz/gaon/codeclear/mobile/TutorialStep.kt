package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.probe.ProbeEnvironment
import xyz.gaon.codeclear.probe.ProbeRadio
import xyz.gaon.codeclear.probe.ProbeSessionState

@Composable
internal fun TutorialStep(
    step: Int,
    state: MobileState,
    environment: ProbeEnvironment,
    session: ProbeSessionState,
    actions: MobileActions,
    back: () -> Unit,
    next: () -> Unit
) {
    SettingSection(
        stringResource(
            listOf(
                R.string.mobile_tutorial_receive,
                R.string.mobile_tutorial_read,
                R.string.mobile_tutorial_translate
            )[step]
        )
    ) {
        if (step ==
            0
        ) {
            Text(stringResource(R.string.mobile_intro_purpose))
        }
        Text(
            stringResource(
                listOf(
                    R.string.mobile_tutorial_receive_body,
                    R.string.mobile_tutorial_read_body,
                    R.string.mobile_tutorial_translate_body
                )[step]
            )
        )
        if (step == 1) {
            NotificationPermissionSetup(actions)
            if (environment.radio == ProbeRadio.PRESENT) {
                MobileSettings(
                    SettingsPage.CAPTURE,
                    state,
                    environment,
                    session,
                    actions,
                    tutorial = {},
                    showTutorialLink = false
                )
            } else {
                Text(
                    stringResource(
                        if (environment.radio ==
                            ProbeRadio.ABSENT
                        ) {
                            R.string.mobile_wifi_only
                        } else {
                            R.string.mobile_radio_unknown
                        }
                    )
                )
            }
        }
        if (step ==
            2
        ) {
            LocalTranslationSettings(state.options, actions)
        }
        Row {
            if (step >
                0
            ) {
                TextButton(onClick = {
                    back()
                }) { Text(stringResource(R.string.mobile_back)) }
            }
            Button(onClick = next) {
                Text(
                    stringResource(
                        if (step ==
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
