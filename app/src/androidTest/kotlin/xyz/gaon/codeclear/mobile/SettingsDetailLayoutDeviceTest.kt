package xyz.gaon.codeclear.mobile

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.R

class SettingsDetailLayoutDeviceTest : MobileScreenFixture() {
    @Test
    fun appearanceSeparatesDisplayChoicesFromLanguageAtLargeText() {
        compose.runOnIdle { fontScale = 2f }
        openSettingsPage(SettingsPage.APPEARANCE)
        compose.onNodeWithTag("appearance-display-group").performScrollTo()
        val display = compose.onNodeWithTag("appearance-display-group")
            .getUnclippedBoundsInRoot()
        val language = compose.onNodeWithTag("appearance-language-group")
            .getUnclippedBoundsInRoot()
        assertTrue("Separate sections must have breathing room", language.top > display.bottom)
        compose.onNodeWithText(text(R.string.mobile_ui_language_title)).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun notificationExplanationsAreOutsideTheControlsTheyDescribe() {
        openSettingsPage(SettingsPage.NOTIFICATIONS)
        for ((description, card) in listOf(
            R.string.mobile_result_notification_summary to "notification-control-group",
            R.string.mobile_deferred_popup_summary to "popup-control-group"
        )) {
            compose.onNodeWithText(text(description)).performScrollTo().assertIsDisplayed()
            compose.onNode(hasText(text(description)) and hasAnyAncestor(hasTestTag(card)))
                .assertDoesNotExist()
        }
        compose.runOnIdle {
            assertEquals(0, notificationRequests)
            assertEquals(0, overlaySettingsOpened)
            assertTrue(!state.options.deferredOverlayEnabled)
        }
    }

    @Test
    fun everyDetailPageKeepsItsTitleAndLastContentReachableInBothThemes() {
        compose.runOnIdle {
            previewLanguage = "ko"
            fontScale = 2f
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            serverAccess = serverAccess.copy(configured = true, loaded = true)
        }
        val lastContent = mapOf(
            SettingsPage.CAPTURE to hasText(text(R.string.mobile_tutorial)),
            SettingsPage.TRANSLATION to hasTestTag("model-zh"),
            SettingsPage.APPEARANCE to hasText(text(R.string.mobile_ui_language_title)),
            SettingsPage.NOTIFICATIONS to hasText(text(R.string.mobile_battery_guidance)),
            SettingsPage.PRIVACY to hasText(text(R.string.mobile_delete_all)),
            SettingsPage.ABOUT to hasText(text(R.string.mobile_compatibility)),
            SettingsPage.COMPATIBILITY to hasText(text(R.string.mobile_compatibility_description)),
            SettingsPage.LABS to hasText(text(R.string.mobile_owned_server)),
            SettingsPage.SHARING to hasText(text(R.string.mobile_nearby_disclosure)),
            SettingsPage.SERVER to hasText(text(R.string.mobile_server_translation_notice)),
            SettingsPage.OWNED_SERVER to hasText(text(R.string.mobile_server_translation_notice))
        )
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            for ((page, last) in lastContent) {
                openSettingsPage(page)
                compose.onNodeWithTag("settings-detail").performScrollToIndex(0)
                savePreview("detail-${page.name.lowercase()}-${theme.name.lowercase()}")
                compose.onNodeWithTag("settings-detail").performScrollToNode(last)
                compose.onNode(last).assertIsDisplayed()
                compose.onNodeWithTag("settings-detail-title").assertIsDisplayed()
                    .assertTextEquals(text(page.title))
            }
        }
        compose.runOnIdle {
            assertEquals(0, prepared)
            assertEquals(0, nearbyRequests)
            assertEquals(0, serverAllows)
            assertEquals(0, deletionRequests)
            assertEquals(0, settingsOpened)
        }
    }
}
