package xyz.gaon.codeclear.mobile

import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.ServerAccessState
import xyz.gaon.codeclear.translation.SystemEngineAvailability
import xyz.gaon.codeclear.translation.SystemLanguagePair
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationTarget

/** Translation engine selection, remote consent, owned service access, and system pairs. */
class MobileScreenEngineDeviceTest : MobileScreenFixture() {
    @Test
    fun ownedServiceStaysHiddenWithoutBuildConfigurationAndMyMemoryCannotAuthorizeIt() {
        openSettingsPage(SettingsPage.LABS)
        compose.onNodeWithText(text(R.string.mobile_owned_server)).assertDoesNotExist()
        switchControl(text(R.string.mobile_labs_enable)).assertIsOff().performClick()
        compose.runOnIdle {
            serverAccess = ServerAccessState(configured = true, loaded = true)
            state = state.copy(options = state.options.copy(serverAllowed = true))
        }
        compose.onNodeWithText(text(R.string.mobile_owned_server)).performClick()
        compose.onNodeWithText(text(R.string.mobile_owned_access_missing)).assertIsDisplayed()
        switchControl(text(R.string.mobile_owned_permission)).assertIsNotEnabled()
        compose.runOnIdle {
            assertFalse(state.options.ownedServerAllowed)
            assertEquals(0, serverAllows)
        }
    }

