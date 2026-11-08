package xyz.gaon.codeclear.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Test
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.StandardTranslationCatalog
import xyz.gaon.codeclear.translation.TranslationTarget

class StandardTranslationScreenDeviceTest : MobileScreenFixture() {
    @Test
    fun preparedWordingShowsItsOwnAttributionInsteadOfTheSelectedEngineLabel() {
        compose.runOnIdle {
            val source = "先ほど発表した緊急地震速報を取り消します"
            val result = StandardTranslationCatalog.translate(source, TranslationTarget.ENGLISH)!!
            val alert = record(1).let {
                it.copy(
                    capture = it.capture.copy(original = source),
                    translation = SavedTranslation(
                        TranslationPhase.READY,
                        result.text,
                        result.sourceLanguage,
                        standard = result.attribution
                    )
                )
            }
            state = state.copy(latest = null, records = emptyList(), detailId = 1, detail = alert)
        }
        compose.onNodeWithTag("alert-modal-details").performClick()
        compose.onNodeWithText(
            text(R.string.mobile_result_standard)
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_result_mlkit)).assertDoesNotExist()
        compose.runOnIdle {
            state =
                state.copy(
                    detail = state.detail!!.copy(
                        translation = SavedTranslation(TranslationPhase.STALE)
                    )
                )
        }
        compose.onNodeWithText(
            text(R.string.mobile_standard_updated)
        ).performScrollTo().assertIsDisplayed()
    }
}
