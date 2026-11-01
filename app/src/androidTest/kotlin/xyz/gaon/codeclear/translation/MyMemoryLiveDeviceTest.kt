package xyz.gaon.codeclear.translation

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sends one constant synthetic phrase only, without reading history or changing product permission. */
class MyMemoryLiveDeviceTest {
    @Test fun explicitlyAuthorizedSyntheticPhraseReturnsARealServerTranslation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = AtomicReference<TranslationResult>()
        val completed = CountDownLatch(1)
        val storage = object : RemoteBudgetStorage {
            var snapshot: RemoteBudgetSnapshot? = null
            override fun read() = snapshot
            override fun write(value: RemoteBudgetSnapshot): Boolean {
                snapshot = value
                return true
            }
        }
        val owner = AtomicReference<MyMemoryEngine>()
        instrumentation.runOnMainSync {
            owner.set(
                MyMemoryEngine(
                    instrumentation.targetContext,
                    suppliedBudget = RemoteDailyBudget(storage) {
                        1
                    }
                )
            )
            owner.get().authorize(true)
            owner.get().run(
                TranslationInput(
                    1,
                    "This is a synthetic translation connection test.",
                    TranslationTarget.JAPANESE,
                    TranslationEngineKind.MY_MEMORY
                ),
                false
            ) {
                result.set(it)
                completed.countDown()
            }
        }
        try {
            assertTrue(completed.await(50, TimeUnit.SECONDS))
            assertTrue(
                "Synthetic remote result must be ready",
                result.get() is TranslationResult.Ready
            )
            val ready = result.get() as TranslationResult.Ready
            assertEquals("en", ready.source)
            assertTrue(ready.text.isNotBlank())
            assertTrue(ready.text != "This is a synthetic translation connection test.")
        } finally {
            instrumentation.runOnMainSync {
                owner.get().authorize(false)
                owner.get().close()
            }
        }
    }
}
