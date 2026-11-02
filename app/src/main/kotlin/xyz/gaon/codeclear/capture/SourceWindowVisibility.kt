package xyz.gaon.codeclear.capture

import android.os.Looper

data class SourceWindowSnapshot(val visibleIds: Set<Int>, val sourceIds: Set<Int>)
enum class SourceWindowChange { OPENED, UNAVAILABLE }

/** Reports window identities only. No other application's text is inspected. */
object SourceWindowVisibility {
    private var inspector: (() -> Set<Int>?)? = null
    private val sourceIds = linkedSetOf<Int>()
    private val observers = linkedSetOf<(SourceWindowChange) -> Unit>()
    private var uncertain = false

    fun connect(provider: () -> Set<Int>?): AutoCloseable {
        checkMain()
        inspector = provider
        sourceIds.clear()
        uncertain = false
        signal(SourceWindowChange.UNAVAILABLE)
        return AutoCloseable {
            checkMain()
            if (inspector === provider) {
                inspector = null
                sourceIds.clear()
                uncertain = false
                signal(SourceWindowChange.UNAVAILABLE)
            }
        }
    }

    fun observe(observer: (SourceWindowChange) -> Unit): AutoCloseable {
        checkMain()
        require(observers.size < 16)
        observers += observer
        return AutoCloseable {
            checkMain()
            observers -= observer
            if (observers.isEmpty()) {
                sourceIds.clear()
                uncertain = false
            }
        }
    }

    fun sourceOpened(windowId: Int) {
        checkMain()
        if (observers.isEmpty()) return
        if (windowId < 0) {
            uncertain = true
        } else {
            if (sourceIds.size >= 128 && windowId !in sourceIds) {
                inspect()?.let(sourceIds::retainAll)
            }
            if (sourceIds.size >= 128 && windowId !in sourceIds) {
                uncertain = true
            } else {
                sourceIds += windowId
            }
        }
        signal(SourceWindowChange.OPENED)
    }

    fun snapshot(): SourceWindowSnapshot? {
        checkMain()
        if (uncertain) return null
        val visible = inspect() ?: return null
        sourceIds.retainAll(visible)
        return SourceWindowSnapshot(visible, sourceIds.toSet())
    }

    private fun inspect(): Set<Int>? = try {
        inspector?.invoke()?.takeIf { it.isNotEmpty() && it.size <= 64 }?.toSet()
    } catch (_: RuntimeException) {
        null
    }

    private fun signal(change: SourceWindowChange) {
        observers.toList().forEach { observer ->
            try {
                observer(change)
            } catch (_: RuntimeException) {
                // Presentation failures must not terminate the system-bound capture service.
                observers -= observer
            }
        }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
}
