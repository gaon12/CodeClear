package xyz.gaon.codeclear.probe

import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemEngineDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val owners = mutableListOf<SystemLocalEngine>()
    private val input = LocalInput(1, "[DRILL] Leave Gate 3. Call 112.", LocalTarget.KOREAN)

    @After
    fun releaseOwnedWorkers() {
        owners.forEach { engine ->
            onMain { engine.close() }
            assertTrue(engine.awaitClosed(5000))
        }
    }

    @Test
    fun publicFactoryQueriesCapabilitiesWithoutLoadingUnsupportedApiTypes() {
        val backend = systemTranslationBackend(instrumentation.targetContext)
        if (Build.VERSION.SDK_INT == 30) assertFalse(backend.supported)
        val capabilities = backend.capabilities()
        assertTrue(capabilities.size <= 512)
        if (!backend.supported) assertTrue(capabilities.isEmpty())
    }

    @Test
    fun theInstalledProviderEitherTranslatesAPreparedPairOrExplainsItsAbsence() {
        val backend = systemTranslationBackend(instrumentation.targetContext)
        val capabilities = backend.capabilities()
        val pair = SystemTranslationOperation.selectPair(capabilities, "en", LocalTarget.KOREAN)
        val scripts = ChineseScriptConverter()
        val result = SystemTranslationOperation(backend, scripts::convert).run(input)
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putBoolean("codeclear_system_platform", backend.supported)
                putInt("codeclear_system_pairs", capabilities.size)
                putBoolean("codeclear_system_pair_ready", pair?.ready == true)
                putString("codeclear_system_result", result.javaClass.simpleName)
            }
        )
        when {
            !backend.supported || capabilities.isEmpty() -> assertEquals(
                LocalResult.Unavailable,
                result
            )

            pair == null -> assertEquals(LocalResult.UnsupportedPair, result)

            !pair.ready -> assertEquals(LocalResult.NeedsModels, result)

            else -> {
                assertTrue(result is LocalResult.Ready)
                val translated = result as LocalResult.Ready
                assertTrue(translated.text.contains("112"))
                assertFalse(translated.sameLanguage)
                assertEquals("en", translated.source)
            }
        }
    }

    @Test
    fun backgroundTranslationConvertsChineseAndReturnsOnTheMainThread() {
        val backend = Backend()
        backend.pairs = listOf(SystemLanguagePair("en", "zh", true))
        val result = AtomicReference<LocalResult>()
        val finished = CountDownLatch(1)
        val engine = owner(backend)
        onMain {
            engine.run(input.copy(target = LocalTarget.TRADITIONAL_CHINESE), false) {
                check(Looper.myLooper() == Looper.getMainLooper())
                result.set(it)
                finished.countDown()
            }
        }
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        assertEquals(LocalResult.Ready("[演練] 112", "en", false), result.get())
        assertEquals(1, backend.session.closes.get())
    }

    @Test
    fun cancellationReleasesTheWorkerAndClosesLateCreatedSessions() {
        val backend = Backend()
        val entered = CountDownLatch(1)
        val creation = AtomicReference<(SystemTranslationSession?) -> Unit>()
        backend.createAction = {
            creation.set(it)
            entered.countDown()
        }
        val engine = owner(backend)
        val finished = CountDownLatch(1)
        val result = AtomicReference<LocalResult>()
        onMain {
            engine.run(input, false) {
                result.set(it)
                finished.countDown()
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        onMain { engine.cancel() }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(LocalResult.Failed, result.get())
        creation.get().invoke(backend.session)
        assertEquals(1, backend.session.closes.get())
        assertEquals(0, backend.session.requests.get())
    }

    @Test
    fun repeatedInspectionSharesOneBoundedQueryAndUsesTheRecentSnapshot() {
        val backend = Backend()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.capabilityAction = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            backend.pairs
        }
        val loaded = CountDownLatch(1)
        val engine = owner(backend) { if (it.loaded) loaded.countDown() }
        try {
            onMain {
                engine.refreshCapabilities()
                repeat(20) { engine.refreshCapabilities(force = true) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(loaded.await(5, TimeUnit.SECONDS))
            onMain { repeat(20) { engine.refreshCapabilities() } }
            assertEquals(1, backend.queries.get())
            assertEquals(backend.pairs, engine.availability.pairs)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun closingDuringAResponseWaitSuppressesCallbacksAndDestroysTheSession() {
        val backend = Backend()
        val requested = CountDownLatch(1)
        backend.session.action = { requested.countDown() }
        val engine = owner(backend)
        val callbacks = AtomicInteger()
        onMain { engine.run(input, false) { callbacks.incrementAndGet() } }
        assertTrue(requested.await(5, TimeUnit.SECONDS))
        onMain { engine.close() }
        assertTrue(engine.awaitClosed(5000))
        instrumentation.waitForIdleSync()
        assertEquals(1, backend.session.closes.get())
        assertEquals(0, callbacks.get())
    }

    @Test
    fun resolvingProviderSettingsDoesNotOpenThemUntilTheCallerActs() {
        val backend = Backend()
        val engine = owner(backend)
        val resolved = CountDownLatch(1)
        val action = AtomicReference<(() -> Boolean)?>()
        onMain {
            engine.requestSettings {
                action.set(it)
                resolved.countDown()
            }
        }
        assertTrue(resolved.await(5, TimeUnit.SECONDS))
        assertEquals(0, backend.settingsOpened.get())
        onMain { assertTrue(action.get()!!.invoke()) }
        assertEquals(1, backend.settingsOpened.get())
        assertEquals(0, backend.session.requests.get())
    }

    private fun owner(
        backend: SystemTranslationBackend,
        changed: (SystemEngineAvailability) -> Unit = {}
    ): SystemLocalEngine = SystemLocalEngine(backend, changed).also(owners::add)

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private class Backend : SystemTranslationBackend {
        override val supported = true
        var pairs = listOf(SystemLanguagePair("en", "ko", true))
        val session = Session()
        val queries = AtomicInteger()
        val settingsOpened = AtomicInteger()
        var createAction: (((SystemTranslationSession?) -> Unit) -> Unit)? = null
        var capabilityAction: (() -> List<SystemLanguagePair>)? = null

        override fun identify(body: String): String {
            check(Looper.myLooper() != Looper.getMainLooper())
            return "en"
        }

        override fun capabilities(): List<SystemLanguagePair> {
            check(Looper.myLooper() != Looper.getMainLooper())
            queries.incrementAndGet()
            return capabilityAction?.invoke() ?: pairs
        }

        override fun create(
            pair: SystemLanguagePair,
            complete: (SystemTranslationSession?) -> Unit
        ) {
            check(Looper.myLooper() != Looper.getMainLooper())
            createAction?.invoke(complete) ?: complete(session)
        }

        override fun settingsAction(): () -> Boolean {
            check(Looper.myLooper() != Looper.getMainLooper())
            return {
                check(Looper.myLooper() == Looper.getMainLooper())
                settingsOpened.incrementAndGet()
                true
            }
        }
    }

    private class Session : SystemTranslationSession {
        val closes = AtomicInteger()
        val requests = AtomicInteger()
        var action: (((String?) -> Unit) -> Unit)? = null

        override fun translate(body: String, complete: (String?) -> Unit) {
            check(Looper.myLooper() != Looper.getMainLooper())
            requests.incrementAndGet()
            action?.invoke(complete) ?: complete("[演练] 112")
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }
}
