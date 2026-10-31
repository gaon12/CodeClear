package xyz.gaon.codeclear.nearby

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicLong
import xyz.gaon.codeclear.mobile.CaptureSource
import xyz.gaon.codeclear.mobile.CapturedAlert

data class NearbyState(
    val status: NearbyStatus = NearbyStatus.OFF,
    val originals: List<NearbyOriginal> = emptyList()
)

/** Owns the explicit sharing allowance, fresh local exports, and a volatile unverified inbox. */
class NearbySharing(
    context: Context,
    val sender: Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
    private val availability: () -> NearbyStatus = { NearbyAccess.status(context, sender) },
    private val factory: NearbyRadioFactory = NearbyRadioFactory { send, known, received, status ->
        BleRelay(context, send, known, received, status)
    }
) {
    private val main = Handler(Looper.getMainLooper())
    private val inbox = RelayInbox()
    private data class LocalExport(
        val capture: CapturedAlert,
        val deadline: Long,
        val occurrence: RelayOccurrence = RelayOccurrence()
    )
    private val outgoing = linkedMapOf<Long, LocalExport>()
    private val generation = AtomicLong()
    private var radio: NearbyRadio? = null
    private var allowed = false
    private var visible = false
    private var suspended = false
    private var enabledAt = Long.MAX_VALUE
    var state by mutableStateOf(NearbyState())
        private set

    private val maintenance = object : Runnable {
        override fun run() {
            prune()
            if (allowed && visible && !suspended) main.postDelayed(this, 15000)
        }
    }

    fun configure(enabled: Boolean) {
        requireMain()
        if (allowed == enabled) return
        allowed = enabled
        enabledAt = if (enabled) System.currentTimeMillis() else Long.MAX_VALUE
        if (!enabled) {
            outgoing.clear()
            inbox.clear()
        }
        refresh()
    }

    fun foreground(active: Boolean) {
        requireMain()
        visible = active
        refresh()
    }

    fun suspendForDiagnostics(active: Boolean) {
        requireMain()
        suspended = active
        outgoing.clear()
        refresh()
    }

    fun refresh() {
        requireMain()
        val ticket = generation.incrementAndGet()
        radio?.close()
        radio = null
        main.removeCallbacks(maintenance)
        prune()
        val status = when {
            !allowed -> NearbyStatus.OFF
            !visible || suspended -> NearbyStatus.PAUSED
            else -> availability()
        }
        state = state.copy(status = status)
        if (status !in setOf(NearbyStatus.LISTENING, NearbyStatus.RECEIVE_ONLY)) return
        val next = factory.create(sender, {
            inbox.contains(it, SystemClock.elapsedRealtime())
        }, { original ->
            main.post {
                if (generation.get() == ticket && allowed && visible && !suspended &&
                    inbox.accept(
                        original,
                        System.currentTimeMillis(),
                        SystemClock.elapsedRealtime()
                    )
                ) {
                    publish()
                }
            }
        }, { result ->
            main.post {
                if (generation.get() == ticket) state = state.copy(status = result)
            }
        })
        radio = next
        next.start()
        outgoing.forEach { (id, export) ->
            val alert = export.capture
            next.publish(
                id,
                alert.title,
                alert.original,
                alert.capturedAtMillis,
                export.deadline,
                export.occurrence
            )
        }
        main.postDelayed(maintenance, 15000)
    }

    fun offer(id: Long, capture: CapturedAlert) {
        requireMain()
        if (!allowed || suspended || !sender || capture.source == CaptureSource.PEER ||
            capture.capturedAtMillis < enabledAt
        ) {
            return
        }
        prune()
        if (id in outgoing) return
        val remaining = (
            capture.capturedAtMillis + RelayProtocol.LIFETIME -
                System.currentTimeMillis()
            )
            .coerceIn(0, RelayProtocol.LIFETIME)
        if (remaining == 0L) return
        val deadline = minOf(
            outgoing[id]?.deadline ?: Long.MAX_VALUE,
            SystemClock.elapsedRealtime() + remaining
        )
        val export = LocalExport(capture, deadline)
        outgoing[id] = export
        while (outgoing.size > 8) outgoing.remove(outgoing.keys.first())
        radio?.publish(
            id,
            capture.title,
            capture.original,
            capture.capturedAtMillis,
            deadline,
            export.occurrence
        )
    }

    /** Restarting the session invalidates pinned inventories and every pending radio callback. */
    fun deleted(id: Long?) {
        requireMain()
        if (id == null) {
            inbox.clear()
            outgoing.clear()
        } else {
            outgoing.remove(id)
        }
        refresh()
    }

    fun original(root: String): NearbyOriginal? {
        requireMain()
        prune()
        return state.originals.firstOrNull { it.root == root }.takeIf { allowed && !suspended }
    }

    fun dismiss(root: String) {
        requireMain()
        inbox.remove(root)
        publish()
    }

    fun saveAllowance(root: String): () -> Boolean {
        val ticket = generation.get()
        return {
            generation.get() == ticket &&
                inbox.originals(SystemClock.elapsedRealtime()).any { it.root == root }
        }
    }

    private fun prune() {
        val elapsed = SystemClock.elapsedRealtime()
        outgoing.entries.removeAll { it.value.deadline <= elapsed }
        publish()
    }
    private fun publish() {
        state = state.copy(originals = inbox.originals(SystemClock.elapsedRealtime()))
    }
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
}
