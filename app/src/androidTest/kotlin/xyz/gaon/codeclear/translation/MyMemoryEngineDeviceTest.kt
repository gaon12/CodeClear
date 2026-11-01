package xyz.gaon.codeclear.translation

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyMemoryEngineDeviceTest {
    @Test fun unauthorizedAndSameLanguageWorkNeverInitializeTheNetworkClient() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val created = AtomicInteger()
        val completed = CountDownLatch(1)
        val result = AtomicReference<TranslationResult>()
        val owner = AtomicReference<MyMemoryEngine>()
        instrumentation.runOnMainSync {
            owner.set(
                MyMemoryEngine(instrumentation.targetContext, transportFactory = {
                    created.incrementAndGet()
                    error("No network client is needed")
                })
            )
            owner.get().run(
                TranslationInput(
                    1,
                    "This is a synthetic English language test.",
                    TranslationTarget.ENGLISH,
                    TranslationEngineKind.MY_MEMORY
                ),
                false
            ) {
                assertEquals(TranslationResult.Unavailable, it)
            }
            assertEquals(0, created.get())
            owner.get().authorize(true)
            owner.get().run(
                TranslationInput(
                    1,
                    "This is a synthetic English language test.",
                    TranslationTarget.ENGLISH,
                    TranslationEngineKind.MY_MEMORY
                ),
                false
            ) {
                result.set(it)
                completed.countDown()
            }
        }
        try {
            assertTrue(completed.await(20, TimeUnit.SECONDS))
            assertTrue(result.get() is TranslationResult.Ready)
            assertTrue((result.get() as TranslationResult.Ready).sameLanguage)
            assertEquals(0, created.get())
        } finally {
            instrumentation.runOnMainSync { owner.get().close() }
        }
    }
}
