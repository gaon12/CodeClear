package xyz.gaon.codeclear.translation

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedServerEngineDeviceTest {
    @Test fun evidencedIssuerTravelsWithItsProducingTranslation() {
        val body = "[DRILL] Issued by Agency X. Gate 3, 112."
        Fixture(body = body).use { f ->
            val issuer = evidencedIssuer(
                body,
                "Agency X",
                "기관 X",
                "Issued by Agency X.",
                IssuerBasis.EXTRACTED_FROM_TEXT
            )!!
            f.client.issuer = issuer
            main { f.engine.authorize(true, serverDigest(token)) }
            assertEquals(
                TranslationResult.Ready("[DRILL] Result 3, 112.", "en", false, issuer),
                f.run()
            )
            assertEquals(1, f.client.calls.get())
        }
    }
    private val token = "synthetic-client-token-aaaaaaaaaaaaaaaa"
    private val replacement = "synthetic-client-token-bbbbbbbbbbbbbbbb"

    @Test
    fun authorizationRequiresTheExactSavedCredentialAndNeverFallsBack() {
        Fixture().use { f ->
            assertEquals(TranslationResult.Unavailable, f.run())
            main { f.engine.authorize(true, serverDigest(replacement)) }
            assertEquals(TranslationResult.Unavailable, f.run())
            assertEquals(0, f.client.calls.get())
            main { f.engine.authorize(true, serverDigest(token)) }
            val local = AtomicReference<TranslationResult?>()
            main {
                f.engine.run(
                    TranslationInput(1, "[DRILL] Gate 3.", TranslationTarget.KOREAN),
                    false,
                    local::set
                )
            }
            assertEquals(TranslationResult.Unavailable, awaitResult(local))
            assertEquals(0, f.client.calls.get())
            assertEquals(TranslationResult.Ready("[DRILL] Result 3, 112.", "en", false), f.run())
            assertEquals(1, f.client.calls.get())
        }
    }

    @Test
    fun sameLanguageUsesTheOriginalWithoutAProviderRequest() {
        Fixture(source = "ko").use { f ->
            main { f.engine.authorize(true, serverDigest(token)) }
            assertEquals(TranslationResult.Ready("[DRILL] Gate 3, 112.", "ko", true), f.run())
            assertEquals(0, f.client.calls.get())
        }
    }

    @Test
    fun withdrawingConsentRejectsLateOutputAndPreventsAnotherSend() {
        Fixture().use { f ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.client.block = {
                started.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            try {
                main { f.engine.authorize(true, serverDigest(token)) }
                val result = f.start()
                assertTrue(started.await(5, TimeUnit.SECONDS))
                main { f.engine.authorize(false, "") }
                release.countDown()
                assertEquals(TranslationResult.Unavailable, awaitResult(result))
                assertEquals(TranslationResult.Unavailable, f.run())
                assertEquals(1, f.client.calls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun changingCredentialsInvalidatesOldWorkAndRequiresFreshConsent() {
        Fixture().use { f ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.client.block = {
                started.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            try {
                main { f.engine.authorize(true, serverDigest(token)) }
                val result = f.start()
                assertTrue(started.await(5, TimeUnit.SECONDS))
                main { assertTrue(f.engine.saveAccess(replacement)) }
                release.countDown()
                assertEquals(TranslationResult.Unavailable, awaitResult(result))
                waitUntil {
                    !f.state.get().busy &&
                        f.state.get().credentialId == serverDigest(replacement)
                }
                assertEquals(TranslationResult.Unavailable, f.run())
                f.client.block = {}
                main { f.engine.authorize(true, serverDigest(replacement)) }
                assertTrue(f.run() is TranslationResult.Ready)
                assertEquals(serverDigest(replacement), f.client.lastCredential.get())
                assertEquals(2, f.client.calls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun explicitCacheDeletionLeavesTranslationPausedAndCredentialRemovalPersists() {
        Fixture().use { f ->
            main {
                f.engine.authorize(true, serverDigest(token))
                assertTrue(f.engine.clearCache())
            }
            waitUntil { f.state.get().cacheResult == ServerCacheClearResult.CLEARED }
            assertEquals(1, f.client.deletions.get())
            assertEquals(TranslationResult.Unavailable, f.run())
            assertEquals(0, f.client.calls.get())
            main { assertTrue(f.engine.removeAccess()) }
            waitUntil { !f.state.get().busy && !f.state.get().ready }
            assertEquals(null, f.storage.value.get())
            assertEquals(TranslationResult.Unavailable, f.run())
        }
    }

    @Test
    fun failedStorageDoesNotEnableTranslationAndCloseIgnoresDelayedLoading() {
        Fixture().use { f ->
            f.storage.failSave = true
            main { assertTrue(f.engine.saveAccess(replacement)) }
            waitUntil { !f.state.get().busy && f.state.get().failed }
            assertEquals(serverDigest(token), f.state.get().credentialId)
            assertEquals(TranslationResult.Unavailable, f.run())
        }
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val read = CountDownLatch(1)
        Fixture(waitForLoad = false, beforeLoad = {
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            read.countDown()
        }).use { f ->
            assertTrue(started.await(5, TimeUnit.SECONDS))
            main { f.engine.close() }
            release.countDown()
            assertTrue(read.await(5, TimeUnit.SECONDS))
            main {}
            assertFalse(f.state.get().loaded)
            assertEquals(0, f.client.calls.get())
        }
    }

    @Test
    fun closingAnActiveClientReturnsBeforeItsOffMainCleanupFinishes() {
        Fixture().use { f ->
            main { f.engine.authorize(true, serverDigest(token)) }
            assertTrue(f.run() is TranslationResult.Ready)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            f.client.onClose = {
                assertFalse(Looper.myLooper() == Looper.getMainLooper())
                started.countDown()
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                } finally {
                    finished.countDown()
                }
            }
            try {
                main { f.engine.close() }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertEquals(1L, finished.count)
            } finally {
                release.countDown()
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
        }
    }

    private inner class Fixture(
        source: String = "en",
        waitForLoad: Boolean = true,
        beforeLoad: () -> Unit = {},
        private val body: String = "[DRILL] Gate 3, 112."
    ) : AutoCloseable {
        val state = AtomicReference(ServerAccessState())
        val storage = FakeStorage(beforeLoad)
        val client = FakeClient()
        lateinit var engine: OwnedServerEngine

        init {
            main {
                engine = OwnedServerEngine(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    requireNotNull(OwnedServerEndpoint.parse("https://example.invalid/api.php")),
                    state::set,
                    storage,
                    { client },
                    { source }
                )
            }
            if (waitForLoad) waitUntil { state.get().loaded }
        }

        fun start(): AtomicReference<TranslationResult?> {
            val result = AtomicReference<TranslationResult?>()
            main {
                engine.run(
                    TranslationInput(
                        1,
                        body,
                        TranslationTarget.KOREAN,
                        TranslationEngineKind.OWNED_SERVER
                    ),
                    false,
                    result::set
                )
            }
            return result
        }

        fun run(): TranslationResult = awaitResult(start())
        override fun close() = main { engine.close() }
    }

    private inner class FakeStorage(private val beforeLoad: () -> Unit) : ServerTokenStorage {
        val value = AtomicReference(ServerAccessToken.parse(token))

        @Volatile var failSave = false
        override fun load(): ServerAccessToken? {
            beforeLoad()
            return value.get()
        }
        override fun save(value: String) {
            if (failSave) throw IOException("Synthetic storage failure")
            this.value.set(ServerAccessToken.parse(value))
        }
        override fun clear() {
            value.set(null)
        }
    }

    private class FakeClient : OwnedServerClient {
        var issuer: IssuerClaim? = null
        val calls = AtomicInteger()
        val deletions = AtomicInteger()
        val lastCredential = AtomicReference<String>()

        @Volatile var block: () -> Unit = {}

        @Volatile var onClose: () -> Unit = {}
        override fun translate(
            original: String,
            target: TranslationTarget,
            token: ServerAccessToken,
            cancellation: RemoteCancellation,
            authorized: () -> Boolean
        ): OwnedServerReply {
            calls.incrementAndGet()
            lastCredential.set(token.fingerprint)
            block()
            return OwnedServerReply.Text("[DRILL] Result 3, 112.", issuer)
        }
        override fun clearCache(
            token: ServerAccessToken,
            cancellation: RemoteCancellation,
            authorized: () -> Boolean
        ): ServerCacheClearResult {
            assertTrue(authorized())
            deletions.incrementAndGet()
            return ServerCacheClearResult.CLEARED
        }
        override fun close() = onClose()
    }

    private fun main(action: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun awaitResult(result: AtomicReference<TranslationResult?>): TranslationResult {
        waitUntil { result.get() != null }
        return requireNotNull(result.get())
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Synthetic server state timed out" }
            Thread.sleep(5)
        }
    }
}
