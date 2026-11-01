package xyz.gaon.codeclear.mobile

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.translation.TranslationTarget

class ModelPreparationDeviceTest {
    private class Backend : ModelPreparationBackend {
        var identification: ((String?) -> Unit)? = null
        var completion: ((Boolean) -> Unit)? = null
        var languages = emptySet<String>()
        var identifications = 0
        override fun identify(body: String, complete: (String?) -> Unit) {
            identifications++
            identification = complete
        }
        override fun download(languages: Set<String>, complete: (Boolean) -> Unit) {
            this.languages = languages
            completion = complete
        }
    }

    @Test
    fun explicitModelManagementSharesTheSlotWithoutEnablingAutomaticTranslation() = onMain {
        val backend = Backend()
        val owner = ModelPreparation(backend, {}, { error("Model inventory has no alert input") })
        assertFalse(owner.prepareModel("ko", false))
        assertTrue(owner.prepareModel("ko", true))
        assertEquals(setOf("ko"), backend.languages)
        assertFalse(owner.prepareModel("de", true))
        owner.prepare("[DRILL] Synthetic", TranslationTarget.FRENCH, true)
        assertEquals(0, backend.identifications)
        owner.withdraw()
        assertEquals("ko", owner.state.modelLanguage)
        backend.completion!!(true)
        assertEquals(PreparationPhase.READY, owner.state.phase)
        assertTrue(owner.prepareModel("de", true))
        backend.completion!!(false)
        assertEquals(PreparationPhase.FAILED, owner.state.phase)
        assertTrue(owner.prepareModel("es", true))
        assertEquals(setOf("es"), backend.languages)
        backend.completion!!(true)
    }

    @Test
    fun downloadRequiresApprovalAndRejectsConcurrentPreparation() = onMain {
        val backend = Backend()
        val ready = mutableListOf<String>()
        val owner = ModelPreparation(backend, {}, ready::add)
        owner.prepare("[DRILL] Synthetic", TranslationTarget.ENGLISH, false)
        assertEquals(0, backend.identifications)
        owner.prepare("[DRILL] Synthetic", TranslationTarget.ENGLISH, true)
        owner.prepare("[DRILL] Another", TranslationTarget.GERMAN, true)
        assertEquals(1, backend.identifications)
        backend.identification!!("ko")
        assertEquals(setOf("ko"), backend.languages)
        assertEquals(PreparationPhase.WAITING_FOR_WIFI, owner.state.phase)
        backend.completion!!(true)
        assertEquals(listOf("en"), ready)
    }

    @Test
    fun withdrawalDuringIdentificationDoesNotStartDownloads() = onMain {
        val backend = Backend()
        val owner =
            ModelPreparation(backend, {}, { error("Withdrawn result must not resume work") })
        owner.prepare("[DRILL] Synthetic", TranslationTarget.FRENCH, true)
        owner.withdraw()
        backend.identification!!("ko")
        assertTrue(backend.languages.isEmpty())
        assertEquals(PreparationPhase.IDLE, owner.state.phase)
    }

    @Test
    fun withdrawalDuringDownloadSuppressesReadyAndRetainsSingleSlot() = onMain {
        val backend = Backend()
        val owner =
            ModelPreparation(backend, {}, { error("Withdrawn result must not resume work") })
        owner.prepare("[DRILL] Synthetic", TranslationTarget.GERMAN, true)
        backend.identification!!("fr")
        assertEquals(setOf("fr"), backend.languages)
        owner.withdraw()
        owner.prepare("[DRILL] Duplicate", TranslationTarget.GERMAN, true)
        assertEquals(1, backend.identifications)
        backend.completion!!(true)
        assertEquals(PreparationPhase.IDLE, owner.state.phase)
        assertEquals(setOf("fr"), backend.languages)
    }

    @Test
    fun sameModelLanguageNeedsNoDownloadAndFailureCanRetry() = onMain {
        val backend = Backend()
        val ready = mutableListOf<String>()
        val owner = ModelPreparation(backend, {}, ready::add)
        owner.prepare("Synthetic", TranslationTarget.TRADITIONAL_CHINESE, true)
        backend.identification!!("zh")
        assertTrue(backend.languages.isEmpty())
        assertEquals(listOf("zh-Hant"), ready)
        owner.prepare("Synthetic", TranslationTarget.FRENCH, true)
        backend.identification!!("ko")
        backend.completion!!(false)
        assertEquals(PreparationPhase.FAILED, owner.state.phase)
        owner.prepare("Synthetic", TranslationTarget.FRENCH, true)
        assertEquals(3, backend.identifications)
    }

    @Test
    fun installedPacksAreCountedAndOnlyMissingPacksDownload() = onMain {
        val backend = Backend()
        val ready = mutableListOf<String>()
        val owner = ModelPreparation(backend, {}, ready::add)
        owner.prepare("Synthetic", TranslationTarget.JAPANESE, true, setOf("ko"))
        backend.identification!!("ko")
        assertEquals(setOf("ja"), backend.languages)
        assertEquals(setOf("ko", "ja"), owner.state.languages)
        assertEquals(setOf("ko"), owner.state.completedLanguages)
        assertEquals("ja", owner.state.activeLanguage)
        backend.completion!!(true)
        assertEquals(setOf("ko", "ja"), owner.state.completedLanguages)
        assertEquals(listOf("ja"), ready)
        backend.languages = emptySet()
        owner.prepare("Synthetic", TranslationTarget.JAPANESE, true, setOf("ko", "ja"))
        backend.identification!!("ko")
        assertTrue(backend.languages.isEmpty())
        assertEquals(PreparationPhase.READY, owner.state.phase)
    }

    @Test
    fun failedSecondPackRetainsCompletedFirstPackWithoutRetrying() = onMain {
        val backend = Backend()
        val owner = ModelPreparation(backend, {}, { error("Incomplete pair") })
        owner.prepare("Synthetic", TranslationTarget.JAPANESE, true)
        backend.identification!!("ko")
        assertEquals(setOf("ko"), backend.languages)
        backend.completion!!(true)
        assertEquals(setOf("ja"), backend.languages)
        assertEquals(setOf("ko"), owner.state.completedLanguages)
        backend.completion!!(false)
        assertEquals(PreparationPhase.FAILED, owner.state.phase)
        assertEquals(setOf("ko"), owner.state.completedLanguages)
        assertFalse(owner.busy)
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
