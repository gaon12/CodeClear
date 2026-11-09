package xyz.gaon.codeclear.mobile

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.TranslationTarget

/** Explicit translation model downloads and speech playback of the displayed message. */
class MobileScreenModelSpeechDeviceTest : MobileScreenFixture() {
    @Test fun automaticSpeechOptInIsSeparateFromTranslationAndDoesNotRequestModels() {
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithText(
            text(R.string.mobile_automatic_speech)
        ).performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(state.options.automaticSpeechEnabled)
            assertTrue(requestedModels.isEmpty())
            assertEquals(0, serverAllows)
        }
        compose.onNodeWithText(
            text(R.string.mobile_automatic_speech)
        ).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(false, state.options.automaticSpeechEnabled)
            assertTrue(requestedModels.isEmpty())
        }
    }

    @Test
    fun modelManagementShowsInstalledStateAndOnlyDownloadsAfterAnExplicitAction() {
        compose.runOnIdle { inventory = inventory.copy(downloaded = setOf("ko", "es")) }
        compose.runOnIdle { context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithTag("settings-detail").performScrollToNode(hasTestTag("model-es"))
        val spanish = Locale.forLanguageTag(
            "es"
        ).getDisplayLanguage(fixtureResources.configuration.locales[0])
        compose.onNode(hasText(spanish) and hasAnyAncestor(hasTestTag("model-es"))).performClick()
        compose.onNodeWithTag("model-download-dialog").assertExists()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.onNodeWithTag("settings-detail").performScrollToNode(hasTestTag("model-ko"))
        savePreview("model-management")
        compose.onNode(hasText("한국어") and hasAnyAncestor(hasTestTag("model-ko"))).performClick()
        compose.onNodeWithTag("model-download-dialog").assertExists()
        compose.onNodeWithText(text(R.string.mobile_model_update_review)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_model_size_estimate, 30)).assertDoesNotExist()
        savePreview("model-update-review", dialog = true)
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.runOnIdle { assertTrue(requestedModels.isEmpty()) }
        compose.onNode(hasText("한국어") and hasAnyAncestor(hasTestTag("model-ko"))).performClick()
        compose.onNodeWithText(text(R.string.mobile_models_check_updates)).performClick()
        compose.runOnIdle { assertEquals(listOf("ko"), requestedModels) }
        compose.onNodeWithTag("model-download-close").performClick()
        compose.onNodeWithTag("settings-detail").performScrollToNode(hasTestTag("model-ja"))
        compose.onNode(
            hasText("日本語") and hasAnyAncestor(hasTestTag("model-ja"))
        ).assertIsNotEnabled()
        compose.runOnIdle {
            modelPreparation =
                PreparationState(PreparationPhase.READY, modelLanguage = "ko")
        }
        compose.onNode(hasText("日本語") and hasAnyAncestor(hasTestTag("model-ja"))).performClick()
        compose.onNodeWithText(text(R.string.mobile_prepare)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_models_download)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
    }

    @Test
    fun missingDetailLanguageOpensDownloadReviewAndShowsCompletedPackCounts() {
        compose.runOnIdle {
            inventory = inventory.copy(downloaded = setOf("ko"))
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithText(
            text(R.string.mobile_target_language)
        ).performScrollTo().performClick()
        compose.onNode(hasText("日本語") and isRadioChoice()).performScrollTo().performClick()
        compose.onNodeWithTag("model-download-dialog").assertExists()
        compose.runOnIdle { assertTrue(requestedModels.isEmpty()) }
        savePreview("model-download-review", dialog = true)
        compose.onNodeWithTag("model-download-confirm").performClick()
        compose.onNodeWithTag("model-download-count").assertTextEquals(
            text(R.string.mobile_model_progress_count, 1, 2)
        )
        compose.runOnIdle { assertEquals(listOf("ja"), requestedModels) }
        savePreview("model-download-progress", dialog = true)
        compose.onNodeWithTag("model-download-close").performClick()
        compose.runOnIdle {
            inventory = inventory.copy(downloaded = setOf("ko", "ja"))
            modelPreparation = modelPreparation.copy(
                phase = PreparationPhase.READY,
                completedLanguages = setOf("ko", "ja"),
                activeLanguage = null
            )
            modelDownload = modelDownload?.copy(visible = true)
        }
        compose.onNodeWithTag(
            "model-download-status"
        ).assertTextEquals(text(R.string.mobile_models_ready))
        compose.onNodeWithTag("model-download-count").assertTextEquals(
            text(R.string.mobile_model_progress_count, 2, 2)
        )
        compose.runOnIdle { assertEquals(listOf("ja"), requestedModels) }
    }

    @Test
    fun missingDefaultLanguageCanBeSelectedButDownloadRequiresConfirmation() {
        compose.runOnIdle { inventory = inventory.copy(downloaded = setOf("ko")) }
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithText(
            text(R.string.mobile_target_language)
        ).performScrollTo().performClick()
        compose.onNode(hasText("日本語") and isRadioChoice()).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(TranslationTarget.JAPANESE, configured.target)
            assertTrue(requestedModels.isEmpty())
        }
        compose.onNodeWithTag("model-download-dialog").assertExists()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.runOnIdle { assertTrue(requestedModels.isEmpty()) }
    }

    @Test
    fun tutorialIncludesOptionalModelPreparationWithoutStartingDownloads() {
        openSettingsPage(SettingsPage.CAPTURE)
        compose.onNodeWithText(text(R.string.mobile_tutorial)).performScrollTo().performClick()
        repeat(2) {
            compose.onNodeWithText(text(R.string.mobile_next)).performScrollTo().performClick()
        }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasTestTag("model-management"))
        compose.onNodeWithText(text(R.string.mobile_models_update_notice)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(requestedModels.isEmpty()) }
        compose.onNodeWithText(text(R.string.mobile_done)).performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(configured.setupComplete)
            assertTrue(requestedModels.isEmpty())
        }
    }

    @Test
    fun speechReadsTheDisplayedMessageAndStopsWhenSwitchingOrLeaving() {
        compose.runOnIdle {
            state =
                state.copy(options = state.options.copy(messageViewMode = MessageViewMode.SWITCH))
        }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasTestTag("speech-translation"))
        compose.onNodeWithTag("speech-translation").performClick()
        compose.runOnIdle {
            assertEquals(state.latest!!.translation!!.text to "ko", spokenMessages.last())
            assertEquals(SpeechPhase.PLAYING, speechState.phase)
        }
        compose.onNodeWithTag("switch-message").performClick()
        compose.runOnIdle { assertEquals(SpeechPhase.IDLE, speechState.phase) }
        compose.onNodeWithTag("speech-original").performClick()
        compose.runOnIdle {
            assertEquals(state.latest!!.capture.original to "ko", spokenMessages.last())
        }
        compose.onNodeWithTag("destination-2").performClick()
        compose.runOnIdle { assertEquals(SpeechPhase.IDLE, speechState.phase) }
    }

    @Test
    fun abandonedOriginalLanguageIdentificationCannotStartSpeech() {
        compose.runOnIdle {
            state = state.copy(
                latest = state.latest!!.copy(translation = null),
                options = state.options.copy(messageViewMode = MessageViewMode.SWITCH)
            )
        }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasTestTag("speech-original"))
        compose.onNodeWithTag("speech-original").performClick()
        compose.runOnIdle { assertEquals(1, originalLanguageRequests) }
        compose.onNodeWithTag("destination-2").performClick()
        compose.runOnIdle {
            originalLanguageReply!!("en")
            assertTrue(spokenMessages.isEmpty())
        }
    }
}
