package xyz.gaon.codeclear.probe

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import xyz.gaon.codeclear.R

class CaptureProbeActivity : ComponentActivity() {
    private var environment by mutableStateOf<ProbeEnvironment?>(null)
    private var observations: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observations = ProbeSession.observe(object : CaptureObserver {
            override fun cleared(generation: Long) {
                if (ProbeLocalTranslation.enabled) ProbeLocalTranslation.select(null)
            }
        })
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            PreviewTheme {
                environment?.let {
                    CapturePreview(it, ::openSettings) { ProbeSourceActions.openFromUser(this) }
                }
            }
        }
    }

    override fun onDestroy() {
        observations?.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val previous = environment
        val current = ProbeEnvironment.read(this)
        if (previous?.notificationGranted == true && !current.notificationGranted) {
            ProbeSession.configure(ProbeRoute.NOTIFICATION, false)
        }
        if (previous?.accessibilityGranted == true && !current.accessibilityGranted) {
            ProbeSession.configure(ProbeRoute.ACCESSIBILITY, false)
        }
        environment = current
    }

    private fun openSettings(action: String) {
        val intent = Intent(action)
        if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
            intent.data = Uri.fromParts("package", packageName, null)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.probe_settings_missing, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.probe_settings_missing, Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
internal fun ProbeSettings(
    environment: ProbeEnvironment,
    openSettings: (String) -> Unit,
    openSource: () -> Unit
) {
    val session = ProbeSession.state
    var consentRoute by remember { mutableStateOf<ProbeRoute?>(null) }
    var confirmOverride by remember { mutableStateOf(false) }
    val canConfigure = environment.radio == ProbeRadio.PRESENT || session.hardwareOverride
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.probe_app_name),
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(stringResource(R.string.probe_scope))
            }
            if (ProbeLocalTranslation.enabled) {
                item {
                    TextButton(onClick = { ProbeLocalTranslation.enable(false) }) {
                        Text(stringResource(R.string.preview_stop_local))
                    }
                }
            }
            item {
                ProbeCard {
                    Text(
                        stringResource(R.string.probe_device),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(
                            R.string.probe_device_format,
                            Build.MANUFACTURER,
                            Build.MODEL,
                            Build.VERSION.RELEASE,
                            Build.VERSION.SDK_INT
                        )
                    )
                    Text(stringResource(R.string.probe_build, Build.ID))
                    Text(
                        stringResource(
                            when (environment.radio) {
                                ProbeRadio.PRESENT -> R.string.probe_radio_present
                                ProbeRadio.ABSENT -> R.string.probe_radio_absent
                                ProbeRadio.UNKNOWN -> R.string.probe_radio_unknown
                            }
                        )
                    )
                    Text(
                        stringResource(
                            R.string.probe_sources,
                            environment.systemPackages.sorted().joinToString().ifEmpty {
                                "—"
                            }
                        )
                    )
                    Text(stringResource(R.string.probe_sources_unverified))
                }
            }
            item {
                ProbeCard {
                    Text(
                        stringResource(R.string.probe_diagnostics),
                        style = MaterialTheme.typography.titleMedium
                    )
                    ProbeToggle(
                        stringResource(R.string.probe_hardware_override),
                        session.hardwareOverride
                    ) {
                        if (it) {
                            confirmOverride = true
                        } else {
                            ProbeSession.setDiagnostics(
                                hardwareOverride = false
                            )
                        }
                    }
                    ProbeToggle(
                        stringResource(R.string.probe_shell_fixture),
                        session.shellFixture
                    ) {
                        ProbeSession.setDiagnostics(shell = it)
                    }
                    Text(stringResource(R.string.probe_changes_clear))
                    Text(stringResource(R.string.probe_source_action_notice))
                    OutlinedButton(
                        onClick = ProbeNotificationListener::inspectConsentedSources,
                        enabled = session.notificationConsent && session.notificationConnected
                    ) { Text(stringResource(R.string.probe_inspect_current)) }
                    OutlinedButton(
                        onClick = openSource,
                        enabled =
                            ProbeSourceActions.state.available && !ProbeSourceActions.state.busy
                    ) { Text(stringResource(R.string.probe_open_original)) }
                    ProbeSourceActions.state.returnedMessages?.let {
                        Text(stringResource(R.string.probe_returned_messages, it))
                    }
                    if (ProbeSourceActions.state.importedMessages > 0) {
                        Text(
                            stringResource(
                                R.string.probe_imported_messages,
                                ProbeSourceActions.state.importedMessages
                            )
                        )
                    }
                    if (ProbeSourceActions.state.failure != null) {
                        Text(stringResource(R.string.probe_source_action_unavailable))
                    }
                }
            }
            item {
                ProbeCard {
                    Text(
                        stringResource(R.string.probe_access),
                        style = MaterialTheme.typography.titleMedium
                    )
                    ProbeAccessRow(
                        title = stringResource(R.string.probe_notification_access),
                        granted = environment.notificationGranted,
                        connected = session.notificationConnected,
                        consent = session.notificationConsent,
                        canConfigure = canConfigure,
                        configure = { consentRoute = ProbeRoute.NOTIFICATION },
                        withdraw = { ProbeSession.configure(ProbeRoute.NOTIFICATION, false) }
                    )
                    ProbeAccessRow(
                        title = stringResource(R.string.probe_accessibility_access),
                        granted = environment.accessibilityGranted,
                        connected = session.accessibilityConnected,
                        consent = session.accessibilityConsent,
                        canConfigure = canConfigure,
                        configure = { consentRoute = ProbeRoute.ACCESSIBILITY },
                        withdraw = { ProbeSession.configure(ProbeRoute.ACCESSIBILITY, false) }
                    )
                    Text(stringResource(R.string.probe_restricted_settings))
                    OutlinedButton(onClick = {
                        openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    }) {
                        Text(stringResource(R.string.probe_app_info))
                    }
                }
            }
        }
    }
    consentRoute?.let { route ->
        AlertDialog(
            onDismissRequest = { consentRoute = null },
            title = { Text(stringResource(R.string.probe_consent_title)) },
            text = {
                Text(
                    stringResource(
                        if (route == ProbeRoute.NOTIFICATION) {
                            R.string.probe_notification_disclosure
                        } else {
                            R.string.probe_accessibility_disclosure
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ProbeSession.configure(route, true)
                    consentRoute = null
                    openSettings(
                        if (route == ProbeRoute.NOTIFICATION) {
                            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                        } else {
                            Settings.ACTION_ACCESSIBILITY_SETTINGS
                        }
                    )
                }) { Text(stringResource(R.string.probe_agree_settings)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    consentRoute = null
                }) { Text(stringResource(R.string.probe_cancel)) }
            }
        )
    }
    if (confirmOverride) {
        AlertDialog(
            onDismissRequest = { confirmOverride = false },
            title = { Text(stringResource(R.string.probe_diagnostics)) },
            text = { Text(stringResource(R.string.probe_override_disclosure)) },
            confirmButton = {
                TextButton(onClick = {
                    ProbeSession.setDiagnostics(hardwareOverride = true)
                    confirmOverride = false
                }) { Text(stringResource(R.string.probe_enable_test)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmOverride = false
                }) { Text(stringResource(R.string.probe_cancel)) }
            }
        )
    }
}

@Composable
private fun ProbeCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun ProbeToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ProbeAccessRow(
    title: String,
    granted: Boolean,
    connected: Boolean,
    consent: Boolean,
    canConfigure: Boolean,
    configure: () -> Unit,
    withdraw: () -> Unit
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(
            R.string.probe_access_status,
            stringResource(if (granted) R.string.probe_yes else R.string.probe_no),
            stringResource(if (connected) R.string.probe_yes else R.string.probe_no),
            stringResource(if (consent) R.string.probe_yes else R.string.probe_no)
        )
    )
    Button(onClick = configure, enabled = canConfigure) {
        Text(stringResource(R.string.probe_configure))
    }
    if (consent) TextButton(onClick = withdraw) { Text(stringResource(R.string.probe_withdraw)) }
}

internal fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance().format(Date(millis))
