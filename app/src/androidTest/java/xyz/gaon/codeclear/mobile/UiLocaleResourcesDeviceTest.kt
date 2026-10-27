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
            for (id in listOf(
                R.string.mobile_notification_disclosure,
                R.string.mobile_accessibility_disclosure,
                R.string.mobile_local_disclosure,
                R.string.mobile_model_notice
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
