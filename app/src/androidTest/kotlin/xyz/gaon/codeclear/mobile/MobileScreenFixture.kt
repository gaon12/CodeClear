package xyz.gaon.codeclear.mobile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.DialogWindowProvider
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeEnvironment
import xyz.gaon.codeclear.capture.ProbeRadio
import xyz.gaon.codeclear.capture.ProbeSessionState
import xyz.gaon.codeclear.nearby.NearbyState
import xyz.gaon.codeclear.nearby.NearbyStatus
import xyz.gaon.codeclear.translation.ServerAccessState
import xyz.gaon.codeclear.translation.ServerCacheClearResult
import xyz.gaon.codeclear.translation.SystemEngineAvailability
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationTarget
import xyz.gaon.codeclear.translation.serverDigest

/**
 * Shared synthetic-only screen fixture. The real runtime is paused and its history is never
 * modified. Subclasses group related screen checks so each file stays reviewable.
 */
abstract class MobileScreenFixture {
    @get:Rule val compose = createAndroidComposeRule<MobileFixtureActivity>()
    protected var scope: AutoCloseable? = null
    protected var state by mutableStateOf(MobileState())
    protected var environment by mutableStateOf(
        ProbeEnvironment(ProbeRadio.PRESENT, setOf("test.system.receiver"), true, true)
    )
    protected var configured = MobileOptions()
    protected var searched = ""
    protected var newestRequests = 0
    protected var notificationRequests = 0
    protected var fixtureAccessibilityConnected by mutableStateOf(true)
    protected var notificationsAllowed by mutableStateOf(true)
    protected var settingsOpened = 0
    protected var lastSettingsAction = ""
    protected var originalLanguageRequests = 0
    protected var speechState by mutableStateOf(SpeechState())
    protected val copiedBodies = mutableListOf<String>()
    protected val sharedBodies = mutableListOf<String>()
    protected val spokenMessages = mutableListOf<Pair<String, String>>()
    protected var inventory by mutableStateOf(
        ModelInventoryState(loaded = true, downloaded = primaryModelLanguages)
    )
    protected var modelPreparation by mutableStateOf(PreparationState())
    protected val requestedModels = mutableListOf<String>()
    protected var modelDownload by mutableStateOf<ModelDownloadRequest?>(null)
    protected var originalLanguageReply: ((String?) -> Unit)? = null
    protected var nearby by mutableStateOf(NearbyState())
    protected var nearbyRequests = 0
    protected var nearbySaves = 0
    protected var deletionRequests = 0
    protected var deletedAlertId: Long? = null
    protected var prepared = 0
    protected var preparedEngine: TranslationEngineKind? = null
    protected var retriedEngine: TranslationEngineKind? = null
    protected var retried = 0
    protected var fontScale by mutableStateOf(1f)
    protected var densityScale by mutableStateOf(1f)
    protected var previewLanguage by mutableStateOf<String?>("en")
    protected var systemSupport by mutableStateOf(SystemEngineAvailability(loaded = true))
    protected val projectLinks = mutableListOf<String>()
    protected var projectLinkFailure = false
    protected var manualSupportRefreshes = 0
    protected var providerSettingsOpened = 0
    protected var overlaySettingsOpened = 0
    protected var batterySettingsOpened = 0
    protected var olderObservationRequests = 0
    protected var newestObservationRequests = 0
    protected var presentationAccess by mutableStateOf(PresentationAccess())
    protected var serverAccess by mutableStateOf(ServerAccessState())
    protected var serverAllows = 0
    protected var serverRemovals = 0
    protected var serverCacheClears = 0
    protected var globalPreparation by mutableStateOf(PreparationState())
    protected val serverTokens = mutableListOf<String>()
    protected val context get() = compose.activity
    protected val fixtureResources
        get() = localizedFixtureContext(context, context.resources.configuration).resources
    protected fun text(id: Int) = fixtureResources.getString(id)
    protected fun text(id: Int, vararg arguments: Any) = fixtureResources.getString(id, *arguments)

    protected fun localizedFixtureContext(base: Context, configuration: Configuration): Context =
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

    protected fun switchControl(label: String) = compose.onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
    )

    protected fun isRadioChoice() =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    protected fun openSettingsPage(page: SettingsPage) {
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

    protected fun openEnginePicker() {
        openSettingsPage(SettingsPage.TRANSLATION)
        val label = text(R.string.mobile_engine_title)
        compose.onNodeWithTag("settings-detail").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
    }

    protected fun savePreview(name: String, dialog: Boolean = false) {
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

    protected fun assertDecisionWindowAtBottom() {
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

    protected fun dialogWindow(): Window {
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

    protected fun record(id: Long) = SavedAlert(
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
