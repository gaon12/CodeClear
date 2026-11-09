package xyz.gaon.codeclear.mobile

import xyz.gaon.codeclear.translation.TranslationEngineKind

internal data class AutomaticSpeechSelection(val target: String, val engine: TranslationEngineKind)
internal data class AutomaticSpeechRequest(val id: Long, val text: String, val language: String) {
    val key: String get() = "$id:$language:translation"
}

/** Only fresh local captures observed during the current visible opt-in session can be spoken. */
internal class AutomaticSpeechQueue {
    private data class Pending(val expiresAt: Long, val body: String? = null)
    private var selection: AutomaticSpeechSelection? = null
    private val pending = linkedMapOf<Long, Pending>()
    private val seen = linkedSetOf<Long>()
    val hasPending: Boolean get() = pending.isNotEmpty()

    fun configure(value: AutomaticSpeechSelection?) {
        if (selection == value) return
        clear()
        selection = value
    }

    fun captured(id: Long, capture: CapturedAlert, wallNow: Long, elapsedNow: Long) {
        if (selection == null || id in seen || capture.source == CaptureSource.PEER ||
            capture.source == CaptureSource.ORIGINAL_NOTIFICATION ||
            wallNow - capture.capturedAtMillis !in 0..15_000 ||
            (capture.receivedAtMillis ?: capture.postedAtMillis)?.let {
                wallNow - it !in -5000..15_000
            } != false
        ) {
            return
        }
        prune(elapsedNow)
        if (pending.size >= 8) return
        seen += id
        if (seen.size > 128) seen.remove(seen.first())
        pending[id] = Pending(elapsedNow + 120_000)
    }

    fun translated(
        id: Long,
        target: String,
        engine: TranslationEngineKind,
        text: String,
        elapsedNow: Long
    ) {
        prune(elapsedNow)
        if (selection != AutomaticSpeechSelection(target, engine) ||
            text.isBlank() || text.length > 16_384
        ) {
            return
        }
        val previous = pending[id] ?: return
        pending[id] = previous.copy(body = text)
    }

    fun next(idle: Boolean, elapsedNow: Long): AutomaticSpeechRequest? {
        prune(elapsedNow)
        val selected = selection ?: return null
        if (!idle) return null
        val ready = pending.entries.firstOrNull { it.value.body != null } ?: return null
        pending.remove(ready.key)
        return AutomaticSpeechRequest(ready.key, requireNotNull(ready.value.body), selected.target)
    }

    fun deleted(id: Long?) {
        if (id == null) clear() else pending.remove(id)
    }

    fun clear() {
        pending.clear()
        seen.clear()
    }

    private fun prune(elapsedNow: Long) {
        pending.entries.removeAll { it.value.expiresAt <= elapsedNow }
    }
}
