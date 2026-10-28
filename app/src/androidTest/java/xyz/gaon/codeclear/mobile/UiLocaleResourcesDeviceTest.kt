package xyz.gaon.codeclear.mobile

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.R

class UiLocaleResourcesDeviceTest {
    @Test
    fun allRequestedLocalesResolveTheirOwnMenusAndFormatMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = mapOf(
            "en" to "Home",
            "ko" to "홈",
            "zh-Hans" to "首页",
            "zh-Hant" to "首頁",
            "ja" to "ホーム",
            "fr" to "Accueil",
            "de" to "Startseite"
        )
        val chineseSources = mutableListOf<String>()
        for ((tag, home) in expected) {
            val localized = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(tag))
                }
            )
            assertEquals(home, localized.getString(R.string.mobile_home))
            assertTrue(localized.getString(R.string.mobile_captured, "14:05").contains("14:05"))
            val metadata = localized.getString(R.string.mobile_numeric_metadata, 4372, 3, "1", "—")
            assertTrue(metadata.contains("4372"))
            assertFalse(metadata.contains("%1"))
            val support = localized.getString(R.string.mobile_engine_pairs, "TEST_LANG", 12, 34)
            assertTrue(
                support.contains("TEST_LANG") && support.contains("12") && support.contains("34")
            )
            assertFalse(support.contains("%1"))
            assertTrue(
                localized.getString(R.string.mobile_engine, "TEST_ENGINE").contains("TEST_ENGINE")
            )
            for (id in listOf(
                R.string.mobile_notification_disclosure,
                R.string.mobile_accessibility_disclosure,
                R.string.mobile_local_disclosure,
                R.string.mobile_model_notice,
                R.string.mobile_system_disclosure
            )) {
                assertTrue(localized.getString(id).length > 40)
            }
            if (tag.startsWith("zh-")) {
                chineseSources +=
                    localized.getString(R.string.mobile_source, "test.system.receiver")
            }
        }
        assertNotEquals(chineseSources[0], chineseSources[1])
    }
}
