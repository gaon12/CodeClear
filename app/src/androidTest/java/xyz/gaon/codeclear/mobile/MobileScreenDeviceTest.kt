package xyz.gaon.codeclear.mobile

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.gaon.codeclear.MainActivity
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.probe.LocalEngineKind
import xyz.gaon.codeclear.probe.LocalTarget
import xyz.gaon.codeclear.probe.ProbeEnvironment
import xyz.gaon.codeclear.probe.ProbeRadio
import xyz.gaon.codeclear.probe.ProbeSessionState
import xyz.gaon.codeclear.probe.SystemEngineAvailability
import xyz.gaon.codeclear.probe.SystemLanguagePair

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
    private var newestRequests = 0
    private var notificationRequests = 0
    private var notificationsAllowed by mutableStateOf(true)
    private var settingsOpened = 0
    private var lastSettingsAction = ""
    private var prepared = 0
    private var preparedEngine: LocalEngineKind? = null
    private var retriedEngine: LocalEngineKind? = null
    private var retried = 0
    private var fontScale by mutableStateOf(1f)
    private var densityScale by mutableStateOf(1f)
    private var previewLanguage by mutableStateOf<String?>(null)
    private var systemSupport by mutableStateOf(SystemEngineAvailability(loaded = true))
    private var manualSupportRefreshes = 0
    private var providerSettingsOpened = 0
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
                    LocalDensity provides Density(density.density * densityScale, fontScale),
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
                                val selected = id?.let { key ->
                                    state.records.firstOrNull { it.id == key }
                                        ?: state.latest?.takeIf { it.id == key }
                                        ?: record(key)
                                }
                                state = state.copy(detailId = id, detail = selected)
                            },
                            delete = {
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
                            systemSettings = { providerSettingsOpened++ }
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
        switchControl(text(R.string.mobile_result_notifications)).assertIsOff()
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
        compose.onNodeWithTag("destination-2").performClick()
        val category = "settings-category-${page.name}"
        if (compose.onAllNodesWithTag("settings-menu").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("settings-back").performClick()
        }
        compose.onNodeWithTag("settings-menu").performScrollToNode(hasTestTag(category))
        compose.onNodeWithTag(category).performClick()
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
        compose.onNodeWithText(text(R.string.mobile_retry)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, prepared)
            assertEquals(LocalEngineKind.SYSTEM, preparedEngine)
            assertEquals(1, retried)
            assertEquals(LocalEngineKind.SYSTEM, retriedEngine)
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
    fun engineChangesExplainLocalProcessingAndPreserveTheDisabledChoice() {
        compose.runOnIdle {
            state =
                state.copy(
                    options = state.options.copy(localConsent = false, target = LocalTarget.KOREAN)
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
        compose.runOnIdle { assertEquals(LocalEngineKind.ML_KIT, state.options.engine) }
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).performClick()
        compose.onNodeWithText(text(R.string.mobile_change)).performClick()
        compose.runOnIdle {
            assertEquals(LocalEngineKind.SYSTEM, state.options.engine)
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
            state = state.copy(options = state.options.copy(target = LocalTarget.KOREAN))
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
        ).assertIsNotEnabled()
        compose.onNodeWithText(
            context.getString(R.string.mobile_engine_pair_missing, languageName("ko"))
        ).assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.mobile_engine_refresh)
        ).performScrollTo().performClick()
        compose.onNodeWithText(
            text(R.string.mobile_system_models_action)
        ).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, manualSupportRefreshes)
            assertEquals(1, providerSettingsOpened)
            assertEquals(LocalEngineKind.ML_KIT, state.options.engine)
            assertEquals(true, state.options.localConsent)
            assertEquals(0, prepared)
        }
    }

    @Test
    fun aDisappearingProviderCannotBeConfirmedThroughAnOlderDisclosure() {
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(target = LocalTarget.KOREAN))
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
            assertEquals(LocalEngineKind.ML_KIT, state.options.engine)
            assertEquals(true, state.options.localConsent)
        }
    }

    @Test
    fun unpreparedPairsUseProviderSettingsAndTurningOffAlwaysRemainsAvailable() {
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(target = LocalTarget.KOREAN))
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
            context.getString(R.string.mobile_engine_pairs, languageName("ko"), 0, 1)
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
        ).assertIsNotEnabled()
        compose.onNode(
            hasText(text(R.string.mobile_engine_mlkit)) and isRadioChoice()
        ).performClick()
        compose.onNodeWithText(text(R.string.mobile_change)).performClick()
        compose.runOnIdle {
            assertEquals(LocalEngineKind.ML_KIT, state.options.engine)
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
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithText(
            text(R.string.mobile_target_language)
        ).performScrollTo().performClick()
        compose.onNodeWithText("Deutsch").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(xyz.gaon.codeclear.probe.LocalTarget.GERMAN, configured.target)
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
        openSettingsPage(SettingsPage.CAPTURE)
        compose.onNodeWithText(
            text(R.string.mobile_notification_access)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_open_settings)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, settingsOpened) }
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithText(
            text(R.string.mobile_target_language)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_cancel)).assertIsDisplayed()
        compose.onNode(hasText("Deutsch") and isRadioChoice()).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(LocalTarget.GERMAN, configured.target)
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
                options = state.options.copy(target = LocalTarget.ENGLISH)
            )
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(translated))
            compose.onNodeWithText(original).assertIsDisplayed()
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
