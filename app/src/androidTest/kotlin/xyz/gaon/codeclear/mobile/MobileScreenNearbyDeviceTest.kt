package xyz.gaon.codeclear.mobile

import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.nearby.NearbyState
import xyz.gaon.codeclear.nearby.NearbyStatus
import xyz.gaon.codeclear.nearby.RelayProtocol

/** Experimental nearby sharing permission, inbox, and explicit save flows. */
class MobileScreenNearbyDeviceTest : MobileScreenFixture() {
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
    fun matchingHistoricalBodyStillRequiresAnExplicitPeerSaveRequest() {
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
                listOf(original)
            )
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_nearby_inbox)).performClick()
        compose.onNodeWithText(original.title).performClick()
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("nearby-inbox")))
            .performScrollToNode(hasTestTag("save-nearby"))
        compose.onNodeWithTag("save-nearby").performClick()
        compose.onNodeWithTag("history-detail").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, nearbySaves) }
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
}
