package xyz.gaon.codeclear.mobile

import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeRadio
import xyz.gaon.codeclear.translation.TranslationTarget

/** First-run guidance, capture access, Wi-Fi-only hardware, and large-text permission flows. */
class MobileScreenAccessDeviceTest : MobileScreenFixture() {
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
}