    @Test
    fun accessTokenDialogMasksValidatesAndForgetsInputWithoutEnablingTranslation() {
        compose.runOnIdle { serverAccess = ServerAccessState(configured = true, loaded = true) }
        openSettingsPage(SettingsPage.OWNED_SERVER)
        compose.onNodeWithText(text(R.string.mobile_owned_access)).performClick()
        compose.onNodeWithTag("owned-server-token-save").assertIsNotEnabled()
        val synthetic = "synthetic-client-token-aaaaaaaaaaaaaaaa"
        compose.onNodeWithTag("owned-server-token").performTextInput(synthetic)
        assertTrue(
            compose.onNodeWithTag("owned-server-token").fetchSemanticsNode()
                .config.contains(SemanticsProperties.Password)
        )
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.onNodeWithText(text(R.string.mobile_owned_access)).performClick()
        assertEquals(
            "",
            compose.onNodeWithTag("owned-server-token").fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text
        )
        compose.onNodeWithTag("owned-server-token").performTextInput(synthetic)
        compose.onNodeWithTag("owned-server-token-save").performClick()
        compose.onNodeWithText(text(R.string.mobile_owned_access_saved)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(synthetic), serverTokens)
            assertFalse(state.options.ownedServerAllowed)
            assertEquals(TranslationEngineKind.ML_KIT, state.options.engine)
        }
    }

    @Test
    fun ownedPermissionReviewRejectsChangedCredentialsAndRequiresFreshConfirmation() {
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            serverAccess = ServerAccessState(
                configured = true,
                loaded = true,
                ready = true,
                credentialId = "a".repeat(64)
            )
        }
        openSettingsPage(SettingsPage.OWNED_SERVER)
        switchControl(text(R.string.mobile_owned_permission)).performClick()
        compose.onNodeWithTag("owned-server-confirm").assertIsEnabled()
        savePreview("owned-server-review", dialog = true)
        compose.runOnIdle {
            serverAccess = serverAccess.copy(revision = 1, credentialId = "b".repeat(64))
        }
        compose.onNodeWithTag("owned-server-confirm").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        switchControl(text(R.string.mobile_owned_permission)).performClick()
        compose.runOnIdle { state = state.copy(options = state.options.copy(labsEnabled = false)) }
        compose.onNodeWithTag("owned-server-review").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, serverAllows) }
        openSettingsPage(SettingsPage.OWNED_SERVER)
        switchControl(text(R.string.mobile_owned_permission)).performClick()
        compose.onNodeWithTag("owned-server-confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, serverAllows)
            assertEquals(TranslationEngineKind.OWNED_SERVER, state.options.engine)
            assertEquals("b".repeat(64), state.options.ownedServerCredentialId)
            assertTrue(state.options.translationAllowed)
        }
    }

    @Test
    fun ownedEngineChoiceNeedsItsPermissionAndMatchingReadyCredential() {
        compose.runOnIdle {
            serverAccess = ServerAccessState(
                configured = true,
                loaded = true,
                ready = true,
                credentialId = "a".repeat(64)
            )
            state =
                state.copy(options = state.options.copy(labsEnabled = true, serverAllowed = true))
        }
        openSettingsPage(SettingsPage.TRANSLATION)
        compose.onNodeWithText(text(R.string.mobile_engine_title)).performClick()
        val choice = hasText(text(R.string.mobile_owned_server)) and isRadioChoice()
        compose.onNode(choice).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_close)).performClick()
        compose.runOnIdle {
            state = state.copy(
                options = state.options.copy(
                    ownedServerAllowed = true,
                    ownedServerCredentialId = "a".repeat(64)
                )
            )
        }
        compose.onNodeWithText(text(R.string.mobile_engine_title)).performClick()
        compose.onNode(choice).assertIsEnabled()
        compose.runOnIdle { serverAccess = serverAccess.copy(ready = false) }
        compose.onNode(choice).assertDoesNotExist()
        compose.runOnIdle {
            serverAccess =
                serverAccess.copy(ready = true, credentialId = "b".repeat(64))
        }
        compose.onNode(choice).assertDoesNotExist()
        compose.runOnIdle { serverAccess = serverAccess.copy(credentialId = "a".repeat(64)) }
        compose.onNode(choice).assertIsEnabled()
        compose.runOnIdle { state = state.copy(options = state.options.copy(labsEnabled = false)) }
        compose.onNode(choice).assertDoesNotExist()
    }

    @Test
    fun serverCacheAndAccessRemovalRequireConfirmationAndKeepLocalHistory() {
        compose.runOnIdle {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            serverAccess = ServerAccessState(
                configured = true,
                loaded = true,
                ready = true,
                credentialId = "a".repeat(64)
            )
            state =
                state.copy(
                    options = state.options.copy(
                        engine = TranslationEngineKind.OWNED_SERVER,
                        labsEnabled = true,
                        ownedServerAllowed = true,
                        ownedServerCredentialId = "a".repeat(64)
                    )
                )
        }
        openSettingsPage(SettingsPage.OWNED_SERVER)
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            compose.runOnIdle { state = state.copy(options = state.options.copy(theme = theme)) }
            savePreview("owned-server-settings-${theme.name.lowercase()}")
        }
        compose.onNodeWithText(text(R.string.mobile_owned_cache_clear)).performClick()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, serverCacheClears) }
        compose.onNodeWithText(text(R.string.mobile_owned_cache_clear)).performClick()
        compose.onNodeWithTag("owned-server-confirm").performClick()
        compose.onNodeWithText(text(R.string.mobile_owned_cache_cleared))
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, serverCacheClears)
            assertFalse(state.options.ownedServerAllowed)
            assertTrue(serverAccess.ready)
        }
        compose.onNodeWithText(text(R.string.mobile_owned_access_remove)).performClick()
        compose.onNodeWithTag("owned-server-confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, serverRemovals)
            assertFalse(serverAccess.ready)
            assertEquals(1, state.records.size)
            assertEquals(0, deletionRequests)
        }
    }

    @Test
    fun systemModelsUseTheirSettingsActionAndResultsNameTheActualProducer() {
        compose.runOnIdle {
            state = state.copy(
                options = state.options.copy(engine = TranslationEngineKind.SYSTEM),
                latest = record(1).copy(
                    translation = SavedTranslation(
                        TranslationPhase.NEEDS_MODELS,
                        engine = TranslationEngineKind.SYSTEM
                    )
                )
            )
        }
        compose.onNodeWithText(
            text(R.string.mobile_system_models_action)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_retry)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, prepared)
            assertEquals(TranslationEngineKind.SYSTEM, preparedEngine)
            assertEquals(1, retried)
            assertEquals(TranslationEngineKind.SYSTEM, retriedEngine)
            state = state.copy(
                latest = record(1).copy(
                    translation = SavedTranslation(
                        TranslationPhase.READY,
                        "[DRILL] System result",
                        "en",
                        engine = TranslationEngineKind.SYSTEM,
                        sameLanguage = true
                    )
                )
            )
        }
        compose.onNodeWithText(
            text(R.string.mobile_result_system)
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.mobile_result_same_language)
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun serverTranslationNeedsSeparatePermissionAndCanBeWithdrawnWithoutChangingLocalPermission() {
        compose.runOnIdle {
            state =
                state.copy(options = state.options.copy(labsEnabled = true, localConsent = true))
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        openSettingsPage(SettingsPage.SERVER)
        compose.onNodeWithText(
            text(R.string.mobile_server_permission)
        ).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText(text(R.string.mobile_server_disclosure)).assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(state.options.serverAllowed)
            assertEquals(TranslationEngineKind.ML_KIT, state.options.engine)
        }
        savePreview("remote-permission", dialog = true)
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.onNodeWithText(text(R.string.mobile_server_permission)).performClick()
        compose.onNodeWithText(text(R.string.mobile_allow)).performClick()
        compose.runOnIdle {
            assertTrue(state.options.serverAllowed)
            assertTrue(state.options.localConsent)
            assertEquals(TranslationEngineKind.MY_MEMORY, state.options.engine)
            assertTrue(state.options.translationAllowed)
            assertTrue(requestedModels.isEmpty())
        }
        compose.onNodeWithText(
            text(R.string.mobile_server_permission)
        ).assertIsOn().performClick().assertIsOff()
        compose.runOnIdle {
            assertFalse(state.options.serverAllowed)
            assertFalse(state.options.translationAllowed)
            assertTrue(state.options.localConsent)
        }
        compose.onNodeWithText(text(R.string.mobile_server_terms)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("https://mymemory.translated.net/terms-and-conditions"),
                projectLinks
            )
        }
    }

    @Test
    fun remoteResultsRetainTheirProviderAndShowQuotaFailures() {
        compose.runOnIdle {
            state =
                state.copy(
                    options = state.options.copy(
                        engine = TranslationEngineKind.MY_MEMORY,
                        serverAllowed = true,
                        labsEnabled = true
                    ),
                    latest = record(
                        1
                    ).copy(
                        translation = SavedTranslation(
                            TranslationPhase.READY,
                            "[DRILL] Remote translation",
                            "ko",
                            engine = TranslationEngineKind.MY_MEMORY
                        )
                    )
                )
        }
        compose.onNodeWithText(
            text(R.string.mobile_result_mymemory)
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_result_mlkit)).assertDoesNotExist()
        compose.runOnIdle {
            state =
                state.copy(
                    latest = state.latest!!.copy(
                        translation = SavedTranslation(
                            TranslationPhase.QUOTA,
                            engine = TranslationEngineKind.MY_MEMORY
                        )
                    )
                )
        }
        compose.onNodeWithText(
            text(R.string.mobile_remote_quota)
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun engineChangesExplainLocalProcessingAndPreserveTheDisabledChoice() {
        compose.runOnIdle {
            state =
                state.copy(
                    options = state.options.copy(
                        localConsent = false,
                        target = TranslationTarget.KOREAN
                    )
                )
            systemSupport =
                SystemEngineAvailability(
                    true,
                    false,
                    true,
                    listOf(SystemLanguagePair("en", "ko", true))
                )
        }
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).performClick()
        compose.onNodeWithText(text(R.string.mobile_system_disclosure)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(TranslationEngineKind.ML_KIT, state.options.engine) }
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).performClick()
        compose.onNodeWithText(text(R.string.mobile_change)).performClick()
        compose.runOnIdle {
            assertEquals(TranslationEngineKind.SYSTEM, state.options.engine)
            assertEquals(false, state.options.localConsent)
        }
        compose.onNodeWithText(
            text(R.string.mobile_auto_translation)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_system_disclosure)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mobile_enable)).performClick()
        compose.runOnIdle { assertEquals(true, state.options.localConsent) }
    }

    @Test
    fun wrongTargetsStayUnavailableAndSettingsActionsDoNotChangeChoices() {
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(target = TranslationTarget.KOREAN))
            systemSupport =
                SystemEngineAvailability(
                    true,
                    false,
                    true,
                    listOf(SystemLanguagePair("en", "fr", true))
                )
        }
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).assertIsNotSelected().assertIsNotEnabled()
        compose.onNodeWithText(
            text(R.string.mobile_engine_pair_missing, languageName("ko"))
        ).assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.mobile_engine_refresh)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_close)).performClick()
        compose.onNodeWithText(
            text(R.string.mobile_system_models_action)
        ).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, manualSupportRefreshes)
            assertEquals(1, providerSettingsOpened)
            assertEquals(TranslationEngineKind.ML_KIT, state.options.engine)
            assertEquals(true, state.options.localConsent)
            assertEquals(0, prepared)
        }
    }

    @Test
    fun systemEngineExplainsNoPublicPairsAndQueryFailuresWithoutEnablingStaleSupport() {
        compose.runOnIdle {
            systemSupport =
                SystemEngineAvailability(loaded = true, platformSupported = true)
        }
        openEnginePicker()
        compose.onNodeWithText(text(R.string.mobile_engine_no_pairs)).assertIsDisplayed()
        compose.runOnIdle {
            systemSupport = SystemEngineAvailability(
                true,
                false,
                true,
                listOf(SystemLanguagePair("en", state.options.target.languageTag, true)),
                queryFailed = true
            )
        }
        compose.onNodeWithText(text(R.string.mobile_engine_query_failed)).assertIsDisplayed()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).assertIsNotSelected()
        compose.runOnIdle { systemSupport = SystemEngineAvailability(loaded = true) }
        compose.onNodeWithText(text(R.string.mobile_engine_unavailable)).assertIsDisplayed()
    }

    @Test
    fun unavailableSystemEngineExplainsItsReasonAndKeepsSettingsOutsideThePicker() {
        compose.runOnIdle {
            systemSupport =
                SystemEngineAvailability(loaded = true, platformSupported = true)
        }
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.mobile_engine_no_pairs)).assertIsDisplayed()
        compose.onNode(
            hasText(text(R.string.mobile_system_models_action)) and
                hasAnyAncestor(hasTestTag("engine-picker"))
        ).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mobile_close)).performClick()
        compose.onNodeWithText(
            text(R.string.mobile_system_models_action)
        ).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(TranslationEngineKind.ML_KIT, state.options.engine)
            assertEquals(1, providerSettingsOpened)
            assertEquals(0, prepared)
        }
    }

    @Test
    fun disabledRemoteConsentHidesEngineAndBlocksStaleConfirmation() {
        val remote = hasText(text(R.string.mobile_engine_mymemory)) and isRadioChoice()
        openEnginePicker()
        compose.onAllNodes(remote).assertCountEquals(0)
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(labsEnabled = true))
        }
        compose.onAllNodes(remote).assertCountEquals(0)
        compose.runOnIdle {
            assertFalse(state.options.serverAllowed)
            state = state.copy(options = state.options.copy(serverAllowed = true))
        }
        compose.onNode(remote).assertIsEnabled()
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(labsEnabled = false))
        }
        compose.onAllNodes(remote).assertCountEquals(0)
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(labsEnabled = true))
        }
        compose.onNode(remote).assertIsEnabled().performClick()
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(serverAllowed = false))
        }
        compose.onNodeWithText(text(R.string.mobile_allow)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.runOnIdle {
            assertEquals(TranslationEngineKind.ML_KIT, state.options.engine)
            assertFalse(state.options.serverAllowed)
        }
    }

    @Test
    fun aDisappearingProviderCannotBeConfirmedThroughAnOlderDisclosure() {
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(target = TranslationTarget.KOREAN))
            systemSupport =
                SystemEngineAvailability(
                    true,
                    false,
                    true,
                    listOf(SystemLanguagePair("en", "ko", true))
                )
        }
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).performClick()
        compose.runOnIdle { systemSupport = SystemEngineAvailability(loaded = true) }
        compose.onNodeWithText(text(R.string.mobile_change)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.mobile_cancel)).performClick()
        compose.runOnIdle {
            assertEquals(TranslationEngineKind.ML_KIT, state.options.engine)
            assertEquals(true, state.options.localConsent)
        }
    }

    @Test
    fun unpreparedPairsUseProviderSettingsAndTurningOffAlwaysRemainsAvailable() {
        compose.runOnIdle {
            state = state.copy(options = state.options.copy(target = TranslationTarget.KOREAN))
            systemSupport =
                SystemEngineAvailability(
                    true,
                    false,
                    true,
                    listOf(SystemLanguagePair("en", "ko", false))
                )
        }
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).performClick()
        compose.onNodeWithText(text(R.string.mobile_change)).performClick()
        compose.onNodeWithText(
            text(R.string.mobile_engine_pairs, languageName("ko"), 0, 1)
        )
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.mobile_system_models_action)
        ).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, providerSettingsOpened)
            assertEquals(0, prepared)
            systemSupport = SystemEngineAvailability(loaded = true)
        }
        compose.onNodeWithText(
            text(R.string.mobile_auto_translation)
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mobile_auto_translation)).assertIsNotEnabled()
        openEnginePicker()
        compose.onNode(
            hasText(text(R.string.mobile_engine_system)) and isRadioChoice()
        ).assertIsSelected()
        compose.onNode(
            hasText(text(R.string.mobile_engine_mlkit)) and isRadioChoice()
        ).performClick()
        compose.onNodeWithText(text(R.string.mobile_change)).performClick()
        compose.runOnIdle {
            assertEquals(TranslationEngineKind.ML_KIT, state.options.engine)
            assertEquals(false, state.options.localConsent)
        }
    }
}
