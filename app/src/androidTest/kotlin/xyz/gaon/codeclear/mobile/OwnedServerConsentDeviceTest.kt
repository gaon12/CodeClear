package xyz.gaon.codeclear.mobile

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationTarget

class OwnedServerConsentDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "fixture_owned_consent"
    private val granted = MobileOptions(
        target = TranslationTarget.ENGLISH,
        engine = TranslationEngineKind.OWNED_SERVER,
        labsEnabled = true,
        labsUnlocked = true,
        ownedServerAllowed = true,
        ownedServerCredentialId = "a".repeat(64)
    )

    @Test
    fun savedOwnedConsentIsIndependentOfMyMemoryAndBoundToItsCredential() {
        try {
            val storage = MobileOptionsStorage(context, name, ownedConfigured = true)
            storage.save(granted)
            val restored = storage.load()
            assertTrue(restored.translationAllowed)
            assertFalse(restored.serverAllowed)
            assertEquals(granted.ownedServerCredentialId, restored.ownedServerCredentialId)
            storage.save(granted.copy(ownedServerAllowed = false, serverAllowed = true))
            assertFalse(storage.load().translationAllowed)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun disablingLabsOrRemovingBuildConfigurationCannotRestoreRemotePermission() {
        try {
            val configured = MobileOptionsStorage(context, name, ownedConfigured = true)
            configured.save(granted.copy(labsEnabled = false))
            assertFalse(configured.load().ownedServerAllowed)
            assertFalse(configured.load().translationAllowed)
            configured.save(granted)
            val missing = MobileOptionsStorage(context, name, ownedConfigured = false).load()
            assertFalse(missing.ownedServerAllowed)
            assertFalse(missing.translationAllowed)
            assertEquals(TranslationEngineKind.ML_KIT, missing.engine)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun oldDisclosureAndInvalidCredentialIdentifiersCannotEnableOwnedTranslation() {
        try {
            val storage = MobileOptionsStorage(context, name, ownedConfigured = true)
            val preferences = context.getSharedPreferences(name, 0)
            for (oldVersion in listOf(0, 1)) {
                storage.save(
                    granted.copy(
                        notificationConsent = true,
                        accessibilityConsent = true,
                        localConsent = true
                    )
                )
                assertTrue(
                    preferences.edit().putInt("owned_disclosure_version", oldVersion).commit()
                )
                val restored = storage.load()
                assertFalse(restored.translationAllowed)
                assertTrue(restored.notificationConsent)
                assertTrue(restored.accessibilityConsent)
                assertTrue(restored.localConsent)
            }
            storage.save(granted.copy(ownedServerCredentialId = "not-a-credential-fingerprint"))
            assertFalse(storage.load().translationAllowed)
            assertEquals("", storage.load().ownedServerCredentialId)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
