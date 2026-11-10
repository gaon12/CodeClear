package xyz.gaon.codeclear.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit validation-package fixture; never instrument the owner's collector to review UI. */
class ObservationReviewActivityDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun isolatedReviewSupportsThemesAndOptionalTimes() {
        assumeTrue(
            InstrumentationRegistry.getInstrumentation().targetContext.packageName ==
                "xyz.gaon.codeclear.validation"
        )
        ActivityScenario.launch(ObservationReviewActivity::class.java).use {
            compose.onNodeWithTag("observation-review-notice").assertIsDisplayed()
            compose.onNodeWithTag("observation-review-light").performClick().assertIsSelected()
            compose.onNodeWithTag("observation-history-toggle").performScrollTo().performClick()
            compose.onNodeWithTag("observation-toggle-12").performScrollTo().performClick()
            compose.onNodeWithTag("observation-times-12").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("observation-toggle-12").performScrollTo().performClick()
            compose.onNodeWithTag("observation-times-12").assertDoesNotExist()
            compose.onNodeWithTag(
                "observation-review-dark"
            ).performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("observation-history-toggle").performScrollTo().performClick()
            compose.onNodeWithTag("observation-history").assertDoesNotExist()
        }
    }
}
