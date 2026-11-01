package xyz.gaon.codeclear.probe

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun preparedTranslationRunsInTheSelectedAbiWithoutDownloadingModels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("require32Bit") == "true") assertFalse(Process.is64Bit())
        fun downloaded(): Set<String> = Tasks.await(
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java),
            15,
            TimeUnit.SECONDS
        ).map { it.language }.toSet()
        val before = downloaded()
        if (arguments.getString("requireNativeTranslation") == "true") assertTrue("ko" in before)
        val result = runEngine(
            "[DRILL] Stay inside. Keep away from windows and call 112 for help.",
            LocalTarget.KOREAN
        )
        if ("ko" in before) {
            assertTrue(result is LocalResult.Ready)
            val translated = result as LocalResult.Ready
            assertEquals("en", translated.source)
            assertFalse(translated.sameLanguage)
            assertTrue(
                translated.text.contains("112") && translated.text.any {
                    it in '\uac00'..'\ud7a3'
                }
            )
        } else {
            assertEquals(LocalResult.NeedsModels, result)
        }
        assertEquals(before, downloaded())
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putBoolean("codeclear_native_64_bit", Process.is64Bit())
                putBoolean("codeclear_native_output_ready", result is LocalResult.Ready)
            }
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
