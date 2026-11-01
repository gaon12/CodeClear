package xyz.gaon.codeclear

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import xyz.gaon.codeclear.mobile.MobileActions
import xyz.gaon.codeclear.mobile.MobileConfirmationDialog
import xyz.gaon.codeclear.mobile.MobileOptions
import xyz.gaon.codeclear.mobile.MobileScreen
import xyz.gaon.codeclear.mobile.MobileTheme
import xyz.gaon.codeclear.mobile.ResultNotifications
import xyz.gaon.codeclear.mobile.SavedAlert
import xyz.gaon.codeclear.mobile.SpeechPhase
import xyz.gaon.codeclear.mobile.ThemeChoice
import xyz.gaon.codeclear.mobile.UiLanguages
import xyz.gaon.codeclear.mobile.mobileRuntime
import xyz.gaon.codeclear.nearby.NearbyAccess
import xyz.gaon.codeclear.nearby.NearbyBackgroundService
import xyz.gaon.codeclear.probe.LocalTarget
import xyz.gaon.codeclear.probe.ProbeEnvironment
import xyz.gaon.codeclear.probe.ProbeSession
import xyz.gaon.codeclear.probe.ProbeSourceActions
import xyz.gaon.codeclear.translation.TranslationEngineKind

private data class PendingDeletion(val id: Long?)

