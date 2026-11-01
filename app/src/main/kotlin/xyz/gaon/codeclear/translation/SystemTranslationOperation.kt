package xyz.gaon.codeclear.translation

import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class SystemLanguagePair(val source: String, val target: String, val ready: Boolean)

/** Ignore unrelated targets before bounding the retained provider inventory. */
internal fun Sequence<SystemLanguagePair>.supportedSystemPairs(): List<SystemLanguagePair> =
    take(32_768).filter {
        it.source.length in 2..32 && it.target.length in 2..32 &&
            Locale.forLanguageTag(it.target).language in systemTargetLanguages
    }.distinct().take(4096).toList()

private val systemTargetLanguages = TranslationTarget.entries.map { it.modelCode }.toSet()

interface SystemTranslationSession : AutoCloseable {
    fun translate(body: String, complete: (String?) -> Unit)
}

/** Implementations identify locally; capability queries must never contain the alert body. */
interface SystemTranslationBackend {
    val supported: Boolean
    fun identify(body: String): String?
    fun capabilities(): List<SystemLanguagePair>
    fun create(pair: SystemLanguagePair, complete: (SystemTranslationSession?) -> Unit)
    fun settingsAction(): (() -> Boolean)? = null
}

/** One worker owns an operation. Cancellation can be requested from any thread. */
class SystemTranslationOperation(
    private val backend: SystemTranslationBackend,
    private val convert: (String, TranslationTarget) -> String,
    private val creationTimeoutMillis: Long = 10_000,
    private val responseTimeoutMillis: Long = 20_000
) {
    private val cancelled = AtomicBoolean()
    private val waiting = AtomicReference<CountDownLatch?>()

    init {
        require(creationTimeoutMillis in 1..30_000 && responseTimeoutMillis in 1..60_000)
    }

    fun cancel() {
        cancelled.set(true)
        waiting.get()?.countDown()
    }

    fun run(input: TranslationInput): TranslationResult {
        if (cancelled.get()) return TranslationResult.Failed
        return try {
            if (!backend.supported) return TranslationResult.Unavailable
            val source = backend.identify(input.body)?.takeIf(::validSystemLanguage)
                ?: return TranslationResult.UnknownLanguage
            if (cancelled.get()) return TranslationResult.Failed
            val capabilities = backend.capabilities().asSequence().supportedSystemPairs()
            if (cancelled.get()) return TranslationResult.Failed
            if (capabilities.isEmpty()) return TranslationResult.Unavailable
            val sourceLocale = Locale.forLanguageTag(source)
            if (sourceLocale.language == input.target.modelCode) {
                return ready(input.body, input.target, source, true)
            }
            val pair = selectPair(capabilities, source, input.target)
                ?: return TranslationResult.UnsupportedPair
            if (!pair.ready) return TranslationResult.NeedsModels
            val session = awaitSession(pair) ?: return TranslationResult.Failed
            session.use {
                if (cancelled.get()) return TranslationResult.Failed
                val result = AtomicReference<String?>()
                val received = AtomicBoolean()
                val latch = CountDownLatch(1)
                waiting.set(latch)
                try {
                    if (cancelled.get()) return TranslationResult.Failed
                    session.translate(input.body) { text ->
                        if (received.compareAndSet(false, true)) {
                            result.set(text?.takeIf { it.isNotBlank() && it.length <= 16384 })
                            latch.countDown()
                        }
                    }
                    if (!latch.await(responseTimeoutMillis, TimeUnit.MILLISECONDS) ||
                        cancelled.get()
                    ) {
                        return TranslationResult.Failed
                    }
                    ready(
                        result.get() ?: return TranslationResult.Failed,
                        input.target,
                        source,
                        false
                    )
                } finally {
                    waiting.compareAndSet(latch, null)
                }
            }
        } catch (_: Exception) {
            // Provider and identification exceptions can contain the original; never expose them.
            TranslationResult.Failed
        }
    }

    private fun awaitSession(pair: SystemLanguagePair): SystemTranslationSession? {
        val pending = PendingSession()
        waiting.set(pending.latch)
        try {
            if (!cancelled.get()) backend.create(pair, pending::offer)
            val arrived = !cancelled.get() &&
                pending.latch.await(creationTimeoutMillis, TimeUnit.MILLISECONDS)
            return pending.take(arrived && !cancelled.get())
        } catch (failure: Exception) {
            pending.take(false)
            throw failure
        } finally {
            waiting.compareAndSet(pending.latch, null)
        }
    }

    private fun ready(
        text: String,
        target: TranslationTarget,
        source: String,
        sameLanguage: Boolean
    ): TranslationResult {
        if (cancelled.get() || text.isBlank() ||
            text.length > 16384
        ) {
            return TranslationResult.Failed
        }
        val converted = convert(text, target)
        return if (cancelled.get() || converted.isBlank() || converted.length > 16384) {
            TranslationResult.Failed
        } else {
            TranslationResult.Ready(converted, source, sameLanguage)
        }
    }

    /** A late or duplicate provider callback must never leak or destroy the accepted session. */
    private class PendingSession {
        val latch = CountDownLatch(1)
        private var accepting = true
        private var received = false
        private var first: SystemTranslationSession? = null

        fun offer(session: SystemTranslationSession?) {
            val discard = synchronized(this) {
                when {
                    received -> session?.takeUnless { it === first }

                    !accepting -> session

                    else -> {
                        received = true
                        first = session
                        null
                    }
                }
            }
            discard?.closeSafely()
            latch.countDown()
        }

        fun take(success: Boolean): SystemTranslationSession? {
            val session = synchronized(this) {
                accepting = false
                first
            }
            if (!success) session?.closeSafely()
            return session.takeIf { success }
        }
    }

    companion object {
        internal fun selectPair(
            capabilities: List<SystemLanguagePair>,
            source: String,
            target: TranslationTarget
        ): SystemLanguagePair? {
            val sourceLocale = Locale.forLanguageTag(source)
            val targetScript = Locale.forLanguageTag(target.languageTag).script
            return capabilities.filter { pair ->
                val from = Locale.forLanguageTag(pair.source)
                val to = Locale.forLanguageTag(pair.target)
                validSystemLanguage(pair.source) && validSystemLanguage(pair.target) &&
                    from.language == sourceLocale.language &&
                    (from.script.isEmpty() || from.script == sourceLocale.script) &&
                    to.language == target.modelCode &&
                    (to.script.isEmpty() || to.script == targetScript)
            }.sortedWith(
                compareByDescending<SystemLanguagePair> { it.ready }
                    .thenByDescending { it.target.equals(target.languageTag, ignoreCase = true) }
                    .thenBy { it.source }.thenBy { it.target }
            ).firstOrNull()
        }

        private fun SystemTranslationSession.closeSafely() {
            try {
                close()
            } catch (_: Exception) {
                // Cleanup failures must not leak provider details or prevent later cleanup.
            }
        }
    }
}
