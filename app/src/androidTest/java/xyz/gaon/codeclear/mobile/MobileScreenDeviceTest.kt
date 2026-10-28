package xyz.gaon.codeclear.mobile

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.DialogWindowProvider
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.gaon.codeclear.MainActivity
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.probe.LocalEngineKind
import xyz.gaon.codeclear.probe.ProbeEnvironment
import xyz.gaon.codeclear.probe.ProbeRadio
import xyz.gaon.codeclear.probe.ProbeSessionState

/** Renders synthetic-only state. The real runtime is paused and its history is never modified. */
class MobileScreenDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var scope: AutoCloseable? = null
    private var state by mutableStateOf(MobileState())
    private var environment by mutableStateOf(
        ProbeEnvironment(ProbeRadio.PRESENT, setOf("test.system.receiver"), true, true)
    )
    private var configured = MobileOptions()
    private var searched = ""
    private var settingsOpened = 0
    private var lastSettingsAction = ""
    private var prepared = 0
    private var preparedEngine: LocalEngineKind? = null
    private var fontScale by mutableStateOf(1f)
    private var previewLanguage by mutableStateOf<String?>(null)
    private val context get() = compose.activity
    private fun text(id: Int) = context.getString(id)

    @Before
    fun renderOnlySyntheticPrivateFixtures() {
        compose.runOnUiThread {
            scope = context.mobileRuntime.pauseForDiagnostics()
            val record = record(1)
            state =
                MobileState(
                    loaded = true,
                    options = MobileOptions(localConsent = true),
                    latest = record,
                    records = listOf(record)
                )
            context.setContent {
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
                    previewLanguage?.let {
                        base.createConfigurationContext(
                            Configuration(configuration).apply {
                                setLocale(Locale.forLanguageTag(it))
                            }
                        )
                    } ?: base
                }
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalContext provides localized,
                    LocalConfiguration provides localized.resources.configuration
                ) {
                    MobileScreen(
                        state,
                        environment,
                        ProbeSessionState(
                            notificationConnected = true,
                            accessibilityConnected = true
                        ),
                        false,
                        PreparationState(),
                        MobileActions(
                            configure = {
                                configured = it
                                state = state.copy(options = it)
                            },
                            search = {
                                searched = it
                                state = state.copy(query = it)
                            },
                            detail = { id ->
                                state = state.copy(detailId = id, detail = id?.let(::record))
                            },
                            delete = {
                            }, retry = { _, _, _ ->
                            }, prepare = { _, _, engine ->
                                prepared++
                                preparedEngine = engine
                            }, settings = {
                                settingsOpened++
                                lastSettingsAction = it
                            }, openSource = {
                            }, notificationPermission = {
                            }, notificationsAllowed = true, older = {
                            }, newest = {}, retryStorage = {}
                        )
                    )
                }
            }
        }
    }

    @After
    fun restoreSecureWindowAndReleaseDiagnosticScope() {
        compose.runOnUiThread {
            context.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            scope?.close()
        }
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
        compose.onNodeWithText(text(R.string.mobile_prepare)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, prepared) }
    }

    @Test
    fun systemModelsUseTheirSettingsActionAndResultsNameTheActualProducer() {
        compose.runOnIdle {
            state = state.copy(
                options = state.options.copy(engine = LocalEngineKind.SYSTEM),
                latest = record(1).copy(
                    translation = SavedTranslation(
                        TranslationPhase.NEEDS_MODELS,
                        engine = LocalEngineKind.SYSTEM
                    )
                )
            )
        }
        compose.onNodeWithText(
            text(R.string.mobile_system_models_action)
        ).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, prepared)
            assertEquals(LocalEngineKind.SYSTEM, preparedEngine)
            state = state.copy(
                latest = record(1).copy(
                    translation = SavedTranslation(
                        TranslationPhase.READY,
                        "[DRILL] System result",
                        "en",
                        engine = LocalEngineKind.SYSTEM,
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
    fun accessDisclosurePrecedesSettingsAndDoesNotPretendToGrantPermission() {
        compose.onNodeWithTag("destination-2").performClick()
        compose.onAllNodesWithText(
            text(R.string.mobile_review_access)
        )[0].performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_notification_disclosure)).assertIsDisplayed()
        assertDecisionWindowAtBottom()
        compose.runOnIdle { assertEquals(0, settingsOpened) }
        compose.onNodeWithText(text(R.string.mobile_agree)).performClick()
        compose.runOnIdle {
            assertEquals(true, configured.notificationConsent)
            assertEquals(1, settingsOpened)
        }
    }

    @Test
    fun wifiOnlyNoticeAndThreeStepTutorialRemainUsable() {
        compose.runOnIdle { environment = environment.copy(radio = ProbeRadio.ABSENT) }
        compose.onNodeWithTag("mobile-feed")
            .performScrollToNode(hasText(text(R.string.mobile_wifi_only)))
        compose.onNodeWithText(text(R.string.mobile_wifi_only)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_tutorial)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_next)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_tutorial_read)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_next)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_done)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, configured.setupComplete) }
    }

    @Test
    fun wifiOnlySettingsAvoidAccessPromptsAndStillAllowWithdrawal() {
        compose.runOnIdle {
            environment = environment.copy(radio = ProbeRadio.ABSENT)
            state = state.copy(
                options = state.options.copy(
                    notificationConsent = true,
                    accessibilityConsent = true
                )
            )
        }
        compose.onNodeWithTag("destination-2").performClick()
        compose.onAllNodesWithText(text(R.string.mobile_review_access)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.mobile_withdraw)).assertCountEquals(2)
        compose.onAllNodesWithText(
            text(R.string.mobile_withdraw)
        )[0].performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(false, configured.notificationConsent)
            assertEquals(true, configured.accessibilityConsent)
            assertEquals(0, settingsOpened)
        }
        compose.onAllNodesWithText(
            text(R.string.mobile_withdraw)
        )[0].performScrollTo().performClick()
        compose.runOnIdle { assertEquals(false, configured.accessibilityConsent) }
    }

    @Test
    fun restrictedSettingsFallbackOpensTheAppListWithoutChangingConsent() {
        val previous = state.options
        compose.onNodeWithTag("destination-2").performClick()
        if (Build.VERSION.SDK_INT >= 33) {
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
        compose.onNodeWithTag("destination-2").performClick()
        compose.onAllNodesWithText(
            text(R.string.mobile_review_access)
        )[0].performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_agree)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, settingsOpened) }
        compose.onNodeWithText(
            context.getString(
                R.string.mobile_target,
                languageName(state.options.target.languageTag)
            )
        ).performScrollTo().performClick()
        compose.onNodeWithText("Deutsch").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(xyz.gaon.codeclear.probe.LocalTarget.GERMAN, configured.target)
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
        compose.onNodeWithTag("destination-2").performClick()
        compose.onNodeWithText(
            text(R.string.mobile_collection)
        ).performScrollTo().assertIsDisplayed()
        val label = context.getString(R.string.mobile_ui_language, text(R.string.mobile_system))
        compose.onNodeWithText(label).performScrollTo()
        compose.onNodeWithTag("destination-0").performClick()
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
            state = state.copy(latest = alert, records = listOf(alert))
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(translated))
            compose.onNodeWithText(original).assertIsDisplayed()
            compose.onNodeWithText(translated).assertIsDisplayed()
            savePreview("mobile-ko-${theme.name.lowercase()}")
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("mobile-feed").performScrollToIndex(0)
        savePreview("mobile-ko-history")
        compose.onNodeWithTag("destination-2").performClick()
        compose.onNodeWithTag("mobile-feed").performScrollToIndex(0)
        savePreview("mobile-ko-settings")
    }

    private fun savePreview(name: String) {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        // Framework press ripples also use render-thread time outside the Compose test clock.
        android.os.SystemClock.sleep(350)
        File(context.cacheDir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(
                Bitmap.CompressFormat.PNG,
                100,
                it
            )
        }
    }

    private fun assertDecisionWindowAtBottom() {
        compose.runOnIdle {
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
            val decor = checkNotNull(provider).window.decorView
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
