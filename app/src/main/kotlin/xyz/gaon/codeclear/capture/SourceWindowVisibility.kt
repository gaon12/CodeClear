package xyz.gaon.codeclear.capture

import android.os.Looper

data class SourceWindowSnapshot(
    val visibleIds: Set<Int>,
    val sourceIds: Set<Int>,
    val alertPackages: Map<Int, String> = emptyMap()
)
enum class SourceWindowChange { OPENED, UNAVAILABLE }

/** Reports window identities only. No other application's text is inspected. */
object SourceWindowVisibility {
    private var inspector: (() -> Map<Int, Int>?)? = null
    private val sourceIds = linkedSetOf<Int>()
    private val alertPackages = linkedMapOf<Int, String>()
    private val observers = linkedSetOf<(SourceWindowChange) -> Unit>()
    private var uncertain = false

    fun connect(provider: () -> Map<Int, Int>?): AutoCloseable {
        checkMain()
        inspector = provider
        sourceIds.clear()
        alertPackages.clear()
        uncertain = false
        signal(SourceWindowChange.UNAVAILABLE)
        return AutoCloseable {
            checkMain()
            if (inspector === provider) {
                inspector = null
                sourceIds.clear()
                alertPackages.clear()
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
                alertPackages.clear()
                uncertain = false
            }
        }
    }

    fun sourceOpened(windowId: Int, reviewedAlertPackage: String? = null) {
        checkMain()
        if (observers.isEmpty()) return
        if (windowId < 0) {
            uncertain = true
        } else {
            if (sourceIds.size >= 128 && windowId !in sourceIds) {
                inspect()?.keys?.let(sourceIds::retainAll)
                alertPackages.keys.retainAll(sourceIds)
            }
            if (sourceIds.size >= 128 && windowId !in sourceIds) {
                uncertain = true
            } else {
                sourceIds += windowId
                if (!reviewedAlertPackage.isNullOrBlank() && reviewedAlertPackage.length <= 256) {
                    alertPackages[windowId] = reviewedAlertPackage
                } else {
                    alertPackages.remove(windowId)
                }
            }
        }
        signal(SourceWindowChange.OPENED)
    }

    fun snapshot(): SourceWindowSnapshot? {
        checkMain()
        if (uncertain) return null
        val visible = inspect() ?: return null
        sourceIds.retainAll(visible.keys)
        alertPackages.keys.retainAll(sourceIds)
        return SourceWindowSnapshot(
            visible.keys.toSet(),
            sourceIds.toSet(),
            alertPackages.filterKeys { visible[it] == 0 }
        )
    }

    private fun inspect(): Map<Int, Int>? = try {
        inspector?.invoke()?.takeIf {
            it.isNotEmpty() && it.size <= 64 && it.all { (id, display) -> id >= 0 && display >= 0 }
        }?.toMap()
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
