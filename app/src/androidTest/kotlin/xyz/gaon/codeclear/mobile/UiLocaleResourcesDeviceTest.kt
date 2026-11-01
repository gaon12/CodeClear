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
        val permissionActions = mapOf(
            "en" to "Open settings",
            "ko" to "설정 열기",
            "zh-Hans" to "打开设置",
            "zh-Hant" to "開啟設定",
            "ja" to "設定を開く",
            "fr" to "Ouvrir les paramètres",
            "de" to "Einstellungen öffnen"
        )
        val chineseCopyLabels = mutableListOf<String>()
        for ((tag, home) in expected) {
            val localized = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(tag))
                }
            )
            assertEquals(home, localized.getString(R.string.mobile_home))
            assertTrue(localized.getString(R.string.mobile_captured, "14:05").contains("14:05"))
            assertTrue(
                localized.getString(R.string.mobile_nearby_saved_at, "14:05").contains("14:05")
            )
            val metadata = localized.getString(R.string.mobile_numeric_metadata, 4372, 3, "1", "—")
            assertTrue(metadata.contains("4372"))
            assertFalse(metadata.contains("%1"))
            val support = localized.getString(R.string.mobile_engine_pairs, "TEST_LANG", 12, 34)
            assertTrue(
                support.contains("TEST_LANG") && support.contains("12") && support.contains("34")
            )
            assertFalse(support.contains("%1"))
            assertEquals(permissionActions[tag], localized.getString(R.string.mobile_open_settings))
            val version = localized.getString(R.string.mobile_version_value, "2.3.4", 99)
            assertTrue(version.contains("2.3.4") && version.contains("99"))
            assertFalse(version.contains("%1"))
            assertTrue(
                localized.getString(R.string.mobile_contributors_summary, "TEST_OWNER")
                    .contains("TEST_OWNER")
            )
            for (id in listOf(
                R.string.mobile_notification_disclosure,
                R.string.mobile_accessibility_disclosure,
                R.string.mobile_local_disclosure,
                R.string.mobile_model_notice,
                R.string.mobile_system_disclosure,
                R.string.mobile_nearby_disclosure,
                R.string.mobile_nearby_foreground
            )) {
                assertTrue(localized.getString(id).length > 40)
            }
            for (count in listOf(0, 1, 2, 32)) {
                val received = localized.resources.getQuantityString(
                    R.plurals.mobile_nearby_count,
                    count,
                    count
                )
                assertFalse(received.contains("%1"))
                assertTrue(received.isNotBlank())
                if (tag == "en" && count == 1) assertTrue(received.startsWith("1 recent message"))
            }
            val actions = localized.getString(R.string.mobile_message_actions, "DRILL")
            assertTrue(actions.contains("DRILL"))
            assertFalse(actions.contains("%1"))
            if (tag.startsWith("zh-")) {
                chineseCopyLabels +=
                    localized.getString(R.string.mobile_copy_body)
            }
        }
        assertNotEquals(chineseCopyLabels[0], chineseCopyLabels[1])
    }
}
