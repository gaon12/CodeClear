package xyz.gaon.codeclear.mobile

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.probe.LocalEngineKind

class MobileOptionsStorageDeviceTest {
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
