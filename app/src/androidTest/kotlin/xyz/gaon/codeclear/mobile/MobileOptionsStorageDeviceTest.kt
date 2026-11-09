package xyz.gaon.codeclear.mobile

import android.content.Context
import android.content.res.Configuration
import androidx.core.app.LocaleManagerCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationTarget

class MobileOptionsStorageDeviceTest {
    @Test fun automaticSpeechDefaultsOffAndOnlyItsVersionedChoicePersists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "speech-options-${UUID.randomUUID()}"
        try {
            val store = MobileOptionsStorage(context, name)
            assertFalse(store.load().automaticSpeechEnabled)
            store.save(MobileOptions(automaticSpeechEnabled = true))
            assertTrue(store.load().automaticSpeechEnabled)
            assertFalse(store.load().translationAllowed)
            assertFalse(store.load().serverAllowed)
            context.getSharedPreferences(name, 0).edit()
                .putInt("automatic_speech_disclosure_version", 0).commit()
            assertFalse(store.load().automaticSpeechEnabled)
            store.save(MobileOptions(automaticSpeechEnabled = false))
            assertFalse(store.load().automaticSpeechEnabled)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun legacyExperimentFlagsCannotRevealLabsOrRestoreRemotePermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "labs-legacy-${UUID.randomUUID()}"
        try {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                .putInt("disclosure_version", 1).putBoolean("labs_enabled", true)
                .putBoolean("server_allowed", true).putBoolean("nearby_enabled", true)
                .putInt("nearby_disclosure_version", 1).putInt("remote_disclosure_version", 1)
                .putBoolean("local_consent", true).putBoolean("notification_consent", true)
                .putBoolean("accessibility_consent", true).commit()
            val options = MobileOptionsStorage(context, name).load()
            assertFalse(options.labsVisible)
            assertFalse(options.labsEnabled)
            assertFalse(options.serverAllowed)
            assertFalse(options.nearbyEnabled)
            assertTrue(options.localConsent)
            assertTrue(options.notificationConsent)
            assertTrue(options.accessibilityConsent)
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                .putString("local_engine", TranslationEngineKind.MY_MEMORY.key).commit()
            val remote = MobileOptionsStorage(context, name).load()
            assertFalse(remote.serverAllowed)
            assertFalse(remote.translationAllowed)
            assertEquals(TranslationEngineKind.ML_KIT, remote.engine)
        } finally {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun labsDiscoveryPersistsWithoutGrantingAnyExperimentalPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = "labs-discovery-${UUID.randomUUID()}"
        try {
            val storage = MobileOptionsStorage(context, preferences)
            assertFalse(storage.load().labsVisible)
            storage.save(MobileOptions(labsUnlocked = true))
            val saved = MobileOptionsStorage(context, preferences).load()
            assertTrue(saved.labsVisible)
            assertFalse(saved.labsEnabled)
            assertFalse(saved.nearbyEnabled)
            assertFalse(saved.serverAllowed)
            assertFalse(saved.ownedServerAllowed)
        } finally {
            context.getSharedPreferences(
                preferences,
                android.content.Context.MODE_PRIVATE
            ).edit().clear().commit()
        }
    }

    @Test
    fun updatedNearbyDisclosureRequiresNewAgreementWithoutResettingCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "options-test-${UUID.randomUUID()}"
        val storage = MobileOptionsStorage(context, name)
        try {
            storage.save(
                MobileOptions(
                    notificationConsent = true,
                    accessibilityConsent = true,
                    localConsent = true,
                    labsEnabled = true,
                    labsUnlocked = true,
                    nearbyEnabled = true
                )
            )
            assertTrue(storage.load().nearbyEnabled)
            assertTrue(
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                    .remove("nearby_disclosure_version").commit()
            )
            val restored = storage.load()
            assertFalse(restored.nearbyEnabled)
            assertTrue(restored.notificationConsent)
            assertTrue(restored.accessibilityConsent)
            assertTrue(restored.localConsent)
            storage.save(restored)
            assertFalse(storage.load().nearbyEnabled)
            storage.save(restored.copy(nearbyEnabled = true))
            assertTrue(storage.load().nearbyEnabled)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun popupPermissionRequiresItsOwnDisclosureAndPreservesCaptureChoices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "options-test-${UUID.randomUUID()}"
        val storage = MobileOptionsStorage(context, name)
        try {
            storage.save(
                MobileOptions(
                    notificationConsent = true,
                    accessibilityConsent = true,
                    deferredOverlayEnabled = true
                )
            )
            assertTrue(storage.load().deferredOverlayEnabled)
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                .remove("overlay_disclosure_version").commit()
            val restored = storage.load()
            assertFalse(restored.deferredOverlayEnabled)
            assertTrue(restored.notificationConsent)
            assertTrue(restored.accessibilityConsent)
            storage.save(restored.copy(deferredOverlayEnabled = false))
            assertFalse(storage.load().deferredOverlayEnabled)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun freshOptionsUseDeviceDefaultsIndependentlyOfTheApplicationLocale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "options-test-${UUID.randomUUID()}"
        val original = Locale.getDefault()
        val language = UiLanguages.normalize(
            LocaleManagerCompat.getSystemLocales(context).toLanguageTags()
        )
        val expected = TranslationTarget.entries.firstOrNull { it.languageTag == language }
            ?: TranslationTarget.ENGLISH
        try {
            for (locale in listOf(Locale.JAPAN, Locale.FRANCE)) {
                Locale.setDefault(locale)
                val localized = context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply { setLocale(locale) }
                )
                val options = MobileOptionsStorage(localized, name).load()
                assertEquals(expected, options.target)
                assertEquals(expected, MobileOptions().target)
                assertEquals("", options.uiLanguage)
                assertEquals(ThemeChoice.SYSTEM, options.theme)
                assertFalse(options.notificationConsent)
                assertFalse(options.accessibilityConsent)
                assertFalse(options.localConsent)
                assertFalse(options.setupComplete)
                assertFalse(options.nearbyEnabled)
                assertFalse(options.labsEnabled)
                assertFalse(options.deferredOverlayEnabled)
                assertEquals(MessageViewMode.SWITCH, options.messageViewMode)
            }
        } finally {
            Locale.setDefault(original)
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun savedLanguageTargetAndThemeRemainExplicitUserChoices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "options-test-${UUID.randomUUID()}"
        val storage = MobileOptionsStorage(context, name)
        val original = Locale.getDefault()
        try {
            storage.save(
                storage.load().copy(
                    target = TranslationTarget.GERMAN,
                    uiLanguage = "fr",
                    theme = ThemeChoice.DARK,
                    nearbyEnabled = true,
                    labsEnabled = true,
                    labsUnlocked = true,
                    messageViewMode = MessageViewMode.BOTH
                )
            )
            Locale.setDefault(Locale.JAPAN)
            val options = storage.load()
            assertEquals(TranslationTarget.GERMAN, options.target)
            assertEquals("fr", options.uiLanguage)
            assertEquals(ThemeChoice.DARK, options.theme)
            assertTrue(options.nearbyEnabled)
            assertTrue(options.labsEnabled)
            assertEquals(MessageViewMode.BOTH, options.messageViewMode)
            assertFalse(options.localConsent)
        } finally {
            Locale.setDefault(original)
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun nearbyChoiceCannotStartSharingBeforeLabsIsUnlocked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "options-test-${UUID.randomUUID()}"
        val storage = MobileOptionsStorage(context, name)
        try {
            storage.save(MobileOptions(nearbyEnabled = true))
            assertFalse(storage.load().nearbyEnabled)
            storage.save(storage.load().copy(labsEnabled = true, labsUnlocked = true))
            assertTrue(storage.load().labsEnabled)
            assertFalse(storage.load().nearbyEnabled)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun remotePermissionIsVersionedAndNeverInheritedFromLocalPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "options-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val storage = MobileOptionsStorage(context, name)
        try {
            storage.save(
                MobileOptions(
                    localConsent = true,
                    labsEnabled = true,
                    labsUnlocked = true,
                    engine = TranslationEngineKind.MY_MEMORY
                )
            )
            assertFalse(storage.load().serverAllowed)
            assertFalse(storage.load().translationAllowed)
            storage.save(storage.load().copy(serverAllowed = true))
            assertTrue(storage.load().translationAllowed)
            preferences.edit().remove("remote_disclosure_version").commit()
            assertFalse(storage.load().serverAllowed)
            storage.save(storage.load().copy(serverAllowed = true, labsEnabled = false))
            assertFalse(storage.load().serverAllowed)
            assertEquals(TranslationEngineKind.ML_KIT, storage.load().engine)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun legacyConsentStaysWithItsEngineAndUnknownIdentifiersDisableTranslation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "options-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val storage = MobileOptionsStorage(context, name)
        try {
            assertTrue(
                preferences.edit().putInt("disclosure_version", 1)
                    .putBoolean("local_consent", true).commit()
            )
            assertEquals(TranslationEngineKind.ML_KIT, storage.load().engine)
            assertTrue(storage.load().localConsent)
            storage.save(storage.load().copy(engine = TranslationEngineKind.SYSTEM))
            assertEquals(TranslationEngineKind.SYSTEM, storage.load().engine)
            assertTrue(storage.load().localConsent)
            assertTrue(preferences.edit().putString("local_engine", "unrecognized").commit())
            assertFalse(storage.load().localConsent)
            assertEquals(TranslationEngineKind.ML_KIT, storage.load().engine)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
