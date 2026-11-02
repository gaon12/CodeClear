package xyz.gaon.codeclear.mobile

import xyz.gaon.codeclear.capture.SourceWindowSnapshot
import xyz.gaon.codeclear.translation.TranslationEngineKind

internal data class DeferredAlert(
    val id: Long,
    val capture: CapturedAlert,
    val target: String,
    val engine: TranslationEngineKind,
    val startedAt: Long,
    val freshNotification: Boolean = false,
    val windowId: Int? = null,
    val translation: String? = null
)

/** Optional popup loss never deletes history or substitutes for capture completeness. */
internal class DeferredAlertQueue(
    private val capacity: Int = 8,
    private val lifetimeMillis: Long = 120_000,
    private val settleMillis: Long = 750
) {
    private val pending = linkedMapOf<Long, DeferredAlert>()
    private val completed = linkedSetOf<Long>()
    private var absentSince: Long? = null
    private var selectedId: Long? = null
    val hasPending: Boolean get() = pending.isNotEmpty()

    init {
        require(capacity in 1..8 && lifetimeMillis in 1000..120_000 && settleMillis in 0..2000)
    }

    fun observed(
        stored: StoredObservation,
        capture: CapturedAlert,
        windowId: Int?,
        displayId: Int?,
        target: String,
        engine: TranslationEngineKind,
        wallNow: Long,
        elapsedNow: Long
    ) {
        if (capture.source !in setOf(CaptureSource.NOTIFICATION, CaptureSource.DIALOG) ||
            stored.id in completed ||
            wallNow - capture.capturedAtMillis !in -5000..lifetimeMillis ||
            capture.receivedAtMillis?.let { wallNow - it !in -5000..lifetimeMillis } == true
        ) {
            return
        }
        val existing = pending[stored.id]
        if (existing == null && !stored.created) return
        if (existing != null && (
                existing.capture.original != capture.original ||
                    existing.capture.sourcePackage != capture.sourcePackage ||
                    existing.target != target ||
                    existing.engine != engine
                )
        ) {
            dismiss(stored.id)
            return
        }
        if (existing == null && pending.size == capacity) {
            pending.keys.firstOrNull { it != selectedId }?.let(::dismiss) ?: return
        }
        val candidate = existing ?: DeferredAlert(stored.id, capture, target, engine, elapsedNow)
        val fresh = capture.source == CaptureSource.NOTIFICATION &&
            capture.postedAtMillis?.let { wallNow - it in -5000..15_000 } == true
        val reviewedWindow = windowId?.takeIf {
            it >= 0 && displayId == 0 && capture.source == CaptureSource.DIALOG
        }
        pending[stored.id] = candidate.copy(
            freshNotification = candidate.freshNotification || fresh,
            windowId = reviewedWindow ?: candidate.windowId
        )
    }

    fun translated(id: Long, target: String, engine: TranslationEngineKind, body: String) {
        val candidate = pending[id] ?: return
        if (candidate.target == target && candidate.engine == engine && body.isNotBlank() &&
            body.length <= 16_384
        ) {
            pending[id] = candidate.copy(translation = body)
        }
    }

    fun sourceAppeared() {
        absentSince = null
        selectedId = null
    }

    fun select(now: Long, windows: SourceWindowSnapshot?): DeferredAlert? {
        pending.values.filter { now - it.startedAt !in 0 until lifetimeMillis }
            .map { it.id }.forEach(::dismiss)
        if (pending.isEmpty()) {
            sourceAppeared()
            return null
        }
        val blockers = windows?.sourceIds.orEmpty() + pending.values.mapNotNull { it.windowId }
        if (windows == null || windows.visibleIds.isEmpty() ||
            blockers.any { it in windows.visibleIds }
        ) {
            sourceAppeared()
            return null
        }
        val since = absentSince ?: now.also { absentSince = it }
        if (now - since < settleMillis) return null
        return pending.values.firstOrNull { it.freshNotification && it.windowId != null }
            ?.also { selectedId = it.id }
    }

    fun dismiss(id: Long) {
        pending.remove(id)
        completed += id
        if (completed.size > 128) completed.remove(completed.first())
        if (selectedId == id) selectedId = null
    }

    fun clear() {
        pending.keys.toList().forEach(::dismiss)
        sourceAppeared()
    }
}
