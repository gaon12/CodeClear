package xyz.gaon.codeclear.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalEngineDeviceTest {
    @Test
    fun bundledLanguageIdentificationRunsWithoutAModelDownload() {
        val body = "[DRILL] This is only a test. Do not enter the dangerous area near Gate 3."
        val result = runEngine(body, LocalTarget.ENGLISH)
        assertEquals(LocalResult.Ready(body, "en", true), result)
    }

    @Test
    fun chineseScriptConversionRunsOnTheDeviceAndPreservesNumbers() {
        val body = "[演练] 请勿进入危险区域。请留在安全地点。14:05，112，3 km。"
        val expected = "[演練] 請勿進入危險區域。請留在安全地點。14:05，112，3 km。"
        assertEquals(
            LocalResult.Ready(expected, "zh", true),
            runEngine(body, LocalTarget.TRADITIONAL_CHINESE)
        )
    }

    private fun runEngine(body: String, target: LocalTarget): LocalResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<LocalResult>()
        val engine = MlKitLocalEngine()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.run(LocalInput(1, body, target), false) {
                result.set(it)
                latch.countDown()
            }
        }
        try {
            assertTrue(
                "Local engine did not finish within 30 seconds",
                latch.await(30, TimeUnit.SECONDS)
            )
            return result.get()
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.close() }
        }
    }
}
