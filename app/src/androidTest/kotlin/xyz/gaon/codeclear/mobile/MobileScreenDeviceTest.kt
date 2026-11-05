package xyz.gaon.codeclear.mobile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.DialogWindowProvider
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.gaon.codeclear.BuildConfig
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeEnvironment
import xyz.gaon.codeclear.capture.ProbeRadio
import xyz.gaon.codeclear.capture.ProbeSessionState
import xyz.gaon.codeclear.nearby.NearbyState
import xyz.gaon.codeclear.nearby.NearbyStatus
import xyz.gaon.codeclear.nearby.RelayProtocol
import xyz.gaon.codeclear.reception.CountryEvidence
import xyz.gaon.codeclear.reception.ReceptionCountry
import xyz.gaon.codeclear.translation.ServerAccessState
import xyz.gaon.codeclear.translation.ServerCacheClearResult
import xyz.gaon.codeclear.translation.SystemEngineAvailability
import xyz.gaon.codeclear.translation.SystemLanguagePair
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationTarget
import xyz.gaon.codeclear.translation.serverDigest

/** Renders synthetic-only state. The real runtime is paused and its history is never modified. */
class MobileScreenDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MobileFixtureActivity>()
    private var scope: AutoCloseable? = null
    private var state by mutableStateOf(MobileState())
    private var environment by mutableStateOf(
        ProbeEnvironment(ProbeRadio.PRESENT, setOf("test.system.receiver"), true, true)
    )
    private var configured = MobileOptions()
    private var searched = ""
    private var newestRequests = 0
    private var notificationRequests = 0
    private var fixtureAccessibilityConnected by mutableStateOf(true)
    private var notificationsAllowed by mutableStateOf(true)
    private var settingsOpened = 0
    private var lastSettingsAction = ""
    private var originalLanguageRequests = 0
    private var speechState by mutableStateOf(SpeechState())
    private val copiedBodies = mutableListOf<String>()
    private val sharedBodies = mutableListOf<String>()
    private val spokenMessages = mutableListOf<Pair<String, String>>()
    private var inventory by mutableStateOf(
        ModelInventoryState(loaded = true, downloaded = primaryModelLanguages)
    )
    private var modelPreparation by mutableStateOf(PreparationState())
    private val requestedModels = mutableListOf<String>()
    private var modelDownload by mutableStateOf<ModelDownloadRequest?>(null)
    private var originalLanguageReply: ((String?) -> Unit)? = null
    private var nearby by mutableStateOf(NearbyState())
    private var nearbyRequests = 0
    private var nearbySaves = 0
    private var deletionRequests = 0
    private var deletedAlertId: Long? = null
    private var prepared = 0
    private var preparedEngine: TranslationEngineKind? = null
    private var retriedEngine: TranslationEngineKind? = null
    private var retried = 0
    private var fontScale by mutableStateOf(1f)
    private var densityScale by mutableStateOf(1f)
    private var previewLanguage by mutableStateOf<String?>("en")
    private var systemSupport by mutableStateOf(SystemEngineAvailability(loaded = true))
    private val projectLinks = mutableListOf<String>()
    private var projectLinkFailure = false
    private var manualSupportRefreshes = 0
    private var providerSettingsOpened = 0
    private var overlaySettingsOpened = 0
    private var batterySettingsOpened = 0
    private var olderObservationRequests = 0
    private var newestObservationRequests = 0
    private var presentationAccess by mutableStateOf(PresentationAccess())
    private var serverAccess by mutableStateOf(ServerAccessState())
    private var serverAllows = 0
    private var serverRemovals = 0
    private var serverCacheClears = 0
    private var globalPreparation by mutableStateOf(PreparationState())
    private val serverTokens = mutableListOf<String>()
    private val context get() = compose.activity
    private val fixtureResources
        get() = localizedFixtureContext(context, context.resources.configuration).resources
    private fun text(id: Int) = fixtureResources.getString(id)
    private fun text(id: Int, vararg arguments: Any) = fixtureResources.getString(id, *arguments)

    private fun localizedFixtureContext(base: Context, configuration: Configuration): Context =
        previewLanguage?.let {
            base.createConfigurationContext(
                Configuration(configuration).apply { setLocale(Locale.forLanguageTag(it)) }
            )
        } ?: base

    @Before
    fun renderOnlySyntheticPrivateFixtures() {
        compose.runOnUiThread {
            scope = context.mobileRuntime.pauseForDiagnostics()
            context.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val record = record(1)
            state =
                MobileState(
                    loaded = true,
                    options = MobileOptions(
                        target = TranslationTarget.KOREAN,
                        localConsent = true,
                        setupComplete = true,
                        messageViewMode = MessageViewMode.BOTH
                    ),
                    latest = record,
                    records = listOf(record)
                )
        }
        compose.setContent {
            val density = LocalDensity.current
            val dark = state.options.theme == ThemeChoice.DARK ||
                (state.options.theme == ThemeChoice.SYSTEM && isSystemInDarkTheme())
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                context.enableEdgeToEdge(
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
            val base = LocalContext.current
            val configuration = LocalConfiguration.current
            val localized = remember(base, configuration, previewLanguage) {
                localizedFixtureContext(base, configuration)
            }
            val uriHandler = remember {
                object : UriHandler {
                    override fun openUri(uri: String) {
                        projectLinks += uri
                        if (projectLinkFailure) {
                            throw IllegalArgumentException(
                                "No browser in fixture"
                            )
                        }
                    }
                }
            }
            CompositionLocalProvider(
                LocalUriHandler provides uriHandler,
                LocalDensity provides Density(density.density * densityScale, fontScale),
                LocalContext provides localized,
                LocalResources provides localized.resources,
                LocalConfiguration provides localized.resources.configuration
            ) {
                MobileScreen(
                    state,
                    environment,
                    ProbeSessionState(
                        notificationConnected = true,
                        accessibilityConnected = fixtureAccessibilityConnected
                    ),
                    false,
                    globalPreparation,
                    MobileActions(
                        olderObservations = { olderObservationRequests++ },
                        newestObservations = { newestObservationRequests++ },
                        presentationAccess = presentationAccess,
                        overlaySettings = { overlaySettingsOpened++ },
                        batterySettings = { batterySettingsOpened++ },
                        serverAccess = serverAccess,
                        allowServer = { revision, id ->
                            if (state.options.labsEnabled && serverAccess.ready &&
                                !serverAccess.busy &&
                                serverAccess.revision == revision &&
                                serverAccess.credentialId == id
                            ) {
                                serverAllows++
                                configured =
                                    state.options.copy(
                                        engine = TranslationEngineKind.OWNED_SERVER,
                                        ownedServerAllowed = true,
                                        ownedServerCredentialId = id
                                    )
                                state = state.copy(options = configured!!)
                            }
                        },
                        saveServerAccess = { value, revision ->
                            if (serverAccess.revision == revision) {
                                serverTokens += value
                                serverAccess =
                                    serverAccess.copy(
                                        ready = true,
                                        credentialId = serverDigest(value),
                                        revision = revision + 1
                                    )
                                state =
                                    state.copy(
                                        options = state.options.copy(
                                            ownedServerAllowed = false,
                                            ownedServerCredentialId = ""
                                        )
                                    )
                            }
                        },
                        removeServerAccess = { revision ->
                            if (serverAccess.revision == revision) {
                                serverRemovals++
                                serverAccess =
                                    serverAccess.copy(
                                        ready = false,
                                        credentialId = "",
                                        revision =
                                            revision + 1
                                    )
                                state =
                                    state.copy(
                                        options = state.options.copy(
                                            ownedServerAllowed = false,
                                            ownedServerCredentialId = ""
                                        )
                                    )
                            }
                        },
                        clearServerCache = { revision ->
                            if (serverAccess.revision == revision) {
                                serverCacheClears++
                                serverAccess = serverAccess.copy(
                                    revision = revision + 1,
                                    cacheResult = ServerCacheClearResult.CLEARED
                                )
                                state =
                                    state.copy(
                                        options = state.options.copy(
                                            ownedServerAllowed = false,
                                            ownedServerCredentialId = ""
                                        )
                                    )
                            }
                        },
                        configure = {
                            configured = it
                            state = state.copy(options = it)
                            if (!it.nearbyEnabled) nearby = NearbyState()
                        },
                        search = {
                            searched = it
                            state = state.copy(query = it)
                        },
                        detail = { id ->
                            val selected = id?.let { key ->
                                state.records.firstOrNull { it.id == key }
                                    ?: state.latest?.takeIf { it.id == key }
                                    ?: record(key)
                            }
                            state = state.copy(detailId = id, detail = selected)
                        },
                        delete = {
                            deletionRequests++
                            deletedAlertId = it
                        }, retry = { _, _, engine ->
                            retried++
                            retriedEngine = engine
                        }, prepare = { _, _, engine ->
                            prepared++
                            preparedEngine = engine
                        }, settings = {
                            settingsOpened++
                            lastSettingsAction = it
                        }, openSource = {
                        }, notificationPermission = {
                            notificationRequests++
                        }, notificationsAllowed = notificationsAllowed, older = {
                        }, newest = {
                            newestRequests++
                            state = state.copy(beforeId = Long.MAX_VALUE)
                        }, retryStorage = {},
                        systemAvailability = systemSupport,
                        refreshSystem = { if (it) manualSupportRefreshes++ },
                        systemSettings = { providerSettingsOpened++ },
                        changeDetailTarget = { id, target, source ->
                            if (id == state.detailId) {
                                state =
                                    state.copy(
                                        detailTarget = target,
                                        detailSourceLanguage = source
                                    )
                            }
                        },
                        identifyOriginal = { _, complete ->
                            originalLanguageRequests++
                            originalLanguageReply = complete
                            AutoCloseable {}
                        },
                        nearby = nearby,
                        nearbyPermission = {
                            nearbyRequests++
                            configured = state.options.copy(nearbyEnabled = true)
                            state = state.copy(options = configured)
                            nearby = nearby.copy(status = NearbyStatus.LISTENING)
                        },
                        dismissNearby = { root ->
                            nearby =
                                nearby.copy(
                                    originals = nearby.originals.filterNot {
                                        it.root == root
                                    }
                                )
                        },
                        saveNearby = { root, complete ->
                            val original = nearby.originals.first { it.root == root }
                            nearbySaves++
                            val saved = record(999).copy(
                                capture = CapturedAlert(
                                    "xyz.gaon.codeclear.nearby",
                                    CaptureSource.PEER,
                                    original.title,
                                    original.body,
                                    System.currentTimeMillis(),
                                    sourceIdentity = root
                                ),
                                translation = null
                            )
                            state = state.copy(records = listOf(saved) + state.records)
                            nearby = nearby.copy(originals = emptyList())
                            complete(saved.id)
                        },
                        copyBody = { copiedBodies.add(it) },
                        shareBody = { sharedBodies.add(it) },
                        speechState = speechState,
                        readAloud = { key, body, language ->
                            spokenMessages.add(body to language)
                            speechState = SpeechState(SpeechPhase.PLAYING, key)
                        },
                        stopReading = { speechState = SpeechState() },
                        modelInventory = inventory,
                        modelPreparation = modelPreparation,
                        prepareModel = {
                            modelDownload = ModelDownloadRequest(
                                setOf(it),
                                checkUpdates = it in inventory.downloaded
                            )
                        },
                        modelDownload = modelDownload,
                        prepareTargetModels = { target, alert ->
                            val source =
                                state.detailSourceLanguage ?: alert?.translation?.sourceLanguage
                            modelDownload = ModelDownloadRequest(
                                (setOfNotNull(source, target.modelCode) - "en"),
                                alert?.capture?.original,
                                target.takeIf { alert != null },
                                sourceKnown = alert == null || source != null
                            )
                        },
                        startModelDownload = {
                            val request = modelDownload!!
                            val completed = if (request.checkUpdates) {
                                emptySet()
                            } else {
                                inventory.downloaded.intersect(request.languages)
                            }
                            requestedModels.addAll(request.languages - completed)
                            modelPreparation = PreparationState(
                                PreparationPhase.WAITING_FOR_WIFI,
                                languages = request.languages,
                                completedLanguages = completed,
                                activeLanguage = (request.languages - completed).firstOrNull(),
                                checkUpdates = request.checkUpdates
                            )
                            modelDownload =
                                request.copy(started = true, body = null, target = null)
                        },
                        closeModelDownload = {
                            modelDownload =
                                if (modelPreparation.phase ==
                                    PreparationPhase.WAITING_FOR_WIFI
                                ) {
                                    modelDownload?.copy(visible = false)
                                } else {
                                    null
                                }
                        },
                        showModelDownload = {
                            modelDownload =
                                modelDownload?.copy(visible = true)
                        }

                    )
                )
            }
        }
    }

    @After
    fun restoreSecureWindowAndReleaseDiagnosticScope() {
        compose.runOnUiThread {
            context.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            scope?.close()
        }
    }

    @Test
    fun firstRunExplainsTheAppAndPermissionsBeforeOptionalModelPreparation() {
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            state = state.copy(options = state.options.copy(setupComplete = false))
            notificationsAllowed = false
        }
        compose.onNodeWithTag("destination-0").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_intro_purpose)).assertIsDisplayed()
        savePreview("initial-guidance")
        compose.onNodeWithText(text(R.string.mobile_next)).performScrollTo().performClick()
        compose.onNodeWithText(
            text(R.string.mobile_notification_permission_reason)
        ).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_result_notifications)).performClick()
        compose.runOnIdle {
            assertEquals(1, notificationRequests)
            notificationsAllowed = true
        }
        compose.onNodeWithText(text(R.string.mobile_next)).performScrollTo().performClick()
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasTestTag("model-management"))
        compose.runOnIdle {
            assertTrue(requestedModels.isEmpty())
            assertEquals(1, notificationRequests)
        }
        compose.onNodeWithText(text(R.string.mobile_done)).performScrollTo().performClick()
        compose.onNodeWithTag("destination-0").assertIsDisplayed()
        compose.runOnIdle { assertTrue(configured.setupComplete) }
    }

    @Test
    fun missingRequiredAccessMasksExistingDetailsAndRestoresNavigationAfterRealGrantChanges() {
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            state = state.copy(detailId = 1, detail = record(1))
            environment = environment.copy(accessibilityGranted = false)
        }
        compose.onNodeWithTag("required-access").assertIsDisplayed()
        compose.onNodeWithTag("alert-modal-card").assertDoesNotExist()
        compose.onNodeWithTag("destination-0").assertDoesNotExist()
        savePreview("required-access")
        compose.onNodeWithText(
            text(R.string.mobile_accessibility_access)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_open_settings)).performClick()
        compose.runOnIdle {
            assertEquals(1, settingsOpened)
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, lastSettingsAction)
        }
        compose.onNodeWithTag("required-access").assertIsDisplayed()
        compose.runOnIdle { environment = environment.copy(accessibilityGranted = true) }
        compose.onNodeWithTag("required-access").assertDoesNotExist()
        compose.onNodeWithTag("alert-modal-card").assertIsDisplayed()
    }

    @Test
    fun reconnectingAccessibilityKeepsItsGrantAndOffersUserControlledRecovery() {
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(accessibilityConsent = true))
        }
        // The synthetic OS grant remains enabled while its service is not connected.
        compose.runOnIdle { fixtureAccessibilityConnected = false }
        openSettingsPage(SettingsPage.CAPTURE)
        compose.onNodeWithText(
            text(R.string.mobile_accessibility_access)
        ).performScrollTo().assertIsOn()
        compose.onNodeWithTag("required-access").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_open_settings)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, lastSettingsAction)
            assertTrue(state.options.accessibilityConsent)
            environment = environment.copy(accessibilityGranted = false)
        }
        compose.onNodeWithTag("required-access").assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.mobile_accessibility_restart_notice)
        ).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(state.options.accessibilityConsent) }
    }

    @Test
    fun wifiOnlyHardwareNeedsNotificationsButNeverRequiresCellularCaptureAccess() {
        compose.runOnIdle {
            environment =
                environment.copy(
                    radio = ProbeRadio.ABSENT,
                    notificationGranted = false,
                    accessibilityGranted = false
                )
        }
        compose.onNodeWithTag("destination-0").assertIsDisplayed()
        compose.runOnIdle { notificationsAllowed = false }
        compose.onNodeWithTag("required-access").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_notification_access)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_accessibility_access)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_result_notifications)).performClick()
        compose.runOnIdle {
            assertEquals(1, notificationRequests)
            notificationsAllowed = true
        }
        compose.onNodeWithTag("destination-0").assertIsDisplayed()
    }

    @Test
    fun modelManagementShowsInstalledStateAndOnlyDownloadsAfterAnExplicitAction() {
        compose.runOnIdle { inventory = inventory.copy(downloaded = setOf("ko", "es")) }
        compose.runOnIdle { context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithTag("settings-detail").performScrollToNode(hasTestTag("model-es"))
        val spanish = Locale.forLanguageTag(
            "es"
        ).getDisplayLanguage(fixtureResources.configuration.locales[0])
        compose.onNode(hasText(spanish) and hasAnyAncestor(hasTestTag("model-es"))).performClick()
        compose.onNodeWithTag("model-download-dialog").assertExists()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.onNodeWithTag("settings-detail").performScrollToNode(hasTestTag("model-ko"))
        savePreview("model-management")
        compose.onNode(hasText("한국어") and hasAnyAncestor(hasTestTag("model-ko"))).performClick()
        compose.onNodeWithTag("model-download-dialog").assertExists()
        compose.onNodeWithText(text(R.string.mobile_model_update_review)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_model_size_estimate, 30)).assertDoesNotExist()
        savePreview("model-update-review", dialog = true)
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.runOnIdle { assertTrue(requestedModels.isEmpty()) }
        compose.onNode(hasText("한국어") and hasAnyAncestor(hasTestTag("model-ko"))).performClick()
        compose.onNodeWithText(text(R.string.mobile_models_check_updates)).performClick()
        compose.runOnIdle { assertEquals(listOf("ko"), requestedModels) }
        compose.onNodeWithTag("model-download-close").performClick()
        compose.onNodeWithTag("settings-detail").performScrollToNode(hasTestTag("model-ja"))
        compose.onNode(
            hasText("日本語") and hasAnyAncestor(hasTestTag("model-ja"))
        ).assertIsNotEnabled()
        compose.runOnIdle {
            modelPreparation =
                PreparationState(PreparationPhase.READY, modelLanguage = "ko")
        }
        compose.onNode(hasText("日本語") and hasAnyAncestor(hasTestTag("model-ja"))).performClick()
        compose.onNodeWithText(text(R.string.mobile_prepare)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_models_download)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
    }

    @Test
    fun missingDetailLanguageOpensDownloadReviewAndShowsCompletedPackCounts() {
        compose.runOnIdle {
            inventory = inventory.copy(downloaded = setOf("ko"))
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithText(
            text(R.string.mobile_target_language)
        ).performScrollTo().performClick()
        compose.onNode(hasText("日本語") and isRadioChoice()).performScrollTo().performClick()
        compose.onNodeWithTag("model-download-dialog").assertExists()
        compose.runOnIdle { assertTrue(requestedModels.isEmpty()) }
        savePreview("model-download-review", dialog = true)
        compose.onNodeWithTag("model-download-confirm").performClick()
        compose.onNodeWithTag("model-download-count").assertTextEquals(
            text(R.string.mobile_model_progress_count, 1, 2)
        )
        compose.runOnIdle { assertEquals(listOf("ja"), requestedModels) }
        savePreview("model-download-progress", dialog = true)
        compose.onNodeWithTag("model-download-close").performClick()
        compose.runOnIdle {
            inventory = inventory.copy(downloaded = setOf("ko", "ja"))
            modelPreparation = modelPreparation.copy(
                phase = PreparationPhase.READY,
                completedLanguages = setOf("ko", "ja"),
                activeLanguage = null
            )
            modelDownload = modelDownload?.copy(visible = true)
        }
        compose.onNodeWithTag(
            "model-download-status"
        ).assertTextEquals(text(R.string.mobile_models_ready))
        compose.onNodeWithTag("model-download-count").assertTextEquals(
            text(R.string.mobile_model_progress_count, 2, 2)
        )
        compose.runOnIdle { assertEquals(listOf("ja"), requestedModels) }
    }

    @Test
    fun missingDefaultLanguageCanBeSelectedButDownloadRequiresConfirmation() {
        compose.runOnIdle { inventory = inventory.copy(downloaded = setOf("ko")) }
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithText(
            text(R.string.mobile_target_language)
        ).performScrollTo().performClick()
        compose.onNode(hasText("日本語") and isRadioChoice()).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(TranslationTarget.JAPANESE, configured.target)
            assertTrue(requestedModels.isEmpty())
        }
        compose.onNodeWithTag("model-download-dialog").assertExists()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.runOnIdle { assertTrue(requestedModels.isEmpty()) }
    }

    @Test
    fun tutorialIncludesOptionalModelPreparationWithoutStartingDownloads() {
        openSettingsPage(SettingsPage.CAPTURE)
        compose.onNodeWithText(text(R.string.mobile_tutorial)).performScrollTo().performClick()
        repeat(2) {
            compose.onNodeWithText(text(R.string.mobile_next)).performScrollTo().performClick()
        }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasTestTag("model-management"))
        compose.onNodeWithText(text(R.string.mobile_models_update_notice)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(requestedModels.isEmpty()) }
        compose.onNodeWithText(text(R.string.mobile_done)).performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(configured.setupComplete)
            assertTrue(requestedModels.isEmpty())
        }
    }

    @Test
    fun speechReadsTheDisplayedMessageAndStopsWhenSwitchingOrLeaving() {
        compose.runOnIdle {
            state =
                state.copy(options = state.options.copy(messageViewMode = MessageViewMode.SWITCH))
        }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasTestTag("speech-translation"))
        compose.onNodeWithTag("speech-translation").performClick()
        compose.runOnIdle {
            assertEquals(state.latest!!.translation!!.text to "ko", spokenMessages.last())
            assertEquals(SpeechPhase.PLAYING, speechState.phase)
        }
        compose.onNodeWithTag("switch-message").performClick()
        compose.runOnIdle { assertEquals(SpeechPhase.IDLE, speechState.phase) }
        compose.onNodeWithTag("speech-original").performClick()
        compose.runOnIdle {
            assertEquals(state.latest!!.capture.original to "ko", spokenMessages.last())
        }
        compose.onNodeWithTag("destination-2").performClick()
        compose.runOnIdle { assertEquals(SpeechPhase.IDLE, speechState.phase) }
    }

    @Test
    fun abandonedOriginalLanguageIdentificationCannotStartSpeech() {
        compose.runOnIdle {
            state = state.copy(
                latest = state.latest!!.copy(translation = null),
                options = state.options.copy(messageViewMode = MessageViewMode.SWITCH)
            )
        }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasTestTag("speech-original"))
        compose.onNodeWithTag("speech-original").performClick()
        compose.runOnIdle { assertEquals(1, originalLanguageRequests) }
        compose.onNodeWithTag("destination-2").performClick()
        compose.runOnIdle {
            originalLanguageReply!!("en")
            assertTrue(spokenMessages.isEmpty())
        }
    }

    @Test
    fun singleMessageViewStartsWithTranslationAndSwitchesToTheOriginal() {
        val original = state.latest!!.capture.original
        val translated = state.latest!!.translation!!.text!!
        compose.runOnIdle {
            state =
                state.copy(options = state.options.copy(messageViewMode = MessageViewMode.SWITCH))
        }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(translated))
        compose.onNodeWithText(translated).assertIsDisplayed()
        compose.onNodeWithText(original).assertDoesNotExist()
        compose.onNodeWithTag("switch-message").performScrollTo().performClick()
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(original))
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onNodeWithText(translated).assertDoesNotExist()
        compose.onNodeWithTag("switch-message").performScrollTo().performClick()
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(translated))
        compose.onNodeWithText(translated).assertIsDisplayed()
        openSettingsPage(SettingsPage.APPEARANCE)
        compose.onNodeWithText(text(R.string.mobile_message_view)).performClick()
        compose.onNodeWithText(text(R.string.mobile_message_both)).performClick()
        assertEquals(MessageViewMode.BOTH, configured.messageViewMode)
    }

    @Test
    fun singleMessageViewExposesChangedScriptButAvoidsAnIdenticalSameLanguageResult() {
        val original = "【演练】请远离车站，注意安全。"
        val converted = "【演練】請遠離車站，注意安全。"
        val alert = record(1).let {
            it.copy(
                capture = it.capture.copy(original = original),
                translation = SavedTranslation(
                    TranslationPhase.READY,
                    converted,
                    "zh",
                    sameLanguage = true
                )
            )
        }
        compose.runOnIdle {
            state = state.copy(
                latest = alert,
                options = state.options.copy(
                    target = TranslationTarget.TRADITIONAL_CHINESE,
                    messageViewMode = MessageViewMode.SWITCH
                )
            )
        }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(converted))
        compose.onNodeWithText(converted).assertIsDisplayed()
        compose.onNodeWithText(original).assertDoesNotExist()
        compose.onNodeWithTag("speech-translation").performClick()
        compose.runOnIdle { assertEquals(converted to "zh-Hant", spokenMessages.last()) }
        compose.onNodeWithTag("switch-message").performClick()
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(original))
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onNodeWithText(converted).assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(
                latest = alert.copy(translation = alert.translation!!.copy(text = original))
            )
        }
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onNodeWithTag("switch-message").assertDoesNotExist()
    }

    @Test
    fun singleMessageViewKeepsTheOriginalReadableWhenTranslationIsUnavailable() {
        val original = state.latest!!.capture.original
        compose.runOnIdle {
            state = state.copy(
                options = state.options.copy(messageViewMode = MessageViewMode.SWITCH),
                latest = state.latest!!.copy(translation = null)
            )
        }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(original))
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onNodeWithTag("switch-message").assertDoesNotExist()
        val translated = record(1).translation!!.text!!
        compose.runOnIdle { state = state.copy(latest = record(1)) }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(translated))
        compose.onNodeWithText(translated).assertIsDisplayed()
        compose.onNodeWithText(original).assertDoesNotExist()
    }

    @Test
    fun alertModalStaysWithinTheScreenAndLeavesManagementToTheDetailPage() {
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            state =
                state.copy(options = state.options.copy(messageViewMode = MessageViewMode.SWITCH))
        }
        compose.onNodeWithTag(
            "mobile-feed"
        ).performScrollToNode(hasText(text(R.string.mobile_details)))
        compose.onNodeWithText(text(R.string.mobile_details)).performClick()
        val bounds = compose.onNodeWithTag("alert-modal-card").fetchSemanticsNode().boundsInRoot
        assertTrue(
            bounds.height < context.windowManager.maximumWindowMetrics.bounds.height() * 0.8f
        )
        compose.onNodeWithTag("alert-modal-close").assertIsDisplayed()
        compose.onNode(
            hasText(state.latest!!.translation!!.text!!) and
                hasAnyAncestor(hasTestTag("alert-modal-card"))
        ).assertIsDisplayed()
        compose.onNodeWithTag("delete-alert").assertDoesNotExist()
        compose.onNodeWithTag("alert-metadata").assertDoesNotExist()
        val labelLayouts = mutableListOf<TextLayoutResult>()
        compose.onNode(
            hasText(
                text(R.string.mobile_translation, languageName(state.options.target.languageTag))
            ) and
                hasAnyAncestor(hasTestTag("alert-modal-card"))
        ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(labelLayouts) }
        assertEquals(1, labelLayouts.single().lineCount)
        savePreview("compact-alert-modal", dialog = true)
        compose.onNodeWithTag("alert-modal-card").performTouchInput { click(center) }
        compose.onNodeWithTag("alert-modal-card").assertIsDisplayed()
        compose.onNodeWithTag("alert-modal-backdrop").performTouchInput {
            click(Offset(2f, 2f))
        }
        compose.onNodeWithTag("alert-modal-card").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_details)).performClick()
        compose.onNodeWithTag("alert-modal-details").performClick()
        compose.onNodeWithTag("history-detail").assertIsDisplayed()
        compose.onNodeWithTag("delete-alert").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun originalAndTranslationActionsUseTheirOwnBodiesWithoutDisplayingTheSourcePackage() {
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        val original = state.detail!!.capture.original
        val translated = state.detail!!.translation!!.text!!
        val originalMenu = text(R.string.mobile_message_actions, text(R.string.mobile_original))
        val translatedMenu = text(
            R.string.mobile_message_actions,
            text(R.string.mobile_translation, languageName("ko"))
        )
        for ((menu, body) in listOf(originalMenu to original, translatedMenu to translated)) {
            compose.onNodeWithContentDescription(menu).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.mobile_copy_body)).performClick()
            compose.runOnIdle { assertEquals(body, copiedBodies.last()) }
            compose.onNodeWithContentDescription(menu).performClick()
            compose.onNodeWithText(text(R.string.mobile_share_body)).performClick()
            compose.runOnIdle { assertEquals(body, sharedBodies.last()) }
        }
        compose.onNodeWithText(state.detail!!.capture.sourcePackage, substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun verifiedKoreanCategoryAppearsAndUnverifiedPeerCategoryStaysHidden() {
        val original = record(1).let {
            it.copy(
                capture = it.capture.copy(
                    context = ReceptionContext(ReceptionCountry("KR", CountryEvidence.CBS_PLMN)),
                    broadcast = BroadcastMetadata(1, 42, 4372, 3, 1, null)
                )
            )
        }
        compose.runOnIdle { state = state.copy(latest = original) }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasTestTag("national-alert-type"))
        compose.onNodeWithTag("national-alert-type").assertIsDisplayed()
        compose.onNodeWithTag("alert-symbol-safety").assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(
                latest = original.copy(capture = original.capture.copy(source = CaptureSource.PEER))
            )
        }
        compose.onNodeWithTag("national-alert-type").assertDoesNotExist()
        compose.onNodeWithTag("estimated-alert-type").assertDoesNotExist()
    }

    @Test
    fun reviewedTitleEstimatesShowDistinctHistorySymbolsInBothThemes() {
        val hints = listOf("EXTREME", "SEVERE", "AMBER", "PUBLIC_SAFETY", "TEST", "UNKNOWN")
        val symbols = listOf("critical", "urgent", "missing", "safety", "test", "general")
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            state = state.copy(
                records = hints.mapIndexed { index, hint ->
                    record(index + 1L).let {
                        it.copy(
                            capture = it.capture.copy(
                                sourcePackage = "com.google.android.cellbroadcastreceiver",
                                headingHint = hint
                            )
                        )
                    }
                }
            )
        }
        compose.onNodeWithTag("destination-1").performClick()
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            hints.indices.forEach { index ->
                compose.onNodeWithTag(
                    "mobile-feed"
                ).performScrollToNode(hasTestTag("history-row-${index + 1}"))
                compose.onNodeWithTag(
                    "alert-symbol-${symbols[index]}",
                    useUnmergedTree = true
                ).assertIsDisplayed()
                val estimate = compose.onNode(
                    hasTestTag("estimated-alert-type") and
                        hasAnyAncestor(hasTestTag("history-row-${index + 1}")),
                    useUnmergedTree = true
                )
                if (index ==
                    hints.lastIndex
                ) {
                    estimate.assertDoesNotExist()
                } else {
                    estimate.assertIsDisplayed()
                }
                compose.onNodeWithTag("national-alert-type").assertDoesNotExist()
            }
            compose.onNodeWithTag("mobile-feed").performScrollToNode(hasTestTag("history-row-1"))
            savePreview("alert-symbols-${theme.name.lowercase()}")
        }
    }

    @Test
    fun detailTitleEstimateYieldsToStructuredOfficialEvidence() {
        compose.runOnIdle {
            state = state.copy(
                records = listOf(
                    record(1).let {
                        it.copy(
                            capture = it.capture.copy(
                                sourcePackage = "com.google.android.cellbroadcastreceiver",
                                headingHint = "AMBER"
                            )
                        )
                    }
                )
            )
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithTag("estimated-alert-type").assertIsDisplayed()
        compose.onNodeWithTag("alert-symbol-missing", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            val detail = state.detail!!
            state = state.copy(
                detail = detail.copy(
                    capture = detail.capture.copy(
                        context = ReceptionContext(
                            ReceptionCountry("KR", CountryEvidence.CBS_PLMN)
                        ),
                        broadcast = BroadcastMetadata(1, 42, 4371, 3, 1, null)
                    )
                )
            )
        }
        compose.onNodeWithTag("national-alert-type").assertIsDisplayed()
        compose.onNodeWithTag("estimated-alert-type").assertDoesNotExist()
        compose.onNodeWithTag("alert-symbol-urgent", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun storedCountryUsesAFlagAndTheCurrentDisplayLanguage() {
        val country = ReceptionCountry("KR", CountryEvidence.CBS_PLMN)
        compose.runOnIdle {
            previewLanguage = "fr"
            state = state.copy(
                records = listOf(
                    record(1).let {
                        it.copy(capture = it.capture.copy(context = ReceptionContext(country)))
                    }
                )
            )
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithTag("reception-country").performScrollTo()
            .assertTextContains(country.flag + " " + country.displayName("fr"), substring = true)
        compose.runOnIdle { previewLanguage = "de" }
        compose.onNodeWithTag("reception-country")
            .assertTextContains(country.flag + " " + country.displayName("de"), substring = true)
    }

    @Test
    fun nearbySharingRequiresAnExplicitAllowanceAndCanBeTurnedOff() {
        openSettingsPage(SettingsPage.SHARING)
        compose.runOnIdle { state = state.copy(loaded = false) }
        switchControl(text(R.string.mobile_nearby_allow)).assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(loaded = true) }
        switchControl(text(R.string.mobile_nearby_allow)).assertIsOff().performClick()
        compose.runOnIdle { assertEquals(0, nearbyRequests) }
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        switchControl(text(R.string.mobile_nearby_allow)).assertIsOff().performClick()
        compose.onNodeWithText(text(R.string.mobile_agree_continue)).performClick()
        compose.runOnIdle {
            assertEquals(1, nearbyRequests)
            assertTrue(configured.nearbyEnabled)
            assertEquals(0, prepared)
        }
        switchControl(text(R.string.mobile_nearby_allow)).assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertFalse(configured.nearbyEnabled) }
    }

    @Test
    fun nearbyPermissionRetriesAndSettingsRequireTheDisclosureAgain() {
        openSettingsPage(SettingsPage.SHARING)
        compose.runOnIdle { nearby = nearby.copy(status = NearbyStatus.PERMISSION_REQUIRED) }
        compose.onNodeWithText(text(R.string.mobile_allow)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, nearbyRequests) }
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, nearbyRequests) }
        compose.onNodeWithText(text(R.string.mobile_open_settings)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, settingsOpened) }
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.onNodeWithText(text(R.string.mobile_open_settings)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_agree_continue)).performClick()
        compose.runOnIdle {
            assertEquals(1, settingsOpened)
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, lastSettingsAction)
            assertEquals(0, nearbyRequests)
            assertFalse(configured.nearbyEnabled)
        }
    }

    @Test
    fun retainedNearbyOriginalOpensHistoryWithoutAnotherSaveRequest() {
        val now = System.currentTimeMillis()
        val original = RelayProtocol.decode(
            RelayProtocol.encode("[DRILL] Stored nearby", "[DRILL] Saved original", now),
            now
        )!!
        compose.runOnIdle {
            val saved = record(999).let {
                it.copy(
                    capture = it.capture.copy(
                        source = CaptureSource.PEER,
                        title = original.title,
                        original = original.body
                    )
                )
            }
            state = state.copy(records = listOf(saved) + state.records)
            nearby = NearbyState(
                NearbyStatus.RECEIVE_ONLY,
                listOf(original),
                mapOf(original.root to saved.id)
            )
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_nearby_inbox)).performClick()
        compose.onNodeWithText(original.title).performClick()
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("nearby-inbox")))
            .performScrollToNode(hasTestTag("save-nearby"))
        compose.onNodeWithTag("save-nearby").performClick()
        compose.onNodeWithTag("history-detail").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, nearbySaves) }
    }

    @Test
    fun nearbyInboxUsesChildPagesAndOnlySavesAfterTheUserRequestsIt() {
        val now = System.currentTimeMillis()
        val original = RelayProtocol.decode(
            RelayProtocol.encode(
                "[DRILL] Nearby received",
                "[DRILL] Temporary original 👩‍🚒",
                now
            ),
            now
        )!!
        compose.runOnIdle {
            previewLanguage = "ko"
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            state = state.copy(options = state.options.copy(nearbyEnabled = true))
            nearby = NearbyState(NearbyStatus.RECEIVE_ONLY, listOf(original))
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_nearby_inbox)).performClick()
        compose.onNodeWithTag("nearby-inbox").assertIsDisplayed()
        compose.onNodeWithText(original.title).performClick()
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("nearby-inbox")))
            .performScrollToNode(hasText(original.body))
        compose.onNodeWithText(original.body).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, nearbySaves) }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("nearby-inbox").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_nearby_inbox)).performClick()
        compose.onNodeWithText(original.title).performClick()
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            savePreview("nearby-original-${theme.name.lowercase()}")
        }
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("nearby-inbox")))
            .performScrollToNode(hasTestTag("save-nearby"))
        compose.onNodeWithTag("save-nearby").performClick()
        compose.onNodeWithTag("history-detail").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, nearbySaves)
            assertEquals(1L, state.latest!!.id)
            assertEquals(CaptureSource.PEER, state.detail!!.capture.source)
            assertEquals(0, prepared)
        }
    }

    @Test
    fun settingsShowSelectedRadioChoicesAndKeepNotificationPermissionSystemControlled() {
        openSettingsPage(SettingsPage.APPEARANCE)
        compose.onNodeWithText(text(R.string.mobile_screen_mode)).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.mobile_system)) and isRadioChoice()).assertIsSelected()
        compose.onNode(
            hasText(text(R.string.mobile_light)) and isRadioChoice()
        ).assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(ThemeChoice.LIGHT, configured.theme) }
        compose.onNodeWithText(text(R.string.mobile_screen_mode)).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.mobile_light)) and isRadioChoice()).assertIsSelected()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        openSettingsPage(SettingsPage.NOTIFICATIONS)
        switchControl(
            text(R.string.mobile_result_notifications)
        ).performScrollTo().assertIsOn().performClick()
        compose.runOnIdle {
            assertEquals(1, notificationRequests)
            assertEquals(0, prepared)
        }
        switchControl(text(R.string.mobile_result_notifications)).assertIsOn()
        compose.runOnIdle { notificationsAllowed = false }
        compose.onNodeWithTag("required-access").assertIsDisplayed()
        compose.onNodeWithTag("destination-2").assertDoesNotExist()
        compose.runOnIdle { notificationsAllowed = true }
        switchControl(text(R.string.mobile_result_notifications)).assertIsOn()
    }

    @Test
    fun swipingRevealsTwoActionsWithoutDeletingAndSharingClosesTheRow() {
        compose.runOnIdle { context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("history-share-1").assertIsDisplayed()
        compose.onNodeWithTag("history-delete-1").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, deletionRequests)
            assertEquals(null, state.detailId)
        }
        savePreview("history-actions-revealed")
        compose.onNodeWithTag("history-share-1").performClick()
        compose.onNodeWithTag("history-share-1").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, sharedBodies.size)
            assertTrue(sharedBodies.single().contains(state.records.first().capture.original))
            assertTrue(sharedBodies.single().contains(state.records.first().translation!!.text!!))
        }
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithTag("history-detail").assertIsDisplayed()
    }

    @Test
    fun longPressActionsShareDeleteAndCancelWithoutOpeningDetails() {
        compose.runOnIdle { context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performTouchInput { longClick() }
        compose.onNodeWithTag("history-action-menu").assertIsDisplayed()
        savePreview("history-actions-menu", dialog = true)
        compose.onNodeWithText(text(R.string.mobile_close)).performClick()
        compose.onNodeWithTag("history-action-menu").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, deletionRequests)
            assertEquals(null, state.detailId)
        }
        compose.onNodeWithTag("history-row-1").performTouchInput { longClick() }
        compose.onNode(
            hasText(text(R.string.mobile_delete)) and
                hasAnyAncestor(hasTestTag("history-action-menu"))
        ).performClick()
        compose.onNodeWithTag("history-action-menu").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, deletionRequests)
            assertEquals(1L, deletedAlertId)
        }
        compose.onNodeWithTag("history-row-1").performTouchInput { longClick() }
        compose.onNode(
            hasText(text(R.string.mobile_share)) and
                hasAnyAncestor(hasTestTag("history-action-menu"))
        ).performClick()
        compose.runOnIdle {
            assertEquals(1, sharedBodies.size)
            assertEquals(null, state.detailId)
        }
    }

    @Test
    fun accessibilityActionsAndVerticalScrollingWorkWhileOnlyOneRowIsRevealed() {
        compose.runOnIdle { state = state.copy(records = List(20) { record(it + 1L) }) }
        compose.onNodeWithTag("destination-1").performClick()
        val custom = compose.onNodeWithTag("history-row-1").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(
            listOf(text(R.string.mobile_delete), text(R.string.mobile_share)),
            custom.map { it.label }
        )
        compose.runOnIdle { assertTrue(custom[1].action()) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, sharedBodies.size) }
        compose.onNodeWithTag("history-row-1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("history-delete-1").assertIsDisplayed()
        compose.onNodeWithTag("history-row-2").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("history-delete-2").assertIsDisplayed()
        compose.onNodeWithTag("history-delete-1").assertDoesNotExist()
        compose.onNodeWithTag("history-row-2").performTouchInput { swipeRight() }
        compose.onNodeWithTag("history-delete-2").assertDoesNotExist()
        val axis = compose.onNodeWithTag("mobile-feed").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        val before = axis.value()
        compose.onNodeWithTag("history-row-2").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue(axis.value() > before)
            assertEquals(0, deletionRequests)
            assertEquals(null, state.detailId)
        }
    }

    @Test
    fun largeTextHistoryActionsStayVisibleInBothThemes() {
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            fontScale = 1.8f
        }
        compose.onNodeWithTag("destination-1").performClick()
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            compose.onNodeWithTag("history-row-1").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("history-share-1").assertIsDisplayed()
            compose.onNodeWithTag("history-delete-1").assertIsDisplayed()
            savePreview("history-actions-large-${theme.name.lowercase()}")
            compose.onNodeWithTag("history-row-1").performTouchInput { swipeRight() }
            compose.onNodeWithTag("history-delete-1").assertDoesNotExist()
        }
    }

    @Test
    fun completedRecordDoesNotShowAnUnrelatedGlobalModelPreparationStatus() {
        compose.runOnIdle {
            globalPreparation = PreparationState(
                PreparationPhase.READY,
                target = "de",
                modelLanguage = "de"
            )
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithTag("history-detail-body").performScrollToNode(hasTestTag("delete-alert"))
        compose.onNodeWithTag("delete-alert").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_models_ready)).assertDoesNotExist()
        for (phase in listOf(
            PreparationPhase.IDENTIFYING,
            PreparationPhase.WAITING_FOR_WIFI,
            PreparationPhase.UNKNOWN_LANGUAGE,
            PreparationPhase.FAILED
        )) {
            compose.runOnIdle { globalPreparation = globalPreparation.copy(phase = phase) }
            compose.onNodeWithText(text(preparationMessage(phase))).assertDoesNotExist()
        }
        compose.runOnIdle { assertTrue(requestedModels.isEmpty()) }
    }

    @Test
    fun ownedServiceStaysHiddenWithoutBuildConfigurationAndMyMemoryCannotAuthorizeIt() {
        openSettingsPage(SettingsPage.LABS)
        compose.onNodeWithText(text(R.string.mobile_owned_server)).assertDoesNotExist()
        switchControl(text(R.string.mobile_labs_enable)).assertIsOff().performClick()
        compose.runOnIdle {
            serverAccess = ServerAccessState(configured = true, loaded = true)
            state = state.copy(options = state.options.copy(serverAllowed = true))
        }
        compose.onNodeWithText(text(R.string.mobile_owned_server)).performClick()
        compose.onNodeWithText(text(R.string.mobile_owned_access_missing)).assertIsDisplayed()
        switchControl(text(R.string.mobile_owned_permission)).assertIsNotEnabled()
        compose.runOnIdle {
            assertFalse(state.options.ownedServerAllowed)
            assertEquals(0, serverAllows)
        }
    }

    @Test
    fun accessTokenDialogMasksValidatesAndForgetsInputWithoutEnablingTranslation() {
        compose.runOnIdle { serverAccess = ServerAccessState(configured = true, loaded = true) }
        openSettingsPage(SettingsPage.OWNED_SERVER)
        compose.onNodeWithText(text(R.string.mobile_owned_access)).performClick()
        compose.onNodeWithTag("owned-server-token-save").assertIsNotEnabled()
        val synthetic = "synthetic-client-token-aaaaaaaaaaaaaaaa"
        compose.onNodeWithTag("owned-server-token").performTextInput(synthetic)
        assertTrue(
            compose.onNodeWithTag("owned-server-token").fetchSemanticsNode()
                .config.contains(SemanticsProperties.Password)
        )
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.onNodeWithText(text(R.string.mobile_owned_access)).performClick()
        assertEquals(
            "",
            compose.onNodeWithTag("owned-server-token").fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text
        )
        compose.onNodeWithTag("owned-server-token").performTextInput(synthetic)
        compose.onNodeWithTag("owned-server-token-save").performClick()
        compose.onNodeWithText(text(R.string.mobile_owned_access_saved)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(synthetic), serverTokens)
            assertFalse(state.options.ownedServerAllowed)
            assertEquals(TranslationEngineKind.ML_KIT, state.options.engine)
        }
    }

    @Test
    fun ownedPermissionReviewRejectsChangedCredentialsAndRequiresFreshConfirmation() {
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            serverAccess = ServerAccessState(
                configured = true,
                loaded = true,
                ready = true,
                credentialId = "a".repeat(64)
            )
        }
        openSettingsPage(SettingsPage.OWNED_SERVER)
        switchControl(text(R.string.mobile_owned_permission)).performClick()
        compose.onNodeWithTag("owned-server-confirm").assertIsEnabled()
        savePreview("owned-server-review", dialog = true)
        compose.runOnIdle {
            serverAccess = serverAccess.copy(revision = 1, credentialId = "b".repeat(64))
        }
        compose.onNodeWithTag("owned-server-confirm").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        switchControl(text(R.string.mobile_owned_permission)).performClick()
        compose.runOnIdle { state = state.copy(options = state.options.copy(labsEnabled = false)) }
        compose.onNodeWithTag("owned-server-review").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, serverAllows) }
        openSettingsPage(SettingsPage.OWNED_SERVER)
        switchControl(text(R.string.mobile_owned_permission)).performClick()
        compose.onNodeWithTag("owned-server-confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, serverAllows)
            assertEquals(TranslationEngineKind.OWNED_SERVER, state.options.engine)
            assertEquals("b".repeat(64), state.options.ownedServerCredentialId)
            assertTrue(state.options.translationAllowed)
        }
    }

    @Test
    fun ownedEngineChoiceNeedsItsPermissionAndMatchingReadyCredential() {
        compose.runOnIdle {
            serverAccess = ServerAccessState(
                configured = true,
                loaded = true,
                ready = true,
                credentialId = "a".repeat(64)
            )
            state =
                state.copy(options = state.options.copy(labsEnabled = true, serverAllowed = true))
        }
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithText(text(R.string.mobile_engine_title)).performClick()
        val choice = hasText(text(R.string.mobile_owned_server)) and isRadioChoice()
        compose.onNode(choice).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_close)).performClick()
        compose.runOnIdle {
            state = state.copy(
                options = state.options.copy(
                    ownedServerAllowed = true,
                    ownedServerCredentialId = "a".repeat(64)
                )
            )
        }
        compose.onNodeWithText(text(R.string.mobile_engine_title)).performClick()
        compose.onNode(choice).assertIsEnabled()
        compose.runOnIdle { serverAccess = serverAccess.copy(ready = false) }
        compose.onNode(choice).assertDoesNotExist()
        compose.runOnIdle {
            serverAccess =
                serverAccess.copy(ready = true, credentialId = "b".repeat(64))
        }
        compose.onNode(choice).assertDoesNotExist()
        compose.runOnIdle { serverAccess = serverAccess.copy(credentialId = "a".repeat(64)) }
        compose.onNode(choice).assertIsEnabled()
        compose.runOnIdle { state = state.copy(options = state.options.copy(labsEnabled = false)) }
        compose.onNode(choice).assertDoesNotExist()
    }

    @Test
    fun serverCacheAndAccessRemovalRequireConfirmationAndKeepLocalHistory() {
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            serverAccess = ServerAccessState(
                configured = true,
                loaded = true,
                ready = true,
                credentialId = "a".repeat(64)
            )
            state =
                state.copy(
                    options = state.options.copy(
                        engine = TranslationEngineKind.OWNED_SERVER,
                        labsEnabled = true,
                        ownedServerAllowed = true,
                        ownedServerCredentialId = "a".repeat(64)
                    )
                )
        }
        openSettingsPage(SettingsPage.OWNED_SERVER)
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            savePreview("owned-server-settings-${theme.name.lowercase()}")
        }
        compose.onNodeWithText(text(R.string.mobile_owned_cache_clear)).performClick()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, serverCacheClears) }
        compose.onNodeWithText(text(R.string.mobile_owned_cache_clear)).performClick()
        compose.onNodeWithTag("owned-server-confirm").performClick()
        compose.onNodeWithText(text(R.string.mobile_owned_cache_cleared))
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, serverCacheClears)
            assertFalse(state.options.ownedServerAllowed)
            assertTrue(serverAccess.ready)
        }
        compose.onNodeWithText(text(R.string.mobile_owned_access_remove)).performClick()
        compose.onNodeWithTag("owned-server-confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, serverRemovals)
            assertFalse(serverAccess.ready)
            assertEquals(1, state.records.size)
            assertEquals(0, deletionRequests)
        }
    }

    @Test
    fun groupedHistoryPreservesVisibleObservationTimesAndPaginationActions() {
        compose.runOnIdle {
            val saved = record(1).copy(
                observationCount = 24,
                moreObservations = true,
                observations = listOf(
                    HistoryObservation(
                        12,
                        CaptureSource.NOTIFICATION,
                        1700000002000,
                        1700000001000,
                        null
                    ),
                    HistoryObservation(
                        11,
                        CaptureSource.ORIGINAL_NOTIFICATION,
                        1700000000000,
                        null,
                        1699999999000
                    )
                )
            )
            state = state.copy(records = listOf(saved), latest = saved, observationBeforeId = 100)
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_observation_count, 24)).assertIsDisplayed()
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_observations)).performScrollTo().performClick()
        compose.onNodeWithTag("observation-12").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("observation-11").performScrollTo().assertIsDisplayed()
        compose.onNode(
            hasText(text(R.string.mobile_older)) and
                hasAnyAncestor(hasTestTag("observation-history"))
        ).performScrollTo().performClick()
        compose.onNode(
            hasText(text(R.string.mobile_newest)) and
                hasAnyAncestor(hasTestTag("observation-history"))
        ).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, olderObservationRequests)
            assertEquals(1, newestObservationRequests)
        }
    }

    @Test
    fun popupDisclosureIsSeparateFromActualOverlayAccessAndBatteryState() {
        openSettingsPage(SettingsPage.NOTIFICATIONS)
        switchControl(text(R.string.mobile_deferred_popup)).performScrollTo().assertIsOff()
            .performClick()
        compose.onNodeWithText(text(R.string.mobile_overlay_disclosure)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.runOnIdle { assertFalse(state.options.deferredOverlayEnabled) }
        switchControl(text(R.string.mobile_deferred_popup)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_allow)).performClick()
        compose.runOnIdle { assertTrue(configured.deferredOverlayEnabled) }
        compose.onNodeWithText(
            text(R.string.mobile_overlay_access)
        ).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, overlaySettingsOpened)
            presentationAccess = PresentationAccess(
                overlayAllowed = true,
                batteryExempt = true,
                backgroundRestricted = true
            )
        }
        compose.onNodeWithText(text(R.string.mobile_battery_exempt)).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_background_restricted)).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.mobile_battery_settings)
        ).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, batterySettingsOpened) }
        switchControl(text(R.string.mobile_deferred_popup)).performScrollTo().performClick()
        compose.runOnIdle { assertFalse(configured.deferredOverlayEnabled) }
        compose.onNodeWithText(text(R.string.mobile_overlay_access)).assertDoesNotExist()
    }

    @Test
    fun koreanSettingsChoicesAndPermissionHelpRenderInBothThemes() {
        compose.runOnIdle {
            previewLanguage = "ko"
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            compose.onNodeWithTag("destination-2").performClick()
            if (compose.onAllNodesWithTag("settings-menu").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithTag("settings-back").performClick()
            }
            compose.onNodeWithTag("settings-menu").performScrollToIndex(0)
            savePreview("settings-main-${theme.name.lowercase()}")
            openSettingsPage(SettingsPage.APPEARANCE)
            savePreview("settings-appearance-${theme.name.lowercase()}")
            compose.onNodeWithText("화면 모드").performScrollTo().performClick()
            savePreview("settings-mode-${theme.name.lowercase()}", dialog = true)
            compose.onNodeWithText("취소").performClick()
            openSettingsPage(SettingsPage.TRANSLATION)
            compose.onNodeWithText("번역 언어").performScrollTo().performClick()
            savePreview("settings-language-${theme.name.lowercase()}", dialog = true)
            compose.onNodeWithText("취소").performClick()
            openSettingsPage(SettingsPage.CAPTURE)
            savePreview("settings-ko-${theme.name.lowercase()}")
            compose.onNodeWithText("알림 접근").performScrollTo().performClick()
            savePreview("settings-access-${theme.name.lowercase()}", dialog = true)
            compose.onNodeWithText("취소").performClick()
        }
    }

    @Test
    fun appInformationShowsTheInstalledVersionAndOpensOnlyProjectLinks() {
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            previewLanguage = "ko"
        }
        openSettingsPage(SettingsPage.ABOUT)
        val version = text(
            R.string.mobile_version_value,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            compose.onNodeWithTag("app-version-value", useUnmergedTree = true)
                .assertTextEquals(version).assertIsDisplayed()
            savePreview("about-${theme.name.lowercase()}")
        }
        compose.runOnIdle { fontScale = 1.8f }
        compose.onNodeWithText(
            text(R.string.mobile_project_repository)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_contributors)).performScrollTo().performClick()
        compose.onNodeWithText(
            text(R.string.mobile_information_sources)
        ).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    "https://github.com/gaon12/CodeClear",
                    "https://github.com/gaon12/CodeClear/graphs/contributors",
                    "https://gaon12.github.io/CodeClear/sources/"
                ),
                projectLinks
            )
            projectLinkFailure = true
        }
        compose.onNodeWithText(
            text(R.string.mobile_project_repository)
        ).performScrollTo().performClick()
        compose.onNodeWithText(
            text(R.string.mobile_link_unavailable)
        ).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { projectLinkFailure = false }
        compose.onNodeWithText(
            text(R.string.mobile_project_repository)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_link_unavailable)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_compatibility)).performScrollTo().performClick()
        compose.onNodeWithTag("compatibility-page").assertExists()
        compose.onNodeWithTag("confirmation-actions").assertDoesNotExist()
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithTag("settings-detail-title").assertTextEquals(text(R.string.mobile_about))
        compose.runOnIdle {
            assertEquals(0, prepared)
            assertEquals(0, settingsOpened)
        }
    }

    @Test
    fun categoryIconsRemainDecorativeAndWideMenusHaveRoom() {
        compose.onNodeWithTag("destination-2").performClick()
        if (compose.onAllNodesWithTag("settings-split").fetchSemanticsNodes().isNotEmpty()) {
            val screen = compose.onNodeWithTag("settings-screen").fetchSemanticsNode().boundsInRoot
            val menu = compose.onNodeWithTag("settings-menu").fetchSemanticsNode().boundsInRoot
            val density = context.resources.displayMetrics.density
            val readableWidth = minOf(screen.width * 0.4f, 600f * density)
            assertTrue(
                "The parent menu needs room for icons and descriptions",
                menu.width >= readableWidth
            )
            val detail = compose.onNodeWithTag(
                "settings-detail-pane"
            ).fetchSemanticsNode().boundsInRoot
            assertTrue(detail.left >= menu.right - 1f)
            assertTrue(detail.width >= 460f * density)
        }
        for (page in settingsMenuPages()) {
            compose.onNodeWithTag("settings-menu").performScrollToNode(
                hasTestTag("settings-category-${page.name}")
            )
            val icon = compose.onNodeWithTag("settings-icon-${page.name}", useUnmergedTree = true)
            icon.assertIsDisplayed()
            assertTrue(
                !icon.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription)
            )
        }
        compose.onNodeWithTag("settings-menu").performScrollToIndex(0)
        compose.onNodeWithTag("settings-category-CAPTURE").performClick()
        compose.onNodeWithTag(
            "settings-detail-title"
        ).assertTextEquals(text(R.string.mobile_collection))
    }

    @Test
    fun settingsPagesPreserveSelectionAcrossTabsAndSupportCompactBackNavigation() {
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithTag(
            "settings-detail-title"
        ).assertTextEquals(text(R.string.mobile_translation_settings))
        compose.onNodeWithText(text(R.string.mobile_target_language)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_notification_access)).assertDoesNotExist()
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("destination-2").performClick()
        compose.onNodeWithTag(
            "settings-detail-title"
        ).assertTextEquals(text(R.string.mobile_translation_settings))
        if (compose.onAllNodesWithTag("settings-split").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("settings-category-TRANSLATION").assertIsSelected()
            val menu = compose.onNodeWithTag("settings-menu").fetchSemanticsNode().boundsInRoot
            val detail = compose.onNodeWithTag(
                "settings-detail-pane"
            ).fetchSemanticsNode().boundsInRoot
            assertTrue("The menu must be to the left of its detail", menu.right <= detail.left)
        } else {
            compose.onNodeWithTag("settings-menu").assertDoesNotExist()
            compose.runOnUiThread { context.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("settings-menu").assertIsDisplayed()
            compose.onNodeWithTag("settings-detail").assertDoesNotExist()
            openSettingsPage(SettingsPage.APPEARANCE)
            compose.onNodeWithTag("settings-back").performClick()
            compose.onNodeWithTag("settings-menu").assertIsDisplayed()
        }
        compose.onNodeWithTag("destination-0").performClick()
        compose.onNodeWithTag(
            "mobile-feed"
        ).performScrollToNode(hasText(text(R.string.mobile_manage_access)))
        compose.onNodeWithText(text(R.string.mobile_manage_access)).performClick()
        compose.onNodeWithTag(
            "settings-detail-title"
        ).assertTextEquals(text(R.string.mobile_collection))
    }

    @Test
    fun settingsSelectionSurvivesSwitchingBetweenSingleAndSplitPanes() {
        var windowWidthDp = 0f
        compose.runOnIdle {
            windowWidthDp =
                context.window.decorView.width / context.resources.displayMetrics.density
            densityScale = windowWidthDp / 960f
        }
        openSettingsPage(SettingsPage.APPEARANCE)
        compose.onNodeWithTag("settings-split").assertIsDisplayed()
        compose.onNodeWithTag("settings-category-APPEARANCE").assertIsSelected()
        compose.runOnIdle { densityScale = windowWidthDp / 400f }
        compose.onNodeWithTag("settings-split").assertDoesNotExist()
        compose.onNodeWithTag("settings-back").assertIsDisplayed()
        compose.onNodeWithTag(
            "settings-detail-title"
        ).assertTextEquals(text(R.string.mobile_appearance))
        compose.runOnIdle { densityScale = windowWidthDp / 960f }
        compose.onNodeWithTag("settings-category-APPEARANCE").assertIsSelected()
        compose.onNodeWithTag(
            "settings-detail-title"
        ).assertTextEquals(text(R.string.mobile_appearance))
    }

    private fun switchControl(label: String) = compose.onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
    )

    private fun isRadioChoice() =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun openSettingsPage(page: SettingsPage) {
        if (page.parent == SettingsPage.LABS) {
            compose.runOnIdle {
                state = state.copy(options = state.options.copy(labsEnabled = true))
            }
        }
        compose.onNodeWithTag("destination-2").performClick()
        val category = "settings-category-${(page.parent ?: page).name}"
        if (compose.onAllNodesWithTag("settings-menu").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("settings-back").performClick()
        }
        compose.onNodeWithTag("settings-menu").performScrollToNode(hasTestTag(category))
        compose.onNodeWithTag(category).performClick()
        if (page.parent != null) {
            compose.onNodeWithTag("settings-detail").performScrollToNode(hasText(text(page.title)))
            compose.onNodeWithText(text(page.title)).performClick()
        }
    }

    @Test
    fun labsIsVisibleAndVersionTapsDoNotEnableExperimentalFeatures() {
        compose.onNodeWithTag("destination-2").performClick()
        compose.onNodeWithTag(
            "settings-menu"
        ).performScrollToNode(hasTestTag("settings-category-LABS"))
        compose.onNodeWithTag("settings-category-LABS").assertIsDisplayed()
        openSettingsPage(SettingsPage.ABOUT)
        repeat(6) { compose.onNodeWithTag("app-version-row").performClick() }
        compose.onNodeWithTag("signal-easter-egg").assertDoesNotExist()
        compose.onNodeWithTag("app-version-row").performClick()
        compose.onNodeWithTag("signal-easter-egg").assertIsDisplayed()
        assertFalse(configured.labsEnabled)
        assertFalse(configured.nearbyEnabled)
        compose.onNodeWithTag("signal-egg-pulse").performClick()
        savePreview("labs-egg", dialog = true)
        compose.onNodeWithTag("signal-egg-close").performClick()
        openSettingsPage(SettingsPage.LABS)
        compose.onNodeWithText(text(R.string.mobile_nearby)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.mobile_server_translation)).assertIsNotEnabled()
        switchControl(text(R.string.mobile_labs_enable)).assertIsOff().performClick()
        compose.runOnIdle {
            assertTrue(configured.labsEnabled)
            assertFalse(configured.nearbyEnabled)
            assertFalse(configured.serverAllowed)
            assertFalse(configured.ownedServerAllowed)
        }
        compose.onNodeWithText(text(R.string.mobile_server_translation)).performClick()
        compose.onNodeWithText(text(R.string.mobile_server_permission)).assertIsOff()
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithText(text(R.string.mobile_nearby)).performClick()
        compose.onNodeWithTag(
            "settings-detail-title"
        ).assertTextEquals(text(R.string.mobile_nearby))
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithTag("settings-detail-title").assertTextEquals(text(R.string.mobile_labs))
        savePreview("labs-menu")
        compose.runOnIdle {
            state = state.copy(
                options = state.options.copy(
                    nearbyEnabled = true,
                    serverAllowed = true,
                    ownedServerAllowed = true,
                    notificationConsent = true,
                    accessibilityConsent = true,
                    localConsent = true
                )
            )
        }
        switchControl(text(R.string.mobile_labs_enable)).assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText(text(R.string.mobile_nearby)).assertIsNotEnabled()
        compose.runOnIdle {
            assertFalse(configured.labsEnabled)
            assertFalse(configured.nearbyEnabled)
            assertFalse(configured.serverAllowed)
            assertFalse(configured.ownedServerAllowed)
            assertTrue(configured.notificationConsent)
            assertTrue(configured.accessibilityConsent)
            assertTrue(configured.localConsent)
        }
        switchControl(text(R.string.mobile_labs_enable)).performClick().assertIsOn()
        compose.runOnIdle {
            assertFalse(configured.nearbyEnabled)
            assertFalse(configured.serverAllowed)
            assertFalse(configured.ownedServerAllowed)
        }
        compose.onNodeWithTag("destination-2").performClick()
        compose.onNodeWithTag("settings-menu").assertIsDisplayed()
    }

    @Test
    fun destinationTitlesStayFixedWhileTheirContentScrolls() {
        compose.runOnIdle {
            val density = context.resources.displayMetrics.density
            val widthScale = context.window.decorView.width / density / 320f
            val heightScale = context.window.decorView.height / density / 480f
            densityScale = minOf(widthScale, heightScale).coerceAtLeast(1f)
            fontScale = 1.8f
            state = state.copy(records = List(24) { record(it.toLong() + 1) })
        }
        fun verify(header: String, label: Int, feed: String, scroll: () -> Unit) {
            compose.onNodeWithTag(header).assertTextEquals(text(label)).assertIsDisplayed()
            val before = compose.onNodeWithTag(header).fetchSemanticsNode().boundsInRoot
            scroll()
            val after = compose.onNodeWithTag(header).fetchSemanticsNode().boundsInRoot
            assertEquals(before.top, after.top, 1f)
            assertEquals(before.left, after.left, 1f)
            compose.onNodeWithTag(header).assertIsDisplayed()
            val offset = compose.onNodeWithTag(feed).fetchSemanticsNode().config[
                SemanticsProperties.VerticalScrollAxisRange
            ].value()
            assertTrue("The content must have scrolled beneath its header", offset > 0f)
        }
        compose.onAllNodesWithText("CodeClear").assertCountEquals(0)
        verify("destination-title", R.string.mobile_home, "mobile-feed") {
            compose.onNodeWithTag(
                "mobile-feed"
            ).performScrollToNode(hasText(text(R.string.mobile_manage_access)))
        }
        compose.onNodeWithTag("destination-1").performClick()
        verify("destination-title", R.string.mobile_history, "mobile-feed") {
            compose.onNodeWithTag("mobile-feed").performScrollToIndex(5)
        }
        compose.onNodeWithTag("destination-2").performClick()
        verify("settings-menu-title", R.string.mobile_settings, "settings-menu") {
            compose.onNodeWithTag(
                "settings-menu"
            ).performScrollToNode(hasTestTag("settings-category-ABOUT"))
        }
    }

    @Test
    fun reselectingADestinationReturnsToItsRootAndPreservesTheHistoryQuery() {
        compose.runOnIdle { state = state.copy(records = List(24) { record(it.toLong() + 1) }) }
        fun offset(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[
            SemanticsProperties.VerticalScrollAxisRange
        ].value()
        compose.onNodeWithTag(
            "mobile-feed"
        ).performScrollToNode(hasText(text(R.string.mobile_manage_access)))
        val homeOffset = offset("mobile-feed")
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_search)).performTextInput("Gate_3%")
        compose.runOnIdle { state = state.copy(beforeId = 50) }
        compose.onNodeWithTag("mobile-feed").performScrollToIndex(5)
        assertTrue(offset("mobile-feed") > 0f)
        compose.onNodeWithTag("destination-1").performClick()
        compose.runOnIdle {
            assertEquals(1, newestRequests)
            assertEquals(Long.MAX_VALUE, state.beforeId)
            assertEquals("Gate_3%", searched)
            assertEquals("Gate_3%", state.query)
        }
        assertEquals(0f, offset("mobile-feed"), 0.01f)
        compose.onNodeWithTag("destination-1").performClick()
        compose.runOnIdle { assertEquals(1, newestRequests) }
        compose.onNodeWithTag("destination-0").performClick()
        assertEquals(homeOffset, offset("mobile-feed"), 0.01f)
        compose.onNodeWithTag("destination-0").performClick()
        assertEquals(0f, offset("mobile-feed"), 0.01f)
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithTag(
            "settings-detail-title"
        ).assertTextEquals(text(R.string.mobile_translation_settings))
        compose.onNodeWithTag("destination-2").performClick()
        compose.onNodeWithTag("settings-menu").assertIsDisplayed()
        assertEquals(0f, offset("settings-menu"), 0.01f)
        if (compose.onAllNodesWithTag("settings-split").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("settings-detail").assertDoesNotExist()
        } else {
            compose.onNodeWithTag("settings-category-CAPTURE").assertIsSelected()
        }
        openSettingsPage(SettingsPage.CAPTURE)
        compose.onNodeWithText(text(R.string.mobile_tutorial)).performScrollTo().performClick()
        compose.onNodeWithTag("destination-2").performClick()
        compose.onNodeWithTag("settings-menu").assertIsDisplayed()
        assertEquals(0f, offset("settings-menu"), 0.01f)
        compose.runOnIdle { assertEquals(0, prepared) }
    }

    @Test
    fun detailLanguageChoicesExcludeTheOriginalAndKeepTheDefaultTarget() {
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        val defaultTarget = state.options.target
        compose.onNodeWithText(
            text(R.string.mobile_target_language)
        ).performScrollTo().performClick()
        val originalLabel = text(R.string.mobile_original_language_choice, "한국어")
        compose.onNode(hasText(originalLabel) and isRadioChoice()).assertIsNotEnabled()
        compose.onNode(hasText("Français") and isRadioChoice()).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("fr", state.detailTarget)
            assertEquals(defaultTarget, state.options.target)
            assertEquals(0, originalLanguageRequests)
            assertEquals(0, prepared)
        }
    }

    @Test
    fun unknownOriginalLanguageDisablesChoicesUntilIdentificationCompletes() {
        compose.runOnIdle {
            state = state.copy(records = listOf(record(1).copy(translation = null)))
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithText(
            text(R.string.mobile_target_language)
        ).performScrollTo().performClick()
        compose.onNode(hasText("English") and isRadioChoice()).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, originalLanguageRequests)
            originalLanguageReply!!.invoke("en")
        }
        val original = text(R.string.mobile_original_language_choice, "English")
        compose.onNode(hasText(original) and isRadioChoice()).performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText("Deutsch") and isRadioChoice()).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("de", state.detailTarget) }
    }

    @Test
    fun detailsGroupMetadataAndExposeAFullWidthDeleteAction() {
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithTag("alert-metadata").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_official_unknown)).assertDoesNotExist()
        compose.onNodeWithText(
            text(R.string.mobile_estimate, text(R.string.mobile_unknown))
        ).assertDoesNotExist()
        val button = compose.onNodeWithTag("delete-alert").performScrollTo()
        val width = compose.onNodeWithTag(
            "history-detail-body"
        ).fetchSemanticsNode().boundsInRoot.width
        assertTrue(button.fetchSemanticsNode().boundsInRoot.width >= width * 0.85f)
        button.performTouchInput { click(Offset(8f, centerY)) }
        compose.runOnIdle {
            assertEquals(1, deletionRequests)
            assertEquals(1L, deletedAlertId)
        }
    }

    @Test
    fun compatibilityPageSeparatesHardwareGrantsConsentAndLiveConnections() {
        compose.runOnIdle {
            environment = environment.copy(radio = ProbeRadio.ABSENT, accessibilityGranted = true)
            fixtureAccessibilityConnected = false
            state = state.copy(options = state.options.copy(accessibilityConsent = true))
        }
        openSettingsPage(SettingsPage.ABOUT)
        compose.onNodeWithText(text(R.string.mobile_compatibility)).performScrollTo().performClick()
        compose.onNodeWithTag("compatibility-radio-status").performScrollTo()
            .assertTextEquals(text(R.string.mobile_compat_no))
        compose.onNodeWithText(text(R.string.mobile_wifi_only)).assertExists()
        compose.onNodeWithTag("compatibility-accessibility-grant-status").performScrollTo()
            .assertTextEquals(text(R.string.mobile_compat_yes))
        compose.onNodeWithTag("compatibility-accessibility-consent-status").performScrollTo()
            .assertTextEquals(text(R.string.mobile_compat_yes))
        compose.onNodeWithTag("compatibility-accessibility-connected-status").performScrollTo()
            .assertTextEquals(text(R.string.mobile_compat_no))
        compose.runOnIdle { environment = environment.copy(radio = ProbeRadio.UNKNOWN) }
        compose.onNodeWithTag("compatibility-radio-status").performScrollTo()
            .assertTextEquals(text(R.string.mobile_compat_unknown))
        compose.onNodeWithTag("confirmation-actions").assertDoesNotExist()
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithTag("settings-detail-title").assertTextEquals(text(R.string.mobile_about))
    }

    @Test
    fun singleDialogCloseUsesTheEntireActionWidth() {
        openSettingsPage(SettingsPage.PRIVACY)
        compose.onNodeWithText(text(R.string.mobile_data_storage)).performScrollTo().performClick()
        compose.onNodeWithTag("confirmation-card").performTouchInput { click(Offset(centerX, 4f)) }
        compose.onNodeWithTag("confirmation-actions").assertIsDisplayed()
        compose.onNodeWithTag("confirmation-backdrop").performTouchInput { click(Offset(2f, 2f)) }
        compose.onNodeWithTag("confirmation-actions").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_data_storage)).performClick()
        val actionsWidth = compose.onNodeWithTag(
            "confirmation-actions"
        ).fetchSemanticsNode().boundsInRoot.width
        val close = compose.onNodeWithText(text(R.string.mobile_close))
        assertEquals(actionsWidth, close.fetchSemanticsNode().boundsInRoot.width, 1f)
        close.performTouchInput { click(Offset(8f, centerY)) }
        compose.onNodeWithTag("confirmation-actions").assertDoesNotExist()
        for (language in listOf("en", "ko", "ja", "zh-Hans", "zh-Hant", "fr", "de")) {
            compose.runOnIdle { previewLanguage = language }
            compose.onNodeWithTag("privacy-policy-link").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(
                    "https://gaon12.github.io/CodeClear/privacy/" +
                        if (language == "ko") "ko.html" else "",
                    projectLinks.last()
                )
            }
        }
        compose.runOnIdle { projectLinkFailure = true }
        compose.onNodeWithTag("privacy-policy-link").performClick()
        compose.onNodeWithTag("settings-detail-title").assertExists()
        compose.runOnIdle {
            assertEquals(0, deletionRequests)
            assertEquals(0, serverAllows)
            assertTrue(requestedModels.isEmpty())
        }
    }

    @Test
    fun compactHistoryPreviewsPreserveUnicodeAndOpenTheFullSelectedRecord() {
        val original = "[DRILL] Long original. ".repeat(120)
        val translated = "[DRILL]  " + "🚨".repeat(400) + " Full translation tail."
        val first = record(1).let {
            it.copy(
                capture = it.capture.copy(original = original),
                translation = it.translation!!.copy(text = translated)
            )
        }
        compose.runOnIdle { state = state.copy(records = listOf(first)) }
        compose.onNodeWithTag("destination-1").performClick()
        val preview = compose.onNodeWithTag("history-preview-1", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertTrue(preview.length < translated.length)
        assertTrue(
            "List previews must preserve complete Unicode characters",
            Charsets.UTF_8.newEncoder().canEncode(preview)
        )
        compose.onNodeWithText(translated).assertDoesNotExist()
        compose.runOnIdle { fontScale = 1.8f }
        compose.onNodeWithTag("history-row-1").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1L, state.detailId)
            state = state.copy(latest = record(2), records = listOf(record(2), first))
        }
        compose.onNodeWithText(original).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(translated).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history-detail-back").assertIsDisplayed().performClick()
        compose.onNodeWithTag("history-row-2").assertIsDisplayed()
    }

    @Test
    fun historyDetailsUseChildPagesAndRestoreTheListBeforeRootReselection() {
        compose.runOnIdle { state = state.copy(records = List(24) { record(it.toLong() + 1) }) }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_search)).performTextInput("Gate_3%")
        compose.runOnIdle { state = state.copy(beforeId = 50) }
        compose.onNodeWithTag("mobile-feed").performScrollToIndex(8)
        compose.onNodeWithTag("history-row-8").performScrollTo()
        val listOffset = compose.onNodeWithTag("mobile-feed").fetchSemanticsNode().config[
            SemanticsProperties.VerticalScrollAxisRange
        ].value()
        assertTrue(listOffset > 0f)
        compose.onNodeWithTag("history-row-8").performClick()
        compose.onNodeWithTag("history-detail").assertIsDisplayed()
        compose.onNodeWithTag("mobile-feed").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_close)).assertDoesNotExist()
        val heading = compose.onNodeWithTag(
            "history-detail-title"
        ).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(text(R.string.mobile_delete)).performScrollTo()
        assertEquals(
            heading.top,
            compose.onNodeWithTag("history-detail-title").fetchSemanticsNode().boundsInRoot.top,
            1f
        )
        val detailOffset = compose.onNodeWithTag("history-detail-body").fetchSemanticsNode().config[
            SemanticsProperties.VerticalScrollAxisRange
        ].value()
        compose.onNodeWithTag("destination-0").performClick()
        compose.onNodeWithTag("history-detail").assertDoesNotExist()
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-detail").assertIsDisplayed()
        assertEquals(
            detailOffset,
            compose.onNodeWithTag("history-detail-body").fetchSemanticsNode().config[
                SemanticsProperties.VerticalScrollAxisRange
            ].value(),
            0.01f
        )
        compose.runOnUiThread { context.onBackPressedDispatcher.onBackPressed() }
        val restoredOffset = compose.onNodeWithTag("mobile-feed").fetchSemanticsNode().config[
            SemanticsProperties.VerticalScrollAxisRange
        ].value()
        assertEquals(listOffset, restoredOffset, 0.01f)
        compose.runOnIdle {
            assertEquals("Gate_3%", state.query)
            assertEquals(50L, state.beforeId)
            assertEquals(0, newestRequests)
            assertEquals(null, state.detailId)
        }
        compose.onNodeWithTag("history-row-8").performClick()
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-detail").assertDoesNotExist()
        assertEquals(
            0f,
            compose.onNodeWithTag("mobile-feed").fetchSemanticsNode().config[
                SemanticsProperties.VerticalScrollAxisRange
            ].value(),
            0.01f
        )
        compose.runOnIdle {
            assertEquals(1, newestRequests)
            assertEquals(Long.MAX_VALUE, state.beforeId)
            assertEquals("Gate_3%", state.query)
            assertEquals(null, state.detailId)
        }
        compose.onNodeWithTag("history-row-1").performClick()
        compose.runOnIdle { state = state.copy(detail = null, records = emptyList()) }
        compose.onNodeWithText(text(R.string.mobile_deleted)).assertIsDisplayed()
        compose.onNodeWithTag("history-detail-back").assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.mobile_no_results)).assertIsDisplayed()
    }

    @Test
    fun originalAndTranslationStayTogetherAndDetailDoesNotSwitchToNewArrival() {
        compose.onNodeWithText(
            "[DRILL] Original 1: Gate 3, 112."
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "[DRILL] Translation 1: Gate 3, 112."
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_details)).performScrollTo().performClick()
        compose.runOnIdle {
            state =
                state.copy(latest = record(2), records = listOf(record(2), record(1)))
        }
        compose.onAllNodesWithText("[DRILL] Original 1: Gate 3, 112.").fetchSemanticsNodes().also {
            assertEquals(1, it.size)
        }
        compose.onNodeWithText(text(R.string.mobile_close)).assertIsDisplayed().performClick()
        compose.onNodeWithText(
            "[DRILL] Original 2: Gate 3, 112."
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun historySearchUsesPlaceholdersUntilTheCurrentResultsArrive() {
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            previewLanguage = "ko"
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_search)).performTextInput("Gate_3%")
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle {
                state = state.copy(loading = true, options = state.options.copy(theme = theme))
            }
            compose.onNodeWithTag("history-loading").assertIsDisplayed()
            compose.onNodeWithTag("history-row-1").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.mobile_no_results)).assertDoesNotExist()
            compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
            ).assertCountEquals(0)
            savePreview("history-loading-${theme.name.lowercase()}")
        }
        compose.runOnIdle { state = state.copy(loading = false, records = emptyList()) }
        compose.onNodeWithTag("history-loading").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_no_results)).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(loading = true) }
        compose.onNodeWithText(text(R.string.mobile_no_results)).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = false, records = listOf(record(2))) }
        compose.onNodeWithTag("history-loading").assertDoesNotExist()
        compose.onNodeWithTag("history-row-2").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("Gate_3%", state.query)
            assertEquals(2L, state.detailId)
            assertEquals(0, prepared)
        }
    }

    @Test
    fun historyDispatchesLiteralSearchAndModelsRequireAnExplicitAction() {
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_search)).performTextInput("Gate_3%")
        compose.runOnIdle {
            assertEquals("Gate_3%", searched)
            val missing = record(
                1
            ).copy(translation = SavedTranslation(TranslationPhase.NEEDS_MODELS))
            state = state.copy(records = listOf(missing))
        }
        compose.onNodeWithTag("history-row-1").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_prepare)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, prepared) }
    }

    @Test
    fun systemModelsUseTheirSettingsActionAndResultsNameTheActualProducer() {
        compose.runOnIdle {
            state = state.copy(
                options = state.options.copy(engine = TranslationEngineKind.SYSTEM),
                latest = record(1).copy(
                    translation = SavedTranslation(
                        TranslationPhase.NEEDS_MODELS,
                        engine = TranslationEngineKind.SYSTEM
                    )
                )
            )
        }
        compose.onNodeWithText(
            text(R.string.mobile_system_models_action)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_retry)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, prepared)
            assertEquals(TranslationEngineKind.SYSTEM, preparedEngine)
            assertEquals(1, retried)
            assertEquals(TranslationEngineKind.SYSTEM, retriedEngine)
            state = state.copy(
                latest = record(1).copy(
                    translation = SavedTranslation(
                        TranslationPhase.READY,
                        "[DRILL] System result",
                        "en",
                        engine = TranslationEngineKind.SYSTEM,
                        sameLanguage = true
                    )
                )
            )
        }
        compose.onNodeWithText(
            text(R.string.mobile_result_system)
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.mobile_result_same_language)
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun serverTranslationNeedsSeparatePermissionAndCanBeWithdrawnWithoutChangingLocalPermission() {
        compose.runOnIdle {
            state =
                state.copy(options = state.options.copy(labsEnabled = true, localConsent = true))
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        openSettingsPage(SettingsPage.SERVER)
        compose.onNodeWithText(
            text(R.string.mobile_server_permission)
        ).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText(text(R.string.mobile_server_disclosure)).assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(state.options.serverAllowed)
            assertEquals(TranslationEngineKind.ML_KIT, state.options.engine)
        }
        savePreview("remote-permission", dialog = true)
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.onNodeWithText(text(R.string.mobile_server_permission)).performClick()
        compose.onNodeWithText(text(R.string.mobile_allow)).performClick()
        compose.runOnIdle {
            assertTrue(state.options.serverAllowed)
            assertTrue(state.options.localConsent)
            assertEquals(TranslationEngineKind.MY_MEMORY, state.options.engine)
            assertTrue(state.options.translationAllowed)
            assertTrue(requestedModels.isEmpty())
        }
        compose.onNodeWithText(
            text(R.string.mobile_server_permission)
        ).assertIsOn().performClick().assertIsOff()
        compose.runOnIdle {
            assertFalse(state.options.serverAllowed)
            assertFalse(state.options.translationAllowed)
            assertTrue(state.options.localConsent)
        }
        compose.onNodeWithText(text(R.string.mobile_server_terms)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("https://mymemory.translated.net/terms-and-conditions"),
                projectLinks
            )
        }
    }

    @Test
    fun remoteResultsRetainTheirProviderAndShowQuotaFailures() {
        compose.runOnIdle {
            state =
                state.copy(
                    options = state.options.copy(
                        engine = TranslationEngineKind.MY_MEMORY,
                        serverAllowed = true,
                        labsEnabled = true
                    ),
                    latest = record(
                        1
                    ).copy(
                        translation = SavedTranslation(
                            TranslationPhase.READY,
                            "[DRILL] Remote translation",
                            "ko",
                            engine = TranslationEngineKind.MY_MEMORY
                        )
                    )
                )
        }
        compose.onNodeWithText(
            text(R.string.mobile_result_mymemory)
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_result_mlkit)).assertDoesNotExist()
        compose.runOnIdle {
            state =
                state.copy(
                    latest = state.latest!!.copy(
                        translation = SavedTranslation(
                            TranslationPhase.QUOTA,
                            engine = TranslationEngineKind.MY_MEMORY
                        )
                    )
                )
        }
        compose.onNodeWithText(
            text(R.string.mobile_remote_quota)
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun engineChangesExplainLocalProcessingAndPreserveTheDisabledChoice() {
        compose.runOnIdle {
            state =
                state.copy(
                    options = state.options.copy(
                        localConsent = false,
                        target = TranslationTarget.KOREAN
                    )
                )
            systemSupport =
                SystemEngineAvailability(
                    true,
                    false,
                    true,
                    listOf(SystemLanguagePair("en", "ko", true))
                )
        }
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).performClick()
        compose.onNodeWithText(text(R.string.mobile_system_disclosure)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(TranslationEngineKind.ML_KIT, state.options.engine) }
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).performClick()
        compose.onNodeWithText(text(R.string.mobile_change)).performClick()
        compose.runOnIdle {
            assertEquals(TranslationEngineKind.SYSTEM, state.options.engine)
            assertEquals(false, state.options.localConsent)
        }
        compose.onNodeWithText(
            text(R.string.mobile_auto_translation)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_system_disclosure)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_enable)).performClick()
        compose.runOnIdle { assertEquals(true, state.options.localConsent) }
    }

    @Test
    fun wrongTargetsStayUnavailableAndSettingsActionsDoNotChangeChoices() {
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(target = TranslationTarget.KOREAN))
            systemSupport =
                SystemEngineAvailability(
                    true,
                    false,
                    true,
                    listOf(SystemLanguagePair("en", "fr", true))
                )
        }
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).assertIsNotSelected().assertIsNotEnabled()
        compose.onNodeWithText(
            text(R.string.mobile_engine_pair_missing, languageName("ko"))
        ).assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.mobile_engine_refresh)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_close)).performClick()
        compose.onNodeWithText(
            text(R.string.mobile_system_models_action)
        ).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, manualSupportRefreshes)
            assertEquals(1, providerSettingsOpened)
            assertEquals(TranslationEngineKind.ML_KIT, state.options.engine)
            assertEquals(true, state.options.localConsent)
            assertEquals(0, prepared)
        }
    }

    @Test
    fun systemEngineExplainsNoPublicPairsAndQueryFailuresWithoutEnablingStaleSupport() {
        compose.runOnIdle {
            systemSupport =
                SystemEngineAvailability(loaded = true, platformSupported = true)
        }
        openEnginePicker()
        compose.onNodeWithText(text(R.string.mobile_engine_no_pairs)).assertIsDisplayed()
        compose.runOnIdle {
            systemSupport = SystemEngineAvailability(
                true,
                false,
                true,
                listOf(SystemLanguagePair("en", state.options.target.languageTag, true)),
                queryFailed = true
            )
        }
        compose.onNodeWithText(text(R.string.mobile_engine_query_failed)).assertIsDisplayed()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).assertIsNotSelected()
        compose.runOnIdle { systemSupport = SystemEngineAvailability(loaded = true) }
        compose.onNodeWithText(text(R.string.mobile_engine_unavailable)).assertIsDisplayed()
    }

    @Test
    fun unavailableSystemEngineExplainsItsReasonAndKeepsSettingsOutsideThePicker() {
        compose.runOnIdle {
            systemSupport =
                SystemEngineAvailability(loaded = true, platformSupported = true)
        }
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.mobile_engine_no_pairs)).assertIsDisplayed()
        compose.onNode(
            hasText(text(R.string.mobile_system_models_action)) and
                hasAnyAncestor(hasTestTag("engine-picker"))
        ).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_close)).performClick()
        compose.onNodeWithText(
            text(R.string.mobile_system_models_action)
        ).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(TranslationEngineKind.ML_KIT, state.options.engine)
            assertEquals(1, providerSettingsOpened)
            assertEquals(0, prepared)
        }
    }

    @Test
    fun disabledRemoteConsentHidesEngineAndBlocksStaleConfirmation() {
        val remote = hasText(text(R.string.mobile_engine_mymemory)) and isRadioChoice()
        openEnginePicker()
        compose.onAllNodes(remote).assertCountEquals(0)
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(labsEnabled = true))
        }
        compose.onAllNodes(remote).assertCountEquals(0)
        compose.runOnIdle {
            assertFalse(state.options.serverAllowed)
            state = state.copy(options = state.options.copy(serverAllowed = true))
        }
        compose.onNode(remote).assertIsEnabled()
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(labsEnabled = false))
        }
        compose.onAllNodes(remote).assertCountEquals(0)
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(labsEnabled = true))
        }
        compose.onNode(remote).assertIsEnabled().performClick()
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(serverAllowed = false))
        }
        compose.onNodeWithText(text(R.string.mobile_allow)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.runOnIdle {
            assertEquals(TranslationEngineKind.ML_KIT, state.options.engine)
            assertFalse(state.options.serverAllowed)
        }
    }

    @Test
    fun aDisappearingProviderCannotBeConfirmedThroughAnOlderDisclosure() {
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(target = TranslationTarget.KOREAN))
            systemSupport =
                SystemEngineAvailability(
                    true,
                    false,
                    true,
                    listOf(SystemLanguagePair("en", "ko", true))
                )
        }
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).performClick()
        compose.runOnIdle { systemSupport = SystemEngineAvailability(loaded = true) }
        compose.onNodeWithText(text(R.string.mobile_change)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.runOnIdle {
            assertEquals(TranslationEngineKind.ML_KIT, state.options.engine)
            assertEquals(true, state.options.localConsent)
        }
    }

    @Test
    fun unpreparedPairsUseProviderSettingsAndTurningOffAlwaysRemainsAvailable() {
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(target = TranslationTarget.KOREAN))
            systemSupport =
                SystemEngineAvailability(
                    true,
                    false,
                    true,
                    listOf(SystemLanguagePair("en", "ko", false))
                )
        }
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).performClick()
        compose.onNodeWithText(text(R.string.mobile_change)).performClick()
        compose.onNodeWithText(
            text(R.string.mobile_engine_pairs, languageName("ko"), 0, 1)
        )
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.mobile_system_models_action)
        ).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, providerSettingsOpened)
            assertEquals(0, prepared)
            systemSupport = SystemEngineAvailability(loaded = true)
        }
        compose.onNodeWithText(
            text(R.string.mobile_auto_translation)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_auto_translation)).assertIsNotEnabled()
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).assertIsSelected()
        compose.onNode(
            hasText(text(R.string.mobile_engine_mlkit)) and isRadioChoice()
        ).performClick()
        compose.onNodeWithText(text(R.string.mobile_change)).performClick()
        compose.runOnIdle {
            assertEquals(TranslationEngineKind.ML_KIT, state.options.engine)
            assertEquals(false, state.options.localConsent)
        }
    }

    private fun openEnginePicker() {
        openSettingsPage(SettingsPage.TRANSLATION)
        val label = text(R.string.mobile_engine_title)
        compose.onNodeWithTag("settings-detail").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
    }

    @Test
    fun accessDisclosurePrecedesSettingsAndDoesNotPretendToGrantPermission() {
        openSettingsPage(SettingsPage.CAPTURE)
        compose.runOnIdle { environment = environment.copy(notificationGranted = false) }
        compose.onNodeWithText(
            text(R.string.mobile_notification_access)
        ).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText(text(R.string.mobile_notification_disclosure)).assertIsDisplayed()
        assertDecisionWindowAtBottom()
        compose.runOnIdle { assertEquals(0, settingsOpened) }
        compose.onNodeWithText(text(R.string.mobile_open_settings)).performClick()
        compose.runOnIdle {
            assertEquals(true, configured.notificationConsent)
            assertEquals(1, settingsOpened)
        }
        compose.onNodeWithText(text(R.string.mobile_notification_access)).assertIsOff()
        compose.runOnIdle { environment = environment.copy(notificationGranted = true) }
        compose.onNodeWithText(text(R.string.mobile_notification_access)).assertIsOn()
        compose.onNodeWithText(
            text(R.string.mobile_notification_access)
        ).performClick().assertIsOff()
        compose.runOnIdle { assertEquals(false, configured.notificationConsent) }
    }

    @Test
    fun wifiOnlyNoticeAndThreeStepTutorialRemainUsable() {
        compose.runOnIdle {
            environment = environment.copy(radio = ProbeRadio.ABSENT)
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            previewLanguage = "ko"
        }
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            compose.onNodeWithTag("mobile-feed").performScrollToNode(
                hasText(text(R.string.mobile_reception_information))
            )
            compose.onNodeWithText(text(R.string.mobile_reception_information)).assertIsDisplayed()
            compose.onNodeWithTag("mobile-feed")
                .performScrollToNode(hasText(text(R.string.mobile_wifi_only)))
            compose.onNodeWithText(text(R.string.mobile_wifi_only)).assertIsDisplayed()
            savePreview("wifi-only-home-${theme.name.lowercase()}")
        }
        compose.onNodeWithTag("mobile-feed")
            .performScrollToNode(hasText(text(R.string.mobile_tutorial)))
        compose.onNodeWithText(text(R.string.mobile_tutorial)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.mobile_next)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_tutorial_read)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_next)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_done)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(true, configured.setupComplete)
            environment = environment.copy(radio = ProbeRadio.PRESENT)
        }
        compose.onNodeWithText(text(R.string.mobile_wifi_only)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_reception_information)).assertDoesNotExist()
    }

    @Test
    fun wifiOnlySettingsAvoidAccessPromptsAndStillAllowTurningCaptureOff() {
        compose.runOnIdle {
            environment = environment.copy(radio = ProbeRadio.ABSENT)
            state = state.copy(
                options = state.options.copy(
                    notificationConsent = true,
                    accessibilityConsent = true
                )
            )
        }
        openSettingsPage(SettingsPage.CAPTURE)
        compose.onNodeWithText(
            text(R.string.mobile_notification_access)
        ).performScrollTo().assertIsOff().performClick()
        compose.runOnIdle {
            assertEquals(false, configured.notificationConsent)
            assertEquals(true, configured.accessibilityConsent)
            assertEquals(0, settingsOpened)
        }
        compose.onNodeWithText(text(R.string.mobile_notification_access)).assertIsNotEnabled()
        compose.onNodeWithText(
            text(R.string.mobile_accessibility_access)
        ).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(false, configured.accessibilityConsent) }
        compose.onNodeWithText(text(R.string.mobile_accessibility_access)).assertIsNotEnabled()
    }

    @Test
    fun restrictedSettingsFallbackOpensTheAppListWithoutChangingChoices() {
        val previous = state.options
        openSettingsPage(SettingsPage.CAPTURE)
        if (Build.VERSION.SDK_INT >= 33) {
            compose.onNodeWithText(
                text(R.string.mobile_permission_help)
            ).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.mobile_app_list)).performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(Settings.ACTION_APPLICATION_SETTINGS, lastSettingsAction)
                assertEquals(1, settingsOpened)
                assertEquals(previous, state.options)
            }
        } else {
            compose.onAllNodesWithText(text(R.string.mobile_app_list)).assertCountEquals(0)
        }
    }

    @Test
    fun largeTextKeepsPermissionActionsAndDialogCloseReachable() {
        compose.runOnIdle { fontScale = 1.8f }
        compose.onNodeWithTag(
            "mobile-feed"
        ).performScrollToNode(hasText(text(R.string.mobile_details)))
        compose.onNodeWithText(text(R.string.mobile_details)).performClick()
        compose.onNodeWithText(text(R.string.mobile_close)).assertIsDisplayed().performClick()
        openSettingsPage(SettingsPage.CAPTURE)
        compose.runOnIdle { environment = environment.copy(notificationGranted = false) }
        compose.onNodeWithText(
            text(R.string.mobile_notification_access)
        ).performScrollTo().performClick()
        compose.onNodeWithText(
            text(R.string.mobile_open_settings)
        ).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, settingsOpened) }
        compose.runOnIdle { environment = environment.copy(notificationGranted = true) }
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithText(
            text(R.string.mobile_target_language)
        ).performScrollTo().performClick()
        compose.onNode(hasText("Deutsch") and isRadioChoice()).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(xyz.gaon.codeclear.translation.TranslationTarget.GERMAN, configured.target)
        }
    }

    @Test
    fun compactWindowKeepsPermissionAndRadioActionsReachableAtLargeText() {
        compose.runOnIdle {
            densityScale = (
                context.window.decorView.width /
                    context.resources.displayMetrics.density / 600f
                ).coerceAtLeast(1f)
            fontScale = 1.8f
            environment = environment.copy(notificationGranted = false)
        }
        compose.onNodeWithTag("required-access").assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.mobile_notification_access)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_open_settings)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, settingsOpened) }
        compose.runOnIdle { environment = environment.copy(notificationGranted = true) }
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithText(
            text(R.string.mobile_target_language)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_cancel)).assertIsDisplayed()
        compose.onNode(hasText("Deutsch") and isRadioChoice()).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(TranslationTarget.GERMAN, configured.target)
            assertEquals(0, prepared)
        }
    }

    @Test
    fun longAlertDetailsKeepTheCloseActionOutsideTheScrollingBody() {
        compose.runOnIdle {
            fontScale = 1.8f
            val alert = record(1)
            state = state.copy(
                detailId = 1,
                detail = alert.copy(
                    capture = alert.capture.copy(
                        original = "[DRILL] Long original. 112. ".repeat(150)
                    ),
                    translation = alert.translation!!.copy(
                        text = "[DRILL] Long translation. 112. ".repeat(150)
                    )
                )
            )
        }
        compose.onNodeWithText(text(R.string.mobile_close)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(null, state.detailId) }
    }

    @Test
    fun bottomDestinationsKeepIndependentScrollPositions() {
        compose.onNodeWithTag(
            "mobile-feed"
        ).performScrollToNode(hasText(text(R.string.mobile_details)))
        compose.onNodeWithText(text(R.string.mobile_details)).assertIsDisplayed()
        val before = compose.onNodeWithText(
            text(R.string.mobile_details)
        ).fetchSemanticsNode().boundsInRoot
        val beforeScroll = compose.onNodeWithTag("mobile-feed").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
        ].value()
        openSettingsPage(SettingsPage.APPEARANCE)
        val label = text(R.string.mobile_ui_language_title)
        compose.onNodeWithText(label).performScrollTo()
        compose.onNodeWithTag("destination-0").performClick()
        val after = compose.onNodeWithText(
            text(R.string.mobile_details)
        ).fetchSemanticsNode().boundsInRoot
        val afterScroll = compose.onNodeWithTag("mobile-feed").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
        ].value()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString("codeclear_viewport_before", before.toString())
                putString("codeclear_viewport_after", after.toString())
                putFloat("codeclear_scroll_before", beforeScroll)
                putFloat("codeclear_scroll_after", afterScroll)
            }
        )
        assertEquals(beforeScroll, afterScroll, 0.01f)
        assertEquals(before.top, after.top, 1f)
        compose.onNodeWithText(text(R.string.mobile_details)).assertIsDisplayed()
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_search)).assertIsDisplayed()
    }

    @Test
    fun lightAndBlackThemesRenderSyntheticOriginalAndTranslation() {
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle {
                state = state.copy(options = state.options.copy(theme = theme))
                context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            compose.onNodeWithTag("mobile-feed")
                .performScrollToNode(hasText("[DRILL] Translation 1: Gate 3, 112."))
            compose.onNodeWithText("[DRILL] Translation 1: Gate 3, 112.").assertIsDisplayed()
            File(context.cacheDir, "mobile-${theme.name.lowercase()}.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    it
                )
            }
        }
    }

    @Test
    fun homeOverviewOpensRelevantSettingsAndKeepsEmptyStateReadable() {
        compose.runOnIdle {
            state = state.copy(latest = null)
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            previewLanguage = "ko"
        }
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            compose.onNodeWithTag("mobile-feed").performScrollToIndex(0)
            compose.onNodeWithTag("home-overview").assertIsDisplayed()
            compose.onNodeWithTag(
                "mobile-feed"
            ).performScrollToNode(hasText(text(R.string.mobile_home_empty_title)))
            compose.onNodeWithText(text(R.string.mobile_home_empty_title)).assertIsDisplayed()
            savePreview("home-empty-${theme.name.lowercase()}")
        }
        compose.onNodeWithTag(
            "mobile-feed"
        ).performScrollToNode(hasText(text(R.string.mobile_collection)))
        compose.onNodeWithText(text(R.string.mobile_collection)).performClick()
        compose.onNodeWithTag("settings-detail-title")
            .assertTextEquals(text(R.string.mobile_collection))
        compose.onNodeWithTag("destination-0").performClick()
        compose.onNodeWithText(text(R.string.mobile_translation_settings)).performClick()
        compose.onNodeWithTag("settings-detail-title")
            .assertTextEquals(text(R.string.mobile_translation_settings))
        compose.onNodeWithTag("destination-0").performClick()
        compose.onNodeWithText(text(R.string.mobile_home_view_history)).performClick()
        compose.onNodeWithTag("destination-title").assertTextEquals(text(R.string.mobile_history))
        compose.runOnIdle { assertEquals(0, prepared) }
    }

    @Test
    fun koreanScreensShowTheOriginalAndEnglishResultInBothThemes() {
        val original = "[훈련] 오늘 밤 많은 비가 예상됩니다. 하천 주변 접근을 피하세요."
        val translated = "[DRILL] Heavy rain tonight. Stay away from rivers."
        compose.runOnIdle {
            previewLanguage = "ko"
            val alert = record(1).let {
                it.copy(
                    capture = it.capture.copy(title = "[훈련] 안전 안내 문자", original = original),
                    translation = it.translation!!.copy(text = translated)
                )
            }
            state = state.copy(
                latest = alert,
                records = listOf(alert),
                options = state.options.copy(target = TranslationTarget.ENGLISH)
            )
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(translated))
            compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(original))
            compose.onNodeWithText(original).assertIsDisplayed()
            compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(translated))
            compose.onNodeWithText(translated).assertIsDisplayed()
            savePreview("mobile-ko-${theme.name.lowercase()}")
        }
        compose.runOnIdle {
            val titles = listOf("[훈련] 비 안내", "[훈련] 하천 주변 안내", "[훈련] 도로 통제 안내", "[훈련] 강풍 안내")
            val results =
                listOf(
                    "[DRILL] Heavy rain tonight.",
                    "[DRILL] Stay away from rivers.",
                    "[DRILL] Use an alternative route.",
                    "[DRILL] Avoid outdoor structures."
                )
            state = state.copy(
                records = List(8) { index ->
                    record(index.toLong() + 1).let {
                        it.copy(
                            capture = it.capture.copy(
                                title = titles[index % titles.size],
                                original = original,
                                capturedAtMillis = it.capture.capturedAtMillis - index * 86_400_000L
                            ),
                            translation = it.translation!!.copy(
                                text = results[index % results.size]
                            )
                        )
                    }
                }
            )
        }
        compose.onNodeWithTag("destination-1").performClick()
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            compose.onNodeWithTag("mobile-feed").performScrollToIndex(0)
            savePreview("history-list-${theme.name.lowercase()}")
            compose.onNodeWithTag("history-row-1").performClick()
            savePreview("history-detail-${theme.name.lowercase()}")
            compose.onNodeWithTag("history-detail-back").performClick()
        }
        savePreview("mobile-ko-history")
        compose.onNodeWithTag("destination-2").performClick()
        compose.onNodeWithTag("settings-menu").performScrollToIndex(0)
        savePreview("mobile-ko-settings")
    }

    private fun savePreview(name: String, dialog: Boolean = false) {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val copied = CountDownLatch(1)
        val result = AtomicInteger(-1)
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val window = if (dialog) dialogWindow() else context.window
            val decor = window.decorView
            check(decor.width.toLong() * decor.height <= 16_000_000)
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            // Copy only this synthetic activity window, including its actual navigation layers.
            PixelCopy.request(window, bitmap, {
                result.set(it)
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        try {
            assertTrue(copied.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result.get())
            File(context.cacheDir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertDecisionWindowAtBottom() {
        compose.runOnIdle {
            val decor = dialogWindow().decorView
            val location = IntArray(2)
            decor.getLocationOnScreen(location)
            val screenBottom = context.windowManager.maximumWindowMetrics.bounds.bottom
            val allowance = 80 * context.resources.displayMetrics.density
            assertTrue(
                "The decision window must end near the lower screen edge",
                location[1] + decor.height >= screenBottom - allowance
            )
        }
    }

    private fun dialogWindow(): Window {
        val views = ArrayDeque<View>().apply { addAll(WindowInspector.getGlobalWindowViews()) }
        var provider: DialogWindowProvider? = null
        var remaining = 256
        while (views.isNotEmpty() && remaining-- > 0) {
            val view = views.removeFirst()
            if (view is DialogWindowProvider) {
                provider = view
                break
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) views.addLast(view.getChildAt(index))
            }
        }
        return checkNotNull(provider).window
    }

    private fun record(id: Long) = SavedAlert(
        id,
        CapturedAlert(
            "test.system.receiver",
            CaptureSource.NOTIFICATION,
            "[DRILL] Test alert $id",
            "[DRILL] Original $id: Gate 3, 112.",
            1700000000000 + id
        ),
        SavedTranslation(TranslationPhase.READY, "[DRILL] Translation $id: Gate 3, 112.", "ko")
    )
}