class MainActivity : AppCompatActivity() {
    private val accessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (!isFinishing && !isDestroyed) refreshAccess()
        }
    }
    private var environment by mutableStateOf<ProbeEnvironment?>(null)
    private var notificationsAllowed by mutableStateOf(false)
    private var notificationRequested = false
    private var systemSettingsRequest = 0L
    private var systemSettingsOpened = false
    private val notificationRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationRequested = true
            refreshAccess()
        }

    private val nearbyRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            mobileRuntime.nearby.refresh()
            ensureNearbyBackground()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationRequested = savedInstanceState?.getBoolean("notification_requested") ?: false
        systemSettingsOpened = savedInstanceState?.getBoolean("system_settings_opened") ?: false
        mobileRuntime.resumeProduct()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        openDestination(intent)
        enableEdgeToEdge()
        setContent {
            val runtime = mobileRuntime
            LaunchedEffect(
                ProbeSession.state.accessibilityConnected,
                ProbeSession.state.notificationConnected
            ) {
                refreshAccess()
            }
            LaunchedEffect(runtime.speech.state) {
                if (runtime.speech.state.phase == SpeechPhase.UNAVAILABLE &&
                    runtime.speech.state.key != null
                ) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.mobile_speech_unavailable,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            LaunchedEffect(
                runtime.state.loaded,
                runtime.state.options.labsEnabled,
                runtime.state.options.nearbyEnabled
            ) {
                if (lifecycle.currentState.isAtLeast(
                        Lifecycle.State.STARTED
                    )
                ) {
                    ensureNearbyBackground()
                }
            }
            LaunchedEffect(runtime.state.loaded) {
                if (runtime.state.loaded) {
                    if (Build.VERSION.SDK_INT < 33) {
                        val saved = runtime.state.options.uiLanguage
                        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != saved) {
                            AppCompatDelegate.setApplicationLocales(
                                LocaleListCompat.forLanguageTags(saved)
                            )
                        }
                    } else {
                        // System app-language preferences are authoritative on Android 13 and newer.
                        val actual = UiLanguages.normalize(
                            AppCompatDelegate.getApplicationLocales().toLanguageTags()
                        )
                        if (actual !=
                            runtime.state.options.uiLanguage
                        ) {
                            runtime.configure(runtime.state.options.copy(uiLanguage = actual))
                        }
                    }
                }
            }
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
                environment?.let { current ->
                    MobileScreen(
                        runtime.state,
                        current,
                        ProbeSession.state,
                        ProbeSourceActions.state.available,
                        runtime.preparation,
                        MobileActions(
                            ::configureOptions, controller::search, {
                                runtime.speech.stop()
                                controller.openDetail(it)
                            }, {
                                deletion =
                                    PendingDeletion(it)
                            }, controller::retry,
                            { alert, target, engine ->
                                if (runtime.state.options.localConsent &&
                                    engine == runtime.state.options.engine
                                ) {
                                    if (engine ==
                                        TranslationEngineKind.SYSTEM
                                    ) {
                                        openSystemTranslationSettings()
                                    } else if (engine == TranslationEngineKind.ML_KIT) {
                                        runtime.requestTargetModels(
                                            LocalTarget.entries.first {
                                                it.languageTag ==
                                                    target
                                            },
                                            alert
                                        )
                                    }
                                }
                            }, ::openSettings, {
                                ProbeSourceActions.openFromUser(this)
                            }, ::requestNotifications,
                            notificationsAllowed,
                            controller::older,
                            controller::newest,
                            controller::retryStorage,
                            runtime.systemAvailability,
                            { runtime.systemEngine.refreshCapabilities(force = it) },
                            ::openSystemTranslationSettings,
                            controller::changeDetailTarget,
                            runtime.detailLanguages::identify,
                            runtime.nearby.state,
                            ::requestNearby,
                            runtime.nearby::refresh,
                            runtime.nearby::dismiss,
                            { root, complete ->
                                val original = runtime.nearby.original(root)
                                if (original == null) {
                                    complete(null)
                                } else {
                                    controller.saveNearby(
                                        original,
                                        runtime.nearby.saveAllowance(root)
                                    ) { id ->
                                        if (id != null) runtime.nearby.dismiss(root)
                                        complete(id)
                                    }
                                }
                            },
                            speechState = runtime.speech.state,
                            readAloud = { key, body, language ->
                                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                    runtime.speech.speak(key, body, language)
                                }
                            },
                            stopReading = runtime.speech::stop,
                            modelInventory = runtime.modelInventory,
                            modelPreparation = runtime.preparation,
                            refreshModels = runtime.inventory::refresh,
                            prepareModel = runtime::requestModel,
                            modelDownload = runtime.modelDownload,
                            prepareTargetModels = runtime::requestTargetModels,
                            startModelDownload = runtime::startModelDownload,
                            closeModelDownload = runtime::closeModelDownload,
                            showModelDownload = runtime::showModelDownload
                        )
                    )
                }
                deletion?.let { request ->
                    MobileConfirmationDialog(
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
                                runtime.detailLanguages.clear()
                                runtime.speech.stop()
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
        outState.putBoolean("system_settings_opened", systemSettingsOpened)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            accessObserver
        )
    }

    override fun onResume() {
        super.onResume()
        mobileRuntime.resumeProduct()
        refreshAccess()
        mobileRuntime.nearby.foreground(true)
        mobileRuntime.nearby.recheckAccess()
        ensureNearbyBackground()
        mobileRuntime.controller.start()
        mobileRuntime.controller.refresh()
        if (systemSettingsOpened || mobileRuntime.systemAvailability.loaded ||
            mobileRuntime.state.options.engine == TranslationEngineKind.SYSTEM
        ) {
            mobileRuntime.systemEngine.refreshCapabilities(force = systemSettingsOpened)
        }
        systemSettingsOpened = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isFinishing && !isDestroyed) refreshAccess()
    }

    override fun onPause() {
        systemSettingsRequest++
        super.onPause()
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(accessObserver)
        mobileRuntime.speech.stop()
        mobileRuntime.nearby.foreground(false)
        super.onStop()
    }

    private fun requestNearby() {
        if (!mobileRuntime.state.loaded || mobileRuntime.state.storageError ||
            !mobileRuntime.state.options.labsEnabled
        ) {
            return
        }
        mobileRuntime.configure(mobileRuntime.state.options.copy(nearbyEnabled = true))
        val permissions = NearbyAccess.permissions(mobileRuntime.nearby.sender).filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isEmpty()) {
            mobileRuntime.nearby.refresh()
        } else {
            nearbyRequest.launch(permissions.toTypedArray())
        }
    }

    private fun ensureNearbyBackground() {
        val runtime = mobileRuntime
        if (runtime.state.loaded && !runtime.state.storageError &&
            runtime.state.options.labsEnabled && runtime.state.options.nearbyEnabled &&
            !runtime.nearby.state.backgroundRunning && !runtime.diagnosticsActive() &&
            NearbyAccess.granted(this, runtime.nearby.sender) &&
            NearbyAccess.backgroundGranted(this)
        ) {
            NearbyBackgroundService.start(this)
        }
    }

    private fun refreshAccess() {
        environment = ProbeEnvironment.read(this)
        notificationsAllowed = ResultNotifications(this).allowed()
    }

    private fun configureOptions(options: MobileOptions) {
        val languageChanged = options.uiLanguage != mobileRuntime.state.options.uiLanguage
        mobileRuntime.configure(options)
        if (languageChanged) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(options.uiLanguage)
            )
        }
    }

    private fun openDestination(intent: Intent) {
        ResultNotifications.destination(intent.data)?.let {
            mobileRuntime.speech.stop()
            mobileRuntime.controller.openDetail(it.id, it.target, it.engine)
            intent.data = null
        }
    }

    private fun openSystemTranslationSettings() {
        val request = ++systemSettingsRequest
        val requestedAt = SystemClock.uptimeMillis()
        mobileRuntime.systemEngine.requestSettings { action ->
            if (request == systemSettingsRequest && !isFinishing && !isDestroyed &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                val opened = if (SystemClock.uptimeMillis() - requestedAt <= 10_000 &&
                    action != null
                ) {
                    systemSettingsOpened = true
                    action.invoke()
                } else {
                    false
                }
                if (!opened) {
                    systemSettingsOpened = false
                    Toast.makeText(
                        this,
                        R.string.mobile_system_settings_unavailable,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
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
