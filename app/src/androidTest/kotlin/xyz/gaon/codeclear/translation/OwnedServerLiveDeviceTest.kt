package xyz.gaon.codeclear.translation

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.gaon.codeclear.BuildConfig

/** Explicit isolated synthetic probe, excluded from automatic fixtures and absent from release APKs. */
class OwnedServerLiveDeviceTest {
    @Test
    fun privateConfiguredServiceTranslatesOnlyTheExplicitSyntheticFixture() {
        assumeTrue(BuildConfig.APPLICATION_ID == "xyz.gaon.codeclear.validation")
        val endpoint = OwnedServerEndpoint.configured()
        assumeTrue(endpoint != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.filesDir, "owned-server-live-token")
        assumeTrue(input.isFile)
        val token = try {
            require(input.length() in 32..256)
            requireNotNull(ServerAccessToken.parse(input.readText(Charsets.US_ASCII)))
        } finally {
            check(input.delete())
        }
        val storage =
            ServerCredentialStore(context, requireNotNull(endpoint).scope, "fixtureServerLive")
        var engine: OwnedServerEngine? = null
        val result = AtomicReference<TranslationResult?>()
        val finished = CountDownLatch(1)
        val loaded = CountDownLatch(1)
        val started = SystemClock.elapsedRealtime()
        try {
            storage.save(token.bytes().toString(Charsets.US_ASCII))
            instrumentation.runOnMainSync {
                engine = OwnedServerEngine(
                    context,
                    endpoint,
                    changed = { if (it.loaded) loaded.countDown() },
                    suppliedStorage = storage
                )
            }
            assertTrue(loaded.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                engine!!.authorize(true, token.fingerprint)
                engine!!.run(
                    TranslationInput(
                        1,
                        "[DRILL] This is a connection test. Gate 3, call 112.",
                        TranslationTarget.KOREAN,
                        TranslationEngineKind.OWNED_SERVER
                    ),
                    false
                ) {
                    result.set(it)
                    finished.countDown()
                }
            }
            assertTrue(finished.await(70, TimeUnit.SECONDS))
            val ready = result.get() as? TranslationResult.Ready
            assertTrue("The explicit synthetic translation did not become ready", ready != null)
            assertFalse(requireNotNull(ready).sameLanguage)
            assertTrue(ready.text.any { it in '\uac00'..'\ud7a3' })
            assertTrue(ready.text.contains("3") && ready.text.contains("112"))
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putBoolean("server_live_synthetic_ready", true)
                    putLong("server_live_elapsed_ms", SystemClock.elapsedRealtime() - started)
                }
            )
        } finally {
            instrumentation.runOnMainSync { engine?.close() }
            storage.clear()
        }
    }
}
