package xyz.gaon.codeclear.mobile

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalLanguageIdentifierDeviceTest {
    @Test
    fun bundledIdentificationRecognizesTheOriginalWithoutPreparingTranslationModels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = AtomicReference<String>()
        val complete = CountDownLatch(1)
        lateinit var identifier: OriginalLanguageIdentifier
        instrumentation.runOnMainSync {
            identifier = OriginalLanguageIdentifier()
            identifier.identify("[DRILL] 이것은 테스트 안내입니다. 안전한 장소로 이동하세요.") {
                result.set(it)
                complete.countDown()
            }
        }
        try {
            assertTrue(complete.await(20, TimeUnit.SECONDS))
            assertEquals("ko", result.get())
        } finally {
            instrumentation.runOnMainSync { identifier.clear() }
        }
    }
}
