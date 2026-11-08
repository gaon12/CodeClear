package xyz.gaon.codeclear.mobile

import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.R

/** History rows, row actions, grouping, search, and history navigation. */
class MobileScreenHistoryDeviceTest : MobileScreenFixture() {
    @Test
    fun swipingRevealsTwoActionsWithoutDeletingAndSharingClosesTheRow() {
        compose.runOnIdle { context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("history-share-1").assertIsDisplayed()
        compose.onNodeWithTag("history-delete-1").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, deletionRequests)
            assertEquals(null, state.detailId)
        }
        savePreview("history-actions-revealed")
        compose.onNodeWithTag("history-share-1").performClick()
        compose.onNodeWithTag("history-share-1").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, sharedBodies.size)
            assertTrue(sharedBodies.single().contains(state.records.first().capture.original))
            assertTrue(sharedBodies.single().contains(state.records.first().translation!!.text!!))
        }
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithTag("history-detail").assertIsDisplayed()
    }

    @Test
    fun longPressActionsShareDeleteAndCancelWithoutOpeningDetails() {
        compose.runOnIdle { context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-row-1").performTouchInput { longClick() }
        compose.onNodeWithTag("history-action-menu").assertIsDisplayed()
        savePreview("history-actions-menu", dialog = true)
        compose.onNodeWithText(text(R.string.mobile_close)).performClick()
        compose.onNodeWithTag("history-action-menu").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, deletionRequests)
            assertEquals(null, state.detailId)
        }
        compose.onNodeWithTag("history-row-1").performTouchInput { longClick() }
        compose.onNode(
            hasText(text(R.string.mobile_delete)) and
                hasAnyAncestor(hasTestTag("history-action-menu"))
        ).performClick()
        compose.onNodeWithTag("history-action-menu").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, deletionRequests)
            assertEquals(1L, deletedAlertId)
        }
        compose.onNodeWithTag("history-row-1").performTouchInput { longClick() }
        compose.onNode(
            hasText(text(R.string.mobile_share)) and
                hasAnyAncestor(hasTestTag("history-action-menu"))
        ).performClick()
        compose.runOnIdle {
            assertEquals(1, sharedBodies.size)
            assertEquals(null, state.detailId)
        }
    }

    @Test
    fun accessibilityActionsAndVerticalScrollingWorkWhileOnlyOneRowIsRevealed() {
        compose.runOnIdle { state = state.copy(records = List(20) { record(it + 1L) }) }
        compose.onNodeWithTag("destination-1").performClick()
        val custom = compose.onNodeWithTag("history-row-1").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(
            listOf(text(R.string.mobile_delete), text(R.string.mobile_share)),
            custom.map { it.label }
        )
        compose.runOnIdle { assertTrue(custom[1].action()) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, sharedBodies.size) }
        compose.onNodeWithTag("history-row-1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("history-delete-1").assertIsDisplayed()
        compose.onNodeWithTag("history-row-2").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("history-delete-2").assertIsDisplayed()
        compose.onNodeWithTag("history-delete-1").assertDoesNotExist()
        compose.onNodeWithTag("history-row-2").performTouchInput { swipeRight() }
        compose.onNodeWithTag("history-delete-2").assertDoesNotExist()
        val axis = compose.onNodeWithTag("mobile-feed").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        val before = axis.value()
        compose.onNodeWithTag("history-row-2").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue(axis.value() > before)
            assertEquals(0, deletionRequests)
            assertEquals(null, state.detailId)
        }
    }

    @Test
    fun largeTextHistoryActionsStayVisibleInBothThemes() {
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            fontScale = 1.8f
        }
        compose.onNodeWithTag("destination-1").performClick()
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            compose.onNodeWithTag("history-row-1").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("history-share-1").assertIsDisplayed()
            compose.onNodeWithTag("history-delete-1").assertIsDisplayed()
            savePreview("history-actions-large-${theme.name.lowercase()}")
            compose.onNodeWithTag("history-row-1").performTouchInput { swipeRight() }
            compose.onNodeWithTag("history-delete-1").assertDoesNotExist()
        }
    }

    @Test
    fun firstInstallHistoryShowsAnEmptyStateAndPagingOnlyWhenUseful() {
        compose.runOnIdle { state = state.copy(records = emptyList(), latest = null) }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-empty").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_history_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_history_empty_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_no_results)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_search)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_newest)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_older)).assertDoesNotExist()

        compose.runOnIdle { state = state.copy(records = listOf(record(1))) }
        compose.onNodeWithTag("history-empty").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_search)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_newest)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_older)).assertDoesNotExist()

        compose.runOnIdle { state = state.copy(records = List(50) { record(it + 1L) }) }
        compose.onNodeWithTag(
            "mobile-feed"
        ).performScrollToNode(hasText(text(R.string.mobile_older)))
        compose.onNodeWithText(text(R.string.mobile_older)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_newest)).assertDoesNotExist()

        compose.runOnIdle { state = state.copy(beforeId = 51, records = listOf(record(1))) }
        compose.onNodeWithTag(
            "mobile-feed"
        ).performScrollToNode(hasText(text(R.string.mobile_newest)))
        compose.onNodeWithText(text(R.string.mobile_newest)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, newestRequests) }

        compose.onNodeWithText(text(R.string.mobile_search)).performTextInput("Gate 9")
        compose.runOnIdle { state = state.copy(records = emptyList(), loading = false) }
        compose.onNodeWithTag("history-no-results").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_no_results)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_no_results_body, "Gate 9")).assertIsDisplayed()
        compose.onNodeWithTag("history-empty").assertDoesNotExist()
    }

    @Test
    fun modelPreparationStatusStaysInModelManagementInsteadOfFeedsAndSettings() {
        val bareStatus = listOf(
            R.string.mobile_working,
            R.string.mobile_downloading,
            R.string.mobile_models_ready,
            R.string.mobile_unknown_language,
            R.string.mobile_failed
        ).map(::text)
        fun assertNoBareStatus() = bareStatus.forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        for (phase in PreparationPhase.entries - PreparationPhase.IDLE) {
            compose.runOnIdle {
                modelPreparation = PreparationState(phase, target = "de", modelLanguage = "de")
            }
            compose.onNodeWithTag("destination-0").performClick()
            assertNoBareStatus()
            compose.onNodeWithTag("destination-1").performClick()
            assertNoBareStatus()
        }
        compose.runOnIdle { modelPreparation = PreparationState(PreparationPhase.READY) }
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithTag("history-detail-body").performScrollToNode(hasTestTag("delete-alert"))
        compose.onNodeWithTag("delete-alert").assertIsDisplayed()
        assertNoBareStatus()
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithTag("settings-detail").performScrollToIndex(1)
        compose.onNodeWithTag("model-management").assertExists()
        compose.onNodeWithText(text(R.string.mobile_models_ready)).assertDoesNotExist()
        compose.runOnIdle { assertTrue(requestedModels.isEmpty()) }
    }

    @Test
    fun groupedHistoryPreservesVisibleObservationTimesAndPaginationActions() {
        compose.runOnIdle {
            val saved = record(1).copy(
                observationCount = 24,
                moreObservations = true,
                observations = listOf(
                    HistoryObservation(
                        12,
                        CaptureSource.NOTIFICATION,
                        1700000002000,
                        1700000001000,
                        null
                    ),
                    HistoryObservation(
                        11,
                        CaptureSource.ORIGINAL_NOTIFICATION,
                        1700000000000,
                        null,
                        1699999999000
                    )
                )
            )
            state = state.copy(records = listOf(saved), latest = saved, observationBeforeId = 100)
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_observation_count, 24)).assertIsDisplayed()
        compose.onNodeWithTag("history-row-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_observations)).performScrollTo().performClick()
        compose.onNodeWithTag("observation-12").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("observation-11").performScrollTo().assertIsDisplayed()
        compose.onNode(
            hasText(text(R.string.mobile_older)) and
                hasAnyAncestor(hasTestTag("observation-history"))
        ).performScrollTo().performClick()
        compose.onNode(
            hasText(text(R.string.mobile_newest)) and
                hasAnyAncestor(hasTestTag("observation-history"))
        ).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, olderObservationRequests)
            assertEquals(1, newestObservationRequests)
        }
    }

    @Test
    fun reselectingADestinationReturnsToItsRootAndPreservesTheHistoryQuery() {
        compose.runOnIdle { state = state.copy(records = List(24) { record(it.toLong() + 1) }) }
        fun offset(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[
            SemanticsProperties.VerticalScrollAxisRange
        ].value()
        compose.onNodeWithTag(
            "mobile-feed"
        ).performScrollToNode(hasText(text(R.string.mobile_manage_access)))
        val homeOffset = offset("mobile-feed")
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_search)).performTextInput("Gate_3%")
        compose.runOnIdle { state = state.copy(beforeId = 50) }
        compose.onNodeWithTag("mobile-feed").performScrollToIndex(5)
        assertTrue(offset("mobile-feed") > 0f)
        compose.onNodeWithTag("destination-1").performClick()
        compose.runOnIdle {
            assertEquals(1, newestRequests)
            assertEquals(Long.MAX_VALUE, state.beforeId)
            assertEquals("Gate_3%", searched)
            assertEquals("Gate_3%", state.query)
        }
        assertEquals(0f, offset("mobile-feed"), 0.01f)
        compose.onNodeWithTag("destination-1").performClick()
        compose.runOnIdle { assertEquals(1, newestRequests) }
        compose.onNodeWithTag("destination-0").performClick()
        assertEquals(homeOffset, offset("mobile-feed"), 0.01f)
        compose.onNodeWithTag("destination-0").performClick()
        assertEquals(0f, offset("mobile-feed"), 0.01f)
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithTag(
            "settings-detail-title"
        ).assertTextEquals(text(R.string.mobile_translation_settings))
        compose.onNodeWithTag("destination-2").performClick()
        compose.onNodeWithTag("settings-menu").assertIsDisplayed()
        assertEquals(0f, offset("settings-menu"), 0.01f)
        if (compose.onAllNodesWithTag("settings-split").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("settings-detail").assertDoesNotExist()
        } else {
            compose.onNodeWithTag("settings-category-CAPTURE").assertIsSelected()
        }
        openSettingsPage(SettingsPage.CAPTURE)
        compose.onNodeWithText(text(R.string.mobile_tutorial)).performScrollTo().performClick()
        compose.onNodeWithTag("destination-2").performClick()
        compose.onNodeWithTag("settings-menu").assertIsDisplayed()
        assertEquals(0f, offset("settings-menu"), 0.01f)
        compose.runOnIdle { assertEquals(0, prepared) }
    }

    @Test
    fun compactHistoryPreviewsPreserveUnicodeAndOpenTheFullSelectedRecord() {
        val original = "[DRILL] Long original. ".repeat(120)
        val translated = "[DRILL]  " + "🚨".repeat(400) + " Full translation tail."
        val first = record(1).let {
            it.copy(
                capture = it.capture.copy(original = original),
                translation = it.translation!!.copy(text = translated)
            )
        }
        compose.runOnIdle { state = state.copy(records = listOf(first)) }
        compose.onNodeWithTag("destination-1").performClick()
        val preview = compose.onNodeWithTag("history-preview-1", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertTrue(preview.length < translated.length)
        assertTrue(
            "List previews must preserve complete Unicode characters",
            Charsets.UTF_8.newEncoder().canEncode(preview)
        )
        compose.onNodeWithText(translated).assertDoesNotExist()
        compose.runOnIdle { fontScale = 1.8f }
        compose.onNodeWithTag("history-row-1").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1L, state.detailId)
            state = state.copy(latest = record(2), records = listOf(record(2), first))
        }
        compose.onNodeWithText(original).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(translated).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history-detail-back").assertIsDisplayed().performClick()
        compose.onNodeWithTag("history-row-2").assertIsDisplayed()
    }

    @Test
    fun historyDetailsUseChildPagesAndRestoreTheListBeforeRootReselection() {
        compose.runOnIdle { state = state.copy(records = List(24) { record(it.toLong() + 1) }) }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_search)).performTextInput("Gate_3%")
        compose.runOnIdle { state = state.copy(beforeId = 50) }
        compose.onNodeWithTag("mobile-feed").performScrollToIndex(8)
        compose.onNodeWithTag("history-row-8").performScrollTo()
        val listOffset = compose.onNodeWithTag("mobile-feed").fetchSemanticsNode().config[
            SemanticsProperties.VerticalScrollAxisRange
        ].value()
        assertTrue(listOffset > 0f)
        compose.onNodeWithTag("history-row-8").performClick()
        compose.onNodeWithTag("history-detail").assertIsDisplayed()
        compose.onNodeWithTag("mobile-feed").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_close)).assertDoesNotExist()
        val heading = compose.onNodeWithTag(
            "history-detail-title"
        ).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(text(R.string.mobile_delete)).performScrollTo()
        assertEquals(
            heading.top,
            compose.onNodeWithTag("history-detail-title").fetchSemanticsNode().boundsInRoot.top,
            1f
        )
        val detailOffset = compose.onNodeWithTag("history-detail-body").fetchSemanticsNode().config[
            SemanticsProperties.VerticalScrollAxisRange
        ].value()
        compose.onNodeWithTag("destination-0").performClick()
        compose.onNodeWithTag("history-detail").assertDoesNotExist()
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-detail").assertIsDisplayed()
        assertEquals(
            detailOffset,
            compose.onNodeWithTag("history-detail-body").fetchSemanticsNode().config[
                SemanticsProperties.VerticalScrollAxisRange
            ].value(),
            0.01f
        )
        compose.runOnUiThread { context.onBackPressedDispatcher.onBackPressed() }
        val restoredOffset = compose.onNodeWithTag("mobile-feed").fetchSemanticsNode().config[
            SemanticsProperties.VerticalScrollAxisRange
        ].value()
        assertEquals(listOffset, restoredOffset, 0.01f)
        compose.runOnIdle {
            assertEquals("Gate_3%", state.query)
            assertEquals(50L, state.beforeId)
            assertEquals(0, newestRequests)
            assertEquals(null, state.detailId)
        }
        compose.onNodeWithTag("history-row-8").performClick()
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithTag("history-detail").assertDoesNotExist()
        assertEquals(
            0f,
            compose.onNodeWithTag("mobile-feed").fetchSemanticsNode().config[
                SemanticsProperties.VerticalScrollAxisRange
            ].value(),
            0.01f
        )
        compose.runOnIdle {
            assertEquals(1, newestRequests)
            assertEquals(Long.MAX_VALUE, state.beforeId)
            assertEquals("Gate_3%", state.query)
            assertEquals(null, state.detailId)
        }
        compose.onNodeWithTag("history-row-1").performClick()
        compose.runOnIdle { state = state.copy(detail = null, records = emptyList()) }
        compose.onNodeWithText(text(R.string.mobile_deleted)).assertIsDisplayed()
        compose.onNodeWithTag("history-detail-back").assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.mobile_no_results)).assertIsDisplayed()
    }

    @Test
    fun historySearchUsesPlaceholdersUntilTheCurrentResultsArrive() {
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            previewLanguage = "ko"
        }
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_search)).performTextInput("Gate_3%")
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle {
                state = state.copy(loading = true, options = state.options.copy(theme = theme))
            }
            compose.onNodeWithTag("history-loading").assertIsDisplayed()
            compose.onNodeWithTag("history-row-1").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.mobile_no_results)).assertDoesNotExist()
            compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
            ).assertCountEquals(0)
            savePreview("history-loading-${theme.name.lowercase()}")
        }
        compose.runOnIdle { state = state.copy(loading = false, records = emptyList()) }
        compose.onNodeWithTag("history-loading").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_no_results)).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(loading = true) }
        compose.onNodeWithText(text(R.string.mobile_no_results)).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = false, records = listOf(record(2))) }
        compose.onNodeWithTag("history-loading").assertDoesNotExist()
        compose.onNodeWithTag("history-row-2").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("Gate_3%", state.query)
            assertEquals(2L, state.detailId)
            assertEquals(0, prepared)
        }
    }

    @Test
    fun historyDispatchesLiteralSearchAndModelsRequireAnExplicitAction() {
        compose.onNodeWithTag("destination-1").performClick()
        compose.onNodeWithText(text(R.string.mobile_search)).performTextInput("Gate_3%")
        compose.runOnIdle {
            assertEquals("Gate_3%", searched)
            val missing = record(
                1
            ).copy(translation = SavedTranslation(TranslationPhase.NEEDS_MODELS))
            state = state.copy(records = listOf(missing))
        }
        compose.onNodeWithTag("history-row-1").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_prepare)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, prepared) }
    }
}
