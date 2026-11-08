package xyz.gaon.codeclear.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.R

class MobileNavigationLayoutDeviceTest : MobileScreenFixture() {
    private val windowWidthDp: Float
        get() = compose.activity.resources.configuration.screenWidthDp.toFloat()

    @Test
    fun tutorialActionsStayOutsideTheContentAndAtOppositeEdges() {
        compose.runOnIdle {
            densityScale = windowWidthDp / 360f
            state = state.copy(options = state.options.copy(setupComplete = false))
        }
        compose.onNodeWithTag("tutorial-navigation-previous").assertDoesNotExist()
        compose.onNodeWithTag("tutorial-navigation-next").performScrollTo().performClick()
        compose.onNodeWithTag("tutorial-navigation-next").performScrollTo().assertIsDisplayed()
        val content = compose.onNodeWithTag("tutorial-content").fetchSemanticsNode().boundsInRoot
        val previous = compose.onNodeWithTag(
            "tutorial-navigation-previous"
        ).fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag(
            "tutorial-navigation-next"
        ).fetchSemanticsNode().boundsInRoot
        assertTrue(previous.top >= content.bottom - 1f)
        assertTrue(next.top >= content.bottom - 1f)
        assertTrue(previous.right < next.left)
        compose.onNodeWithTag("tutorial-navigation-previous").performClick()
        compose.onNodeWithTag("tutorial-navigation-previous").assertDoesNotExist()
    }

    @Test
    fun settingsHeadingCollapsesWhileItsTitleStaysAvailable() {
        compose.runOnIdle { densityScale = windowWidthDp / 360f }
        compose.onNodeWithTag("destination-2").performClick()
        val before = compose.onNodeWithTag(
            "destination-heading-frame"
        ).fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("settings-menu").performTouchInput { swipeUp() }
        compose.onNodeWithTag("settings-menu-title").assertIsDisplayed()
        val after = compose.onNodeWithTag(
            "destination-heading-frame"
        ).fetchSemanticsNode().boundsInRoot.height
        val configuration = compose.activity.resources.configuration
        if (configuration.screenWidthDp < 840 && configuration.screenHeightDp >= 560) {
            assertTrue("The portrait heading should collapse as the list scrolls", after < before)
        } else {
            assertEquals("Short and wide windows retain a compact heading", before, after, 1f)
        }
    }

    @Test
    fun largeTextNavigationKeepsBothActionsVisibleAndSeparated() {
        compose.runOnIdle {
            fontScale = 1.8f
            densityScale = windowWidthDp / 360f
            state = state.copy(options = state.options.copy(setupComplete = false))
        }
        compose.onNodeWithTag("tutorial-navigation-next").performScrollTo().performClick()
        compose.onNodeWithTag("tutorial-navigation-next").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tutorial-navigation-previous").assertIsDisplayed()
        val left = compose.onNodeWithTag(
            "tutorial-navigation-previous"
        ).fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag(
            "tutorial-navigation-next"
        ).fetchSemanticsNode().boundsInRoot
        assertTrue(left.right < right.left)
    }
}
