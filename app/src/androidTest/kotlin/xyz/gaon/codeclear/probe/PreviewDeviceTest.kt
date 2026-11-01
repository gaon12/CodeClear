package xyz.gaon.codeclear.probe

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeBodyEvidence
import xyz.gaon.codeclear.capture.ProbeObservation
import xyz.gaon.codeclear.capture.ProbeOrigin
import xyz.gaon.codeclear.capture.ProbeRoute
import xyz.gaon.codeclear.capture.ProbeSession
import xyz.gaon.codeclear.translation.TranslationResult
import xyz.gaon.codeclear.translation.TranslationState
import xyz.gaon.codeclear.translation.TranslationTarget

@RunWith(AndroidJUnit4::class)
class PreviewDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<CaptureProbeActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun prepareOnlySyntheticObservations() {
        compose.runOnIdle {
            ProbeSession.configure(ProbeRoute.NOTIFICATION, false)
            ProbeSession.configure(ProbeRoute.ACCESSIBILITY, false)
            ProbeLocalTranslation.enable(false)
            ProbeLocalTranslation.chooseTarget(TranslationTarget.ENGLISH)
            add(1)
        }
    }

    @After
    fun clearSyntheticObservations() {
        compose.runOnIdle {
            ProbeLocalTranslation.enable(false)
            ProbeSession.clear()
        }
    }

    @Test
    fun homePreservesOriginalAndShowsASeparateTranslationArea() {
        compose.onNodeWithText(body(1)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.preview_original)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.preview_translation, "English"))
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { ProbeLocalTranslation.enable(true) }
        compose.waitUntil(30_000) {
            val result = (ProbeLocalTranslation.state as? TranslationState.Finished)?.result
            result is TranslationResult.Ready
        }
        compose.onAllNodesWithText(body(1))[1].performScrollTo().assertIsDisplayed()
        // Capture only this synthetic fixture while both real capture consents are disabled.
        compose.runOnIdle {
            compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        try {
            val dark =
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            val destination =
                File(context.cacheDir, "preview-${if (dark) "dark" else "light"}.png")
            destination.outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    it
                )
            }
        } finally {
            compose.runOnIdle {
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    @Test
    fun historySearchFiltersWithoutChangingStoredObservations() {
        compose.runOnIdle { add(2) }
        compose.onNodeWithText(context.getString(R.string.preview_history)).performClick()
        compose.onNodeWithText(
            context.getString(R.string.preview_search)
        ).performTextInput("Gate 2")
        compose.onNodeWithText(body(2)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(body(1)).assertDoesNotExist()
    }

    @Test
    fun detailKeepsItsOriginalIdWhenAnotherAlertArrivesAndHandlesDeletion() {
        compose.onNodeWithText(
            context.getString(R.string.preview_details)
        ).performScrollTo().performClick()
        compose.runOnIdle { add(2) }
        compose.onNodeWithText(body(2)).assertDoesNotExist()
        compose.runOnIdle { ProbeSession.clear() }
        compose.onNodeWithText(context.getString(R.string.preview_removed)).assertIsDisplayed()
    }

    private fun add(number: Int) {
        ProbeSession.publish(
            ProbeSession.currentGeneration(),
            ProbeObservation(
                packageName = "com.android.shell",
                route = ProbeRoute.NOTIFICATION,
                origin = ProbeOrigin.SHELL_FIXTURE,
                title = "[DRILL] Preview fixture",
                body = body(number),
                capturedAtMillis = 1_700_000_000_000L,
                bodyEvidence = ProbeBodyEvidence.SHELL_FIXTURE_FIELD
            )
        )
    }

    private fun body(number: Int) = "[DRILL] Do not enter Gate $number. Stay indoors until 14:05."
}
