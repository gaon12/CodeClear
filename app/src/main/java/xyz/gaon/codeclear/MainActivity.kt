package xyz.gaon.codeclear

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import xyz.gaon.codeclear.mobile.MobileActions
import xyz.gaon.codeclear.mobile.MobileScreen
import xyz.gaon.codeclear.mobile.MobileTheme
import xyz.gaon.codeclear.mobile.ResultNotifications
import xyz.gaon.codeclear.mobile.SavedAlert
import xyz.gaon.codeclear.mobile.ThemeChoice
import xyz.gaon.codeclear.mobile.mobileRuntime
import xyz.gaon.codeclear.probe.LocalTarget
import xyz.gaon.codeclear.probe.ProbeEnvironment
import xyz.gaon.codeclear.probe.ProbeSession
import xyz.gaon.codeclear.probe.ProbeSourceActions

private data class PendingDeletion(val id: Long?)
private data class PendingPreparation(val alert: SavedAlert, val target: String)

class MainActivity : ComponentActivity() {
    private var environment by mutableStateOf<ProbeEnvironment?>(null)
    private var notificationsAllowed by mutableStateOf(false)
    private var notificationRequested = false
    private val notificationRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationRequested = true
            refreshAccess()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationRequested = savedInstanceState?.getBoolean("notification_requested") ?: false
        mobileRuntime.resumeProduct()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        openDestination(intent)
        enableEdgeToEdge()
        setContent {
            val runtime = mobileRuntime
            val dark = runtime.state.options.theme == ThemeChoice.DARK ||
                (runtime.state.options.theme == ThemeChoice.SYSTEM && isSystemInDarkTheme())
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(
                            transparent
                        )
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(
                            android.graphics.Color.BLACK
                        )
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.WHITE
                        )
                    }
                )
            }
            MobileTheme(runtime.state.options.theme) {
                val controller = runtime.controller
                var deletion by remember { mutableStateOf<PendingDeletion?>(null) }
                var preparation by remember { mutableStateOf<PendingPreparation?>(null) }
                environment?.let { current ->
                    MobileScreen(
                        runtime.state,
                        current,
                        ProbeSession.state,
                        ProbeSourceActions.state.available,
                        runtime.preparation,
                        MobileActions(
                            runtime::configure, controller::search, {
                                controller.openDetail(it)
                            }, {
                                deletion =
                                    PendingDeletion(it)
                            }, controller::retry,
                            { alert, target ->
                                preparation = PendingPreparation(alert, target)
                            }, ::openSettings, {
                                ProbeSourceActions.openFromUser(this)
                            }, ::requestNotifications,
                            notificationsAllowed,
                            controller::older,
                            controller::newest,
                            controller::retryStorage
                        )
                    )
                }
                deletion?.let { request ->
                    AlertDialog(
                        onDismissRequest = {
                            deletion = null
                        },
                        title = {
                            Text(stringResource(R.string.mobile_delete_title))
                        },
                        text = {
                            Text(
                                stringResource(R.string.mobile_delete_notice),
                                Modifier.verticalScroll(rememberScrollState())
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                deletion = null
                                runtime.models.withdraw()
                                controller.delete(request.id)
                            }) { Text(stringResource(R.string.mobile_delete)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                deletion = null
                            }) { Text(stringResource(R.string.mobile_cancel)) }
                        }
                    )
                }
                preparation?.let { request ->
                    AlertDialog(
                        onDismissRequest = {
                            preparation = null
                        },
                        title = {
                            Text(stringResource(R.string.mobile_prepare))
                        },
                        text = {
                            Text(
                                stringResource(R.string.mobile_model_notice),
                                Modifier.verticalScroll(rememberScrollState())
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                preparation = null
                                if (runtime.state.options.localConsent) {
                                    runtime.models.prepare(
                                        request.alert.capture.original,
                                        LocalTarget.entries.first {
                                            it.languageTag ==
                                                request.target
                                        },
                                        true
                                    )
                                }
                            }) { Text(stringResource(R.string.mobile_agree)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                preparation =
                                    null
                            }) { Text(stringResource(R.string.mobile_cancel)) }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openDestination(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("notification_requested", notificationRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        mobileRuntime.resumeProduct()
        refreshAccess()
        mobileRuntime.controller.start()
        mobileRuntime.controller.refresh()
    }

    private fun refreshAccess() {
        environment = ProbeEnvironment.read(this)
        notificationsAllowed = ResultNotifications(this).allowed()
    }

    private fun openDestination(intent: Intent) {
        ResultNotifications.destination(intent.data)?.let {
            mobileRuntime.controller.openDetail(it.id, it.target)
            intent.data = null
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !notificationRequested
        ) {
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }
    }

    private fun openSettings(action: String) {
        val intent = Intent(action)
        if (action ==
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        ) {
            intent.data =
                Uri.fromParts("package", packageName, null)
        }
        if (action ==
            Settings.ACTION_APP_NOTIFICATION_SETTINGS
        ) {
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        try {
            startActivity(intent)
        } catch (
            _: ActivityNotFoundException
        ) {
            Toast.makeText(this, R.string.mobile_settings_missing, Toast.LENGTH_LONG).show()
        } catch (
            _: SecurityException
        ) {
            Toast.makeText(this, R.string.mobile_settings_missing, Toast.LENGTH_LONG).show()
        }
    }
}
