package xyz.gaon.codeclear.mobile

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.translation.MlKitLocalEngine
import xyz.gaon.codeclear.translation.SystemLanguagePair
import xyz.gaon.codeclear.translation.SystemTranslationSession
import xyz.gaon.codeclear.translation.TranslationInput
import xyz.gaon.codeclear.translation.TranslationResult
import xyz.gaon.codeclear.translation.TranslationTarget
import xyz.gaon.codeclear.translation.systemTranslationBackend

/** Provider probes use constants, never the owner's stored alert text or automatic downloads. */
class TranslationAvailabilityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun installedJapaneseAndEnglishPairsTranslateWithoutRequestingDownloads() {
        val installed = Tasks.await(
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java),
            15,
            TimeUnit.SECONDS
        ).map {
            it.language
        }.toSet()
        val engine = MlKitLocalEngine()
        val ready = mutableListOf<String>()
        try {
            for (target in listOf(TranslationTarget.JAPANESE, TranslationTarget.ENGLISH)) {
                if (!installed.containsAll(setOf("ko", target.modelCode) - "en")) continue
                val result = AtomicReference<TranslationResult>()
                val done = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    engine.run(
                        TranslationInput(1, "[DRILL] 번역 기능 점검입니다. 안전한 장소로 이동하세요. 112", target),
                        false
                    ) {
                        result.set(it)
                        done.countDown()
                    }
                }
                assertTrue(done.await(55, TimeUnit.SECONDS))
                assertTrue(result.get() is TranslationResult.Ready)
                assertTrue((result.get() as TranslationResult.Ready).text.contains("112"))
                ready += target.languageTag
            }
        } finally {
            instrumentation.runOnMainSync { engine.close() }
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putBoolean("codeclear_model_ko", "ko" in installed)
                putBoolean("codeclear_model_ja", "ja" in installed)
                putString("codeclear_installed_pair_results", ready.joinToString(","))
            }
        )
    }

    @Test fun publicSystemSettingsAndDirectSessionAreCheckedIndependentlyOfThePairList() {
        val backend = systemTranslationBackend(instrumentation.targetContext)
        val pairs = backend.capabilities()
        val settings = backend.settingsAction() != null
        val created = CountDownLatch(1)
        val lock = Any()
        var accepting = true
        var session: SystemTranslationSession? = null
        var succeeded = false
        var available = false
        try {
            backend.create(SystemLanguagePair("en", "ko", false)) { value ->
                synchronized(lock) {
                    if (accepting) session = value else value?.close()
                }
                created.countDown()
            }
            assertTrue(created.await(15, TimeUnit.SECONDS))
            val selected = synchronized(lock) { session }
            available = selected != null
            if (selected != null) {
                val done = CountDownLatch(1)
                selected.translate("[DRILL] Synthetic translation check. Call 112.") {
                    succeeded = !it.isNullOrBlank() && it.contains("112")
                    done.countDown()
                }
                assertTrue(done.await(15, TimeUnit.SECONDS))
            }
        } finally {
            synchronized(lock) {
                accepting = false
                session?.close()
                session = null
            }
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putInt("codeclear_public_system_pairs", pairs.size)
                putBoolean("codeclear_public_system_settings", settings)
                putBoolean("codeclear_direct_system_session", available)
                putBoolean("codeclear_direct_system_translated", succeeded)
            }
        )
    }
}
