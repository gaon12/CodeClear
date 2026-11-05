package xyz.gaon.codeclear.mobile

import android.content.ActivityNotFoundException
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.BuildConfig
import xyz.gaon.codeclear.R

internal object ProjectInformation {
    const val REPOSITORY = "https://github.com/gaon12/CodeClear"
    const val CONTRIBUTORS = "$REPOSITORY/graphs/contributors"
    const val SOURCES = "https://gaon12.github.io/CodeClear/sources/"
    const val MAINTAINER = "gaon12"
}

@Composable
internal fun AppAboutSettings(compatibility: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var linkFailed by remember { mutableStateOf(false) }
    var showEgg by remember { mutableStateOf(false) }
    val taps = remember { VersionTapUnlock() }
    val open: (String) -> Unit = { url ->
        linkFailed = false
        try {
            uriHandler.openUri(url)
        } catch (_: ActivityNotFoundException) {
            linkFailed = true
        } catch (_: IllegalArgumentException) {
            linkFailed = true
        } catch (_: SecurityException) {
            linkFailed = true
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsGroup {
            Column(
                Modifier.fillMaxWidth().testTag("app-version-row").clickable(role = Role.Button) {
                    if (taps.tap(SystemClock.elapsedRealtime())) {
                        showEgg = true
                    }
                }.heightIn(min = 72.dp).padding(horizontal = 24.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    stringResource(R.string.mobile_version),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    stringResource(
                        R.string.mobile_version_value,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE
                    ),
                    Modifier.testTag("app-version-value"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SettingDivider()
            SettingRow(
                stringResource(R.string.mobile_project_repository),
                ProjectInformation.REPOSITORY.removePrefix("https://"),
                onClick = { open(ProjectInformation.REPOSITORY) }
            )
            SettingDivider()
            SettingRow(
                stringResource(R.string.mobile_contributors),
                stringResource(R.string.mobile_contributors_summary, ProjectInformation.MAINTAINER),
                onClick = { open(ProjectInformation.CONTRIBUTORS) }
            )
        }
        if (linkFailed) {
            Text(
                stringResource(R.string.mobile_link_unavailable),
                Modifier.padding(horizontal = 12.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SettingsGroup {
            SettingNote(stringResource(R.string.mobile_independent_notice))
            SettingRow(
                stringResource(R.string.mobile_information_sources),
                onClick = { open(ProjectInformation.SOURCES) }
            )
        }
        SettingsGroup {
            SettingRow(stringResource(R.string.mobile_compatibility), onClick = compatibility)
        }
    }
    if (showEgg) SignalEasterEgg { showEgg = false }
}
