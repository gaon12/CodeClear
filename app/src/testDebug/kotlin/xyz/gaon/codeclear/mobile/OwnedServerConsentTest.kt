package xyz.gaon.codeclear.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationTarget

class OwnedServerConsentTest {
    @Test
    fun enabledMyMemoryTakesRemotePriorityAndDeliberateDisableRequiresExistingOwnedConsent() {
        val both = options.copy(labsEnabled = true, serverAllowed = true, ownedServerAllowed = true)
        assertEquals(TranslationEngineKind.MY_MEMORY, both.withMyMemoryPriority().engine)
        assertTrue(both.withMyMemoryPriority().translationAllowed)
        val disabled = both.withMyMemoryPriority().withoutMyMemory()
        assertEquals(TranslationEngineKind.OWNED_SERVER, disabled.engine)
        assertFalse(disabled.serverAllowed)
        assertTrue(disabled.translationAllowed)
        assertFalse(
            both.copy(ownedServerAllowed = false).withMyMemoryPriority()
                .withoutMyMemory().translationAllowed
        )
        assertFalse(
            both.copy(ownedServerCredentialId = "").withMyMemoryPriority()
                .withoutMyMemory().translationAllowed
        )
        val local = both.copy(engine = TranslationEngineKind.ML_KIT, localConsent = true)
        assertEquals(TranslationEngineKind.ML_KIT, local.withMyMemoryPriority().engine)
    }

    private val options = MobileOptions(
        target = TranslationTarget.ENGLISH,
        engine = TranslationEngineKind.OWNED_SERVER,
        ownedServerCredentialId = "a".repeat(64)
    )

    @Test
    fun ownedTranslationNeedsLabsAndItsOwnConsent() {
        assertFalse(options.translationAllowed)
        assertFalse(options.copy(labsEnabled = true, serverAllowed = true).translationAllowed)
        assertFalse(options.copy(ownedServerAllowed = true).translationAllowed)
        assertTrue(options.copy(labsEnabled = true, ownedServerAllowed = true).translationAllowed)
    }

    @Test
    fun unboundConsentCannotEnableAClientAccount() {
        for (id in listOf("", "a".repeat(63), "A".repeat(64), "not-an-account")) {
            assertFalse(
                options.copy(
                    labsEnabled = true,
                    ownedServerAllowed = true,
                    ownedServerCredentialId = id
                ).translationAllowed
            )
        }
    }

    @Test
    fun ownedPermissionDoesNotEnableTheLocalOrMyMemoryEngine() {
        val granted = options.copy(labsEnabled = true, ownedServerAllowed = true)
        assertFalse(
            granted.copy(
                engine = TranslationEngineKind.ML_KIT,
                localConsent = false
            ).translationAllowed
        )
        assertFalse(
            granted.copy(
                engine = TranslationEngineKind.MY_MEMORY,
                serverAllowed = false
            ).translationAllowed
        )
        assertTrue(
            granted.copy(
                engine = TranslationEngineKind.ML_KIT,
                localConsent = true
            ).translationAllowed
        )
    }
}
