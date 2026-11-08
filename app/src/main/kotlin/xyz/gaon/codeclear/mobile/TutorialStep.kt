package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeEnvironment
import xyz.gaon.codeclear.capture.ProbeRadio
import xyz.gaon.codeclear.capture.ProbeSessionState

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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.testTag("tutorial-content")) {
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
            }
        }
        PageNavigation(
            previousLabel = stringResource(R.string.mobile_previous),
            nextLabel = stringResource(
                if (step ==
                    2
                ) {
                    R.string.mobile_done
                } else {
                    R.string.mobile_next
                }
            ),
            previous = back.takeIf { step > 0 },
            next = next,
            tag = "tutorial-navigation",
            primaryNext = true
        )
    }
}
