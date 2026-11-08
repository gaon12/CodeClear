package xyz.gaon.codeclear.mobile

import android.graphics.Bitmap
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.BuildConfig
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeRadio
import xyz.gaon.codeclear.translation.TranslationTarget

/** Settings navigation, appearance, app information, Labs, and themed previews. */
class MobileScreenSettingsDeviceTest : MobileScreenFixture() {
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
            compose.onNodeWithText(text(R.string.mobile_last_saved)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.mobile_home_view_history)).assertDoesNotExist()
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
        compose.runOnIdle { state = state.copy(latest = record(1)) }
        compose.onNodeWithTag("mobile-feed")
            .performScrollToNode(hasText(text(R.string.mobile_home_view_history)))
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
}
