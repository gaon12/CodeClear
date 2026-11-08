package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeEnvironment
import xyz.gaon.codeclear.capture.ProbeRadio
import xyz.gaon.codeclear.capture.ProbeSessionState

@Composable
internal fun HomeOverview(
    state: MobileState,
    environment: ProbeEnvironment,
    session: ProbeSessionState,
    openCapture: () -> Unit,
    openTranslation: () -> Unit
) {
    val captureActive =
        (
            state.options.notificationConsent && environment.notificationGranted &&
                session.notificationConnected
            ) ||
            (
                state.options.accessibilityConsent && environment.accessibilityGranted &&
                    session.accessibilityConnected
                )
    SettingsGroup {
        Row(Modifier.fillMaxWidth().testTag("home-overview")) {
            OverviewAction(
                R.drawable.ic_category_capture,
                stringResource(R.string.mobile_collection),
                stringResource(
                    when {
                        environment.radio == ProbeRadio.ABSENT -> {
                            R.string.mobile_home_no_radio
                        }

                        environment.radio != ProbeRadio.PRESENT ||
                            environment.systemPackages.isEmpty() -> {
                            R.string.mobile_home_check_source
                        }

                        captureActive -> R.string.mobile_home_capture_connected

                        else -> R.string.mobile_home_setup
                    }
                ),
                Modifier.weight(1f),
                openCapture
            )
            OverviewAction(
                R.drawable.ic_category_translation,
                stringResource(
                    if (state.options.engine ==
                        xyz.gaon.codeclear.translation.TranslationEngineKind.MY_MEMORY
                    ) {
                        R.string.mobile_server_translation
                    } else {
                        R.string.mobile_translation_settings
                    }
                ),
                if (state.options.translationAllowed) {
                    languageName(state.options.target.languageTag)
                } else {
                    stringResource(R.string.mobile_off)
                },
                Modifier.weight(1f),
                openTranslation
            )
        }
        if (environment.radio == ProbeRadio.PRESENT) {
            Text(
                stringResource(R.string.mobile_home_capture_notice),
                Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp)
                    .testTag("home-capture-notice"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OverviewAction(
    icon: Int,
    title: String,
    summary: String,
    modifier: Modifier,
    action: () -> Unit
) {
    Column(
        modifier.clickable(role = Role.Button, onClick = action).heightIn(min = 112.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painterResource(icon),
            null,
            Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            title,
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center
        )
        Text(
            summary,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun HomeEmptyState() {
    SettingsGroup {
        Column(
            Modifier.fillMaxWidth().padding(28.dp).testTag("home-empty"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(
                    painterResource(R.drawable.ic_result),
                    null,
                    Modifier.padding(18.dp).size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                stringResource(R.string.mobile_home_empty_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                stringResource(R.string.mobile_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
