package xyz.gaon.codeclear.probe

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTranslationOperationTest {
    private val input = LocalInput(1, "[DRILL] Leave Gate 3. Call 112.", LocalTarget.KOREAN)

    @Test
    fun unrelatedCapabilitiesDoNotHideALaterSupportedPair() {
        val backend = Backend().apply {
            pairs = List(600) { SystemLanguagePair("en", "es", true) } +
                SystemLanguagePair("en", "ko", true)
        }
        assertTrue(operation(backend).run(input) is LocalResult.Ready)
        assertEquals(1, backend.creations)
    }

    @Test
    fun capabilityInspectionAndRetainedInventoryHaveFiniteBounds() {
        var inspected = 0
        val irrelevant = generateSequence {
            inspected++
            SystemLanguagePair("en", "es", true)
        }.supportedSystemPairs()
        assertTrue(irrelevant.isEmpty())
        assertEquals(32_768, inspected)
        val relevant = (1..5000).asSequence().map {
            SystemLanguagePair("en-$it", "ko", true)
        }.supportedSystemPairs()
        assertEquals(4096, relevant.size)
    }

    @Test
    fun unavailablePlatformsNeverReadTheBodyOrCreateASession() {
        val backend = Backend().apply { available = false }
        assertEquals(LocalResult.Unavailable, operation(backend).run(input))
        assertEquals(0, backend.identifications)
        assertEquals(0, backend.creations)
    }

    @Test
    fun missingProvidersUnsupportedPairsAndUnpreparedModelsStayDistinct() {
        val backend = Backend()
        backend.pairs = emptyList()
        assertEquals(LocalResult.Unavailable, operation(backend).run(input))
        backend.pairs = listOf(SystemLanguagePair("en", "de", true))
        assertEquals(LocalResult.UnsupportedPair, operation(backend).run(input))
        backend.pairs = listOf(SystemLanguagePair("en", "ko", false))
        assertEquals(LocalResult.NeedsModels, operation(backend).run(input))
        assertEquals(0, backend.creations)
        assertEquals(0, backend.session.requests)
    }

    @Test
    fun unidentifiedOrInvalidSourceTagsNeverReachTheProvider() {
        val backend = Backend()
        listOf(null, "und", "", "en\nko", "en_XX").forEach {
            backend.source = it
            assertEquals(LocalResult.UnknownLanguage, operation(backend).run(input))
        }
        assertEquals(0, backend.creations)
    }

    @Test
    fun theReadyPairPreservesSourceAndClosesExactlyOneSession() {
        val backend = Backend()
        backend.session.answer = "[훈련] 3번 출입구를 떠나세요. 112."
        assertEquals(
            LocalResult.Ready(backend.session.answer!!, "en", false),
            operation(backend).run(input)
        )
        assertEquals(1, backend.session.requests)
        assertEquals(1, backend.session.closed.get())
    }

    @Test
    fun chineseOutputConversionAlsoAppliesToUnchangedOriginals() {
        val backend = Backend().apply {
            source = "zh"
            pairs = listOf(SystemLanguagePair("zh", "en", true))
        }
        val operation = SystemTranslationOperation(backend, { text, _ -> text.replace("练", "練") })
        assertEquals(
            LocalResult.Ready("[演練] 112", "zh", true),
            operation.run(LocalInput(1, "[演练] 112", LocalTarget.TRADITIONAL_CHINESE))
        )
        assertEquals(0, backend.creations)
    }

    @Test
    fun languagePairSelectionRejectsWrongScriptsAndPrefersPreparedModels() {
        val pair = SystemLanguagePair("en", "zh", true)
        val capabilities = listOf(
            SystemLanguagePair("en", "zh-Hans", true),
            SystemLanguagePair("en", "zh-Hant", false),
            pair
        )
        assertEquals(
            pair,
            SystemTranslationOperation.selectPair(
                capabilities,
                "en",
                LocalTarget.TRADITIONAL_CHINESE
            )
        )
        assertEquals(
            null,
            SystemTranslationOperation.selectPair(
                listOf(SystemLanguagePair("zh-Hans", "en", true)),
                "zh",
                LocalTarget.ENGLISH
            )
        )
    }

    @Test
    fun invalidAndOversizedProviderOutputNeverBecomesAReadyResult() {
        listOf(null, "", " ", "x".repeat(16385)).forEach { answer ->
            val backend = Backend().apply { session.answer = answer }
            assertEquals(LocalResult.Failed, operation(backend).run(input))
            assertEquals(1, backend.session.closed.get())
        }
        val backend = Backend()
        assertEquals(
            LocalResult.Failed,
            SystemTranslationOperation(backend, { _, _ -> "x".repeat(16385) }).run(input)
        )
    }

    @Test
    fun duplicateCreationCallbacksDoNotDestroyTheAcceptedSession() {
        val backend = Backend()
        val other = Session()
        backend.createAction = { callback ->
            callback(backend.session)
            callback(backend.session)
            callback(other)
        }
        assertTrue(operation(backend).run(input) is LocalResult.Ready)
        assertEquals(1, backend.session.closed.get())
        assertEquals(1, other.closed.get())
        assertEquals(0, other.requests)
    }

    @Test
    fun nullAndLateCreationCallbacksReleaseResourcesWithoutSendingTheBody() {
        val backend = Backend().apply { createAction = { it(null) } }
        assertEquals(LocalResult.Failed, operation(backend).run(input))
        lateinit var deliver: (SystemTranslationSession?) -> Unit
        backend.createAction = { deliver = it }
        assertEquals(LocalResult.Failed, operation(backend).run(input))
        deliver(backend.session)
        assertEquals(1, backend.session.closed.get())
        assertEquals(0, backend.session.requests)
    }

    @Test
    fun responseTimeoutAndLateRepliesCannotKeepTheSessionOpen() {
        val backend = Backend()
        var deliver: ((String?) -> Unit)? = null
        backend.session.translateAction = { deliver = it }
        assertEquals(LocalResult.Failed, operation(backend).run(input))
        assertEquals(1, backend.session.closed.get())
        deliver!!("late")
        assertEquals(1, backend.session.closed.get())
    }

    @Test
    fun cancellationDuringProviderCreationWakesTheWorkerAndClosesALateSession() {
        val backend = Backend()
        val entered = CountDownLatch(1)
        lateinit var deliver: (SystemTranslationSession?) -> Unit
        backend.createAction = {
            deliver = it
            entered.countDown()
        }
        val operation = SystemTranslationOperation(backend, { text, _ -> text })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<LocalResult> { operation.run(input) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            operation.cancel()
            assertEquals(LocalResult.Failed, result.get(2, TimeUnit.SECONDS))
            deliver(backend.session)
            assertEquals(1, backend.session.closed.get())
            assertEquals(0, backend.session.requests)
        } finally {
            operation.cancel()
            worker.shutdownNow()
        }
    }

    @Test
    fun withdrawalDuringCapabilityLookupPreventsTranslatorCreation() {
        val backend = Backend()
        val operation = operation(backend)
        backend.capabilityAction = {
            operation.cancel()
            backend.pairs
        }
        assertEquals(LocalResult.Failed, operation.run(input))
        assertEquals(0, backend.creations)
        assertFalse(backend.session.closed.get() > 0)
    }

    @Test
    fun cancellationDuringTranslationClosesTheSessionAndDiscardsTheReply() {
        val backend = Backend()
        val operation = operation(backend)
        backend.session.translateAction = { callback ->
            operation.cancel()
            callback("withdrawn result")
        }
        assertEquals(LocalResult.Failed, operation.run(input))
        assertEquals(1, backend.session.closed.get())
    }

    @Test
    fun providerExceptionsAndDuplicateRepliesCannotReplaceTheFirstResult() {
        val backend = Backend()
        backend.session.translateAction = { error("Provider exception containing private text") }
        assertEquals(LocalResult.Failed, operation(backend).run(input))
        assertEquals(1, backend.session.closed.get())
        val duplicate = Backend()
        duplicate.session.translateAction = {
            it("first 112")
            it("second")
        }
        assertEquals(LocalResult.Ready("first 112", "en", false), operation(duplicate).run(input))
        assertEquals(1, duplicate.session.closed.get())
    }

    private fun operation(backend: Backend) = SystemTranslationOperation(
        backend,
        { text, _ -> text },
        creationTimeoutMillis = 30,
        responseTimeoutMillis = 30
    )

    private class Backend : SystemTranslationBackend {
        var available = true
        override val supported get() = available
        var source: String? = "en"
        var pairs = listOf(SystemLanguagePair("en", "ko", true))
        var identifications = 0
        var creations = 0
        val session = Session()
        var createAction: (((SystemTranslationSession?) -> Unit) -> Unit)? = null
        var capabilityAction: (() -> List<SystemLanguagePair>)? = null

        override fun identify(body: String): String? {
            identifications++
            return source
        }

        override fun capabilities(): List<SystemLanguagePair> = capabilityAction?.invoke() ?: pairs

        override fun create(
            pair: SystemLanguagePair,
            complete: (SystemTranslationSession?) -> Unit
        ) {
            creations++
            createAction?.invoke(complete) ?: complete(session)
        }
    }

    private class Session : SystemTranslationSession {
        var requests = 0
        val closed = AtomicInteger()
        var answer: String? = "Translated 112"
        var translateAction: (((String?) -> Unit) -> Unit)? = null

        override fun translate(body: String, complete: (String?) -> Unit) {
            requests++
            translateAction?.invoke(complete) ?: complete(answer)
        }

        override fun close() {
            closed.incrementAndGet()
        }
    }
}
