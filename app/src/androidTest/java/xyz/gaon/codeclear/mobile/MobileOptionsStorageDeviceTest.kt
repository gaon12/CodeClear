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
import xyz.gaon.codeclear.probe.LocalEngineKind
import xyz.gaon.codeclear.probe.LocalTarget

class MobileOptionsStorageDeviceTest {
    @Test
    fun freshOptionsUseDeviceDefaultsIndependentlyOfTheApplicationLocale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "options-test-${UUID.randomUUID()}"
        val original = Locale.getDefault()
        val language = UiLanguages.normalize(
            LocaleManagerCompat.getSystemLocales(context).toLanguageTags()
        )
        val expected = LocalTarget.entries.firstOrNull { it.languageTag == language }
            ?: LocalTarget.ENGLISH
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
                    target = LocalTarget.GERMAN,
                    uiLanguage = "fr",
                    theme = ThemeChoice.DARK,
                    nearbyEnabled = true,
                    labsEnabled = true,
                    messageViewMode = MessageViewMode.BOTH
                )
            )
            Locale.setDefault(Locale.JAPAN)
            val options = storage.load()
            assertEquals(LocalTarget.GERMAN, options.target)
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
            storage.save(storage.load().copy(labsEnabled = true))
            assertTrue(storage.load().labsEnabled)
            assertFalse(storage.load().nearbyEnabled)
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
            assertEquals(LocalEngineKind.ML_KIT, storage.load().engine)
            assertTrue(storage.load().localConsent)
            storage.save(storage.load().copy(engine = LocalEngineKind.SYSTEM))
            assertEquals(LocalEngineKind.SYSTEM, storage.load().engine)
            assertTrue(storage.load().localConsent)
            assertTrue(preferences.edit().putString("local_engine", "unrecognized").commit())
            assertFalse(storage.load().localConsent)
            assertEquals(LocalEngineKind.ML_KIT, storage.load().engine)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
