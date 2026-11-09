package xyz.gaon.codeclear.mobile

import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.reception.CountryEvidence
import xyz.gaon.codeclear.reception.ReceptionCountry
import xyz.gaon.codeclear.translation.IssuerBasis
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationTarget
import xyz.gaon.codeclear.translation.evidencedIssuer

/** Alert modal and detail presentation: message views, actions, categories, and languages. */
class MobileScreenAlertDetailDeviceTest : MobileScreenFixture() {
    @Test fun aiIssuerShowsItsFallibleBasisAndExactSourceAtLargeText() {
        val original = "[DRILL] Issued by Agency X."
        compose.runOnIdle {
            fontScale = 2f
            val alert = record(1).let {
                it.copy(
                    capture = it.capture.copy(original = original),
                    translation = it.translation!!.copy(
                        engine = TranslationEngineKind.OWNED_SERVER,
                        issuer = evidencedIssuer(
                            original,
                            "Agency X",
                            "기관 X",
                            "Issued by Agency X.",
                            IssuerBasis.EXTRACTED_FROM_TEXT
                        )
                    )
                )
            }
            state = state.copy(records = listOf(alert), latest = alert)
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            compose.onNodeWithTag("issuer-name").performScrollTo()
                .assertTextContains("기관 X · Agency X")
            compose.onNodeWithText(
                text(R.string.mobile_issuer_ai_notice)
            ).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag(
                "issuer-evidence"
            ).performScrollTo().assertTextContains(
                text(R.string.mobile_issuer_evidence, "Issued by Agency X.")
            )
        }
        compose.runOnIdle {
            assertEquals(0, serverAllows)
            assertTrue(requestedModels.isEmpty())
        }
    }

    @Test fun localIssuerIsOnlyAnEstimateAndUnknownOriginalsStayUnknown() {
        compose.runOnIdle {
            val alert = record(1).let {
                it.copy(
                    capture = it.capture.copy(original = "[DRILL] Gate 3. [행정안전부]")
                )
            }
            state = state.copy(records = listOf(alert), latest = alert)
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithTag("issuer-name").performScrollTo().assertTextContains("행정안전부")
        compose.onNodeWithText(
            text(R.string.mobile_issuer_rule_notice)
        ).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(
                detail = state.detail!!.let {
                    it.copy(
                        capture = it.capture.copy(original = "[DRILL] Seoul, call 112.")
                    )
                }
            )
        }
        compose.onNodeWithTag("issuer-unknown").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("issuer-name").assertDoesNotExist()
    }

    @Test
    fun singleMessageViewStartsWithTranslationAndSwitchesToTheOriginal() {
        val original = state.latest!!.capture.original
        val translated = state.latest!!.translation!!.text!!
        compose.runOnIdle {
            state =
                state.copy(options = state.options.copy(messageViewMode = MessageViewMode.SWITCH))
        }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(translated))
        compose.onNodeWithText(translated).assertIsDisplayed()
        compose.onNodeWithText(original).assertDoesNotExist()
        compose.onNodeWithTag("switch-message").performScrollTo().performClick()
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(original))
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onNodeWithText(translated).assertDoesNotExist()
        compose.onNodeWithTag("switch-message").performScrollTo().performClick()
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(translated))
        compose.onNodeWithText(translated).assertIsDisplayed()
        openSettingsPage(SettingsPage.APPEARANCE)
        compose.onNodeWithText(text(R.string.mobile_message_view)).performClick()
        compose.onNodeWithText(text(R.string.mobile_message_both)).performClick()
        assertEquals(MessageViewMode.BOTH, configured.messageViewMode)
    }

    @Test
    fun singleMessageViewExposesChangedScriptButAvoidsAnIdenticalSameLanguageResult() {
        val original = "【演练】请远离车站，注意安全。"
        val converted = "【演練】請遠離車站，注意安全。"
        val alert = record(1).let {
            it.copy(
                capture = it.capture.copy(original = original),
                translation = SavedTranslation(
                    TranslationPhase.READY,
                    converted,
                    "zh",
                    sameLanguage = true
                )
            )
        }
        compose.runOnIdle {
            state = state.copy(
                latest = alert,
                options = state.options.copy(
                    target = TranslationTarget.TRADITIONAL_CHINESE,
                    messageViewMode = MessageViewMode.SWITCH
                )
            )
        }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(converted))
        compose.onNodeWithText(converted).assertIsDisplayed()
        compose.onNodeWithText(original).assertDoesNotExist()
        compose.onNodeWithTag("speech-translation").performClick()
        compose.runOnIdle { assertEquals(converted to "zh-Hant", spokenMessages.last()) }
        compose.onNodeWithTag("switch-message").performClick()
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(original))
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onNodeWithText(converted).assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(
                latest = alert.copy(translation = alert.translation!!.copy(text = original))
            )
        }
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onNodeWithTag("switch-message").assertDoesNotExist()
    }

    @Test
    fun singleMessageViewKeepsTheOriginalReadableWhenTranslationIsUnavailable() {
        val original = state.latest!!.capture.original
        compose.runOnIdle {
            state = state.copy(
                options = state.options.copy(messageViewMode = MessageViewMode.SWITCH),
                latest = state.latest!!.copy(translation = null)
            )
        }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(original))
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onNodeWithTag("switch-message").assertDoesNotExist()
        val translated = record(1).translation!!.text!!
        compose.runOnIdle { state = state.copy(latest = record(1)) }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasText(translated))
        compose.onNodeWithText(translated).assertIsDisplayed()
        compose.onNodeWithText(original).assertDoesNotExist()
    }

    @Test
    fun alertModalStaysWithinTheScreenAndLeavesManagementToTheDetailPage() {
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            state =
                state.copy(options = state.options.copy(messageViewMode = MessageViewMode.SWITCH))
        }
        compose.onNodeWithTag(
            "mobile-feed"
        ).performScrollToNode(hasText(text(R.string.mobile_details)))
        compose.onNodeWithText(text(R.string.mobile_details)).performClick()
        val bounds = compose.onNodeWithTag("alert-modal-card").fetchSemanticsNode().boundsInRoot
        assertTrue(
            bounds.height < context.windowManager.maximumWindowMetrics.bounds.height() * 0.8f
        )
        compose.onNodeWithTag("alert-modal-close").assertIsDisplayed()
        compose.onNode(
            hasText(state.latest!!.translation!!.text!!) and
                hasAnyAncestor(hasTestTag("alert-modal-card"))
        ).assertIsDisplayed()
        compose.onNodeWithTag("delete-alert").assertDoesNotExist()
        compose.onNodeWithTag("alert-metadata").assertDoesNotExist()
        val labelLayouts = mutableListOf<TextLayoutResult>()
        compose.onNode(
            hasText(
                text(R.string.mobile_translation, languageName(state.options.target.languageTag))
            ) and
                hasAnyAncestor(hasTestTag("alert-modal-card"))
        ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(labelLayouts) }
        assertEquals(1, labelLayouts.single().lineCount)
        savePreview("compact-alert-modal", dialog = true)
        compose.onNodeWithTag("alert-modal-card").performTouchInput { click(center) }
        compose.onNodeWithTag("alert-modal-card").assertIsDisplayed()
        compose.onNodeWithTag("alert-modal-backdrop").performTouchInput {
            click(Offset(2f, 2f))
        }
        compose.onNodeWithTag("alert-modal-card").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_details)).performClick()
        compose.onNodeWithTag("alert-modal-details").performClick()
        compose.onNodeWithTag("history-detail").assertIsDisplayed()
        compose.onNodeWithTag("delete-alert").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun originalAndTranslationActionsUseTheirOwnBodiesWithoutDisplayingTheSourcePackage() {
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        val original = state.detail!!.capture.original
        val translated = state.detail!!.translation!!.text!!
        val originalMenu = text(R.string.mobile_message_actions, text(R.string.mobile_original))
        val translatedMenu = text(
            R.string.mobile_message_actions,
            text(R.string.mobile_translation, languageName("ko"))
        )
        for ((menu, body) in listOf(originalMenu to original, translatedMenu to translated)) {
            compose.onNodeWithContentDescription(menu).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.mobile_copy_body)).performClick()
            compose.runOnIdle { assertEquals(body, copiedBodies.last()) }
            compose.onNodeWithContentDescription(menu).performClick()
            compose.onNodeWithText(text(R.string.mobile_share_body)).performClick()
            compose.runOnIdle { assertEquals(body, sharedBodies.last()) }
        }
        compose.onNodeWithText(state.detail!!.capture.sourcePackage, substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun verifiedKoreanCategoryAppearsAndUnverifiedPeerCategoryStaysHidden() {
        val original = record(1).let {
            it.copy(
                capture = it.capture.copy(
                    context = ReceptionContext(ReceptionCountry("KR", CountryEvidence.CBS_PLMN)),
                    broadcast = BroadcastMetadata(1, 42, 4372, 3, 1, null)
                )
            )
        }
        compose.runOnIdle { state = state.copy(latest = original) }
        compose.onNodeWithTag("mobile-feed").performScrollToNode(hasTestTag("national-alert-type"))
        compose.onNodeWithTag("national-alert-type").assertIsDisplayed()
        compose.onNodeWithTag("alert-symbol-safety").assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(
                latest = original.copy(capture = original.capture.copy(source = CaptureSource.PEER))
            )
        }
        compose.onNodeWithTag("national-alert-type").assertDoesNotExist()
        compose.onNodeWithTag("estimated-alert-type").assertDoesNotExist()
    }

    @Test
    fun reviewedTitleEstimatesShowDistinctHistorySymbolsInBothThemes() {
        val hints = listOf("EXTREME", "SEVERE", "AMBER", "PUBLIC_SAFETY", "TEST", "UNKNOWN")
        val symbols = listOf("critical", "urgent", "missing", "safety", "test", "general")
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            state = state.copy(
                records = hints.mapIndexed { index, hint ->
                    record(index + 1L).let {
                        it.copy(
                            capture = it.capture.copy(
                                sourcePackage = "com.google.android.cellbroadcastreceiver",
                                headingHint = hint
                            )
                        )
                    }
                }
            )
        }
        compose.onNodeWithTag("destination-1").performClick()
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            hints.indices.forEach { index ->
                compose.onNodeWithTag(
                    "mobile-feed"
                ).performScrollToNode(hasTestTag("history-row-${index + 1}"))
                compose.onNodeWithTag(
                    "alert-symbol-${symbols[index]}",
                    useUnmergedTree = true
                ).assertIsDisplayed()
                val estimate = compose.onNode(
                    hasTestTag("estimated-alert-type") and
                        hasAnyAncestor(hasTestTag("history-row-${index + 1}")),
                    useUnmergedTree = true
                )
                if (index ==
                    hints.lastIndex
                ) {
                    estimate.assertDoesNotExist()
                } else {
                    estimate.assertIsDisplayed()
                }
                compose.onNodeWithTag("national-alert-type").assertDoesNotExist()
            }
            compose.onNodeWithTag("mobile-feed").performScrollToNode(hasTestTag("history-row-1"))
            savePreview("alert-symbols-${theme.name.lowercase()}")
        }
    }

    @Test
    fun detailTitleEstimateYieldsToStructuredOfficialEvidence() {
        compose.runOnIdle {
            state = state.copy(
                records = listOf(
                    record(1).let {
                        it.copy(
                            capture = it.capture.copy(
                                sourcePackage = "com.google.android.cellbroadcastreceiver",
                                headingHint = "AMBER"
                            )
                        )
                    }
                )
            )
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithTag("estimated-alert-type").assertIsDisplayed()
        compose.onNodeWithTag("alert-symbol-missing", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            val detail = state.detail!!
            state = state.copy(
                detail = detail.copy(
                    capture = detail.capture.copy(
                        context = ReceptionContext(
                            ReceptionCountry("KR", CountryEvidence.CBS_PLMN)
                        ),
                        broadcast = BroadcastMetadata(1, 42, 4371, 3, 1, null)
                    )
                )
            )
        }
        compose.onNodeWithTag("national-alert-type").assertIsDisplayed()
        compose.onNodeWithTag("estimated-alert-type").assertDoesNotExist()
        compose.onNodeWithTag("alert-symbol-urgent", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun storedCountryUsesAFlagAndTheCurrentDisplayLanguage() {
        val country = ReceptionCountry("KR", CountryEvidence.CBS_PLMN)
        compose.runOnIdle {
            previewLanguage = "fr"
            state = state.copy(
                records = listOf(
                    record(1).let {
                        it.copy(capture = it.capture.copy(context = ReceptionContext(country)))
                    }
                )
            )
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithTag("reception-country").performScrollTo()
            .assertTextContains(country.flag + " " + country.displayName("fr"), substring = true)
        compose.runOnIdle { previewLanguage = "de" }
        compose.onNodeWithTag("reception-country")
            .assertTextContains(country.flag + " " + country.displayName("de"), substring = true)
    }

    @Test
    fun detailLanguageChoicesExcludeTheOriginalAndKeepTheDefaultTarget() {
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        val defaultTarget = state.options.target
        compose.onNodeWithText(
            text(R.string.mobile_target_language)
        ).performScrollTo().performClick()
        val originalLabel = text(R.string.mobile_original_language_choice, "한국어")
        compose.onNode(hasText(originalLabel) and isRadioChoice()).assertIsNotEnabled()
        compose.onNode(hasText("Français") and isRadioChoice()).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("fr", state.detailTarget)
            assertEquals(defaultTarget, state.options.target)
            assertEquals(0, originalLanguageRequests)
            assertEquals(0, prepared)
        }
    }

    @Test
    fun unknownOriginalLanguageDisablesChoicesUntilIdentificationCompletes() {
        compose.runOnIdle {
            state = state.copy(records = listOf(record(1).copy(translation = null)))
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithText(
            text(R.string.mobile_target_language)
        ).performScrollTo().performClick()
        compose.onNode(hasText("English") and isRadioChoice()).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, originalLanguageRequests)
            originalLanguageReply!!.invoke("en")
        }
        val original = text(R.string.mobile_original_language_choice, "English")
        compose.onNode(hasText(original) and isRadioChoice()).performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText("Deutsch") and isRadioChoice()).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("de", state.detailTarget) }
    }

    @Test
    fun detailsGroupMetadataAndExposeAFullWidthDeleteAction() {
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithTag("alert-metadata").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_official_unknown)).assertDoesNotExist()
        compose.onNodeWithText(
            text(R.string.mobile_estimate, text(R.string.mobile_unknown))
        ).assertDoesNotExist()
        val button = compose.onNodeWithTag("delete-alert").performScrollTo()
        val width = compose.onNodeWithTag(
            "history-detail-body"
        ).fetchSemanticsNode().boundsInRoot.width
        assertTrue(button.fetchSemanticsNode().boundsInRoot.width >= width * 0.85f)
        button.performTouchInput { click(Offset(8f, centerY)) }
        compose.runOnIdle {
            assertEquals(1, deletionRequests)
            assertEquals(1L, deletedAlertId)
        }
    }

    @Test
    fun singleDialogCloseUsesTheEntireActionWidth() {
        openSettingsPage(SettingsPage.PRIVACY)
        compose.onNodeWithText(text(R.string.mobile_data_storage)).performScrollTo().performClick()
        compose.onNodeWithTag("confirmation-card").performTouchInput { click(Offset(centerX, 4f)) }
        compose.onNodeWithTag("confirmation-actions").assertIsDisplayed()
        compose.onNodeWithTag("confirmation-backdrop").performTouchInput { click(Offset(2f, 2f)) }
        compose.onNodeWithTag("confirmation-actions").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_data_storage)).performClick()
        val actionsWidth = compose.onNodeWithTag(
            "confirmation-actions"
        ).fetchSemanticsNode().boundsInRoot.width
        val close = compose.onNodeWithText(text(R.string.mobile_close))
        assertEquals(actionsWidth, close.fetchSemanticsNode().boundsInRoot.width, 1f)
        close.performTouchInput { click(Offset(8f, centerY)) }
        compose.onNodeWithTag("confirmation-actions").assertDoesNotExist()
        for (language in listOf("en", "ko", "ja", "zh-Hans", "zh-Hant", "fr", "de")) {
            compose.runOnIdle { previewLanguage = language }
            compose.onNodeWithTag("privacy-policy-link").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(
                    "https://gaon12.github.io/CodeClear/privacy/" +
                        if (language == "ko") "ko.html" else "",
                    projectLinks.last()
                )
            }
        }
        compose.runOnIdle { projectLinkFailure = true }
        compose.onNodeWithTag("privacy-policy-link").performClick()
        compose.onNodeWithTag("settings-detail-title").assertExists()
        compose.runOnIdle {
            assertEquals(0, deletionRequests)
            assertEquals(0, serverAllows)
            assertTrue(requestedModels.isEmpty())
        }
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
}
