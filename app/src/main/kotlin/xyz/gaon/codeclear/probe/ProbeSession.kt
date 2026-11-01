package xyz.gaon.codeclear.probe

import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xyz.gaon.codeclear.BuildConfig

interface CaptureObserver {
    fun added(generation: Long, record: ProbeRecord) {}
    fun updated(generation: Long, record: ProbeRecord) {}
    fun cleared(generation: Long) {}
}

data class ProbeSessionState(
    val notificationConsent: Boolean = false,
    val accessibilityConsent: Boolean = false,
    val notificationConnected: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val hardwareOverride: Boolean = false,
    val shellFixture: Boolean = false,
    val records: List<ProbeRecord> = emptyList(),
    val lastAccessibilityWindowClass: String? = null
)

/** In-memory coordination; persistent history and product consent restoration have separate owners. */
object ProbeSession {
    private val buffer = ProbeBuffer()
    private val observers = linkedSetOf<CaptureObserver>()
    var state by mutableStateOf(ProbeSessionState())
        private set

    /** Observers must return promptly; persistence and translation belong on their own workers. */
    fun observe(observer: CaptureObserver): AutoCloseable {
        checkMainThread()
        observers += observer
        return AutoCloseable {
            checkMainThread()
            observers -= observer
        }
    }

    fun configure(route: ProbeRoute, consent: Boolean) {
        checkMainThread()
        clear()
        state = when (route) {
            ProbeRoute.NOTIFICATION -> state.copy(notificationConsent = consent)
            ProbeRoute.ACCESSIBILITY -> state.copy(accessibilityConsent = consent)
        }
    }

    fun setConnected(route: ProbeRoute, connected: Boolean) {
        checkMainThread()
        state = when (route) {
            ProbeRoute.NOTIFICATION -> state.copy(notificationConnected = connected)
            ProbeRoute.ACCESSIBILITY -> state.copy(accessibilityConnected = connected)
        }
    }

    fun setDiagnostics(
        hardwareOverride: Boolean = state.hardwareOverride,
        shell: Boolean = state.shellFixture
    ) {
        checkMainThread()
        require(BuildConfig.DEBUG || (!hardwareOverride && !shell))
        clear()
        state = state.copy(hardwareOverride = hardwareOverride, shellFixture = shell)
    }

    fun ticket(route: ProbeRoute, environment: ProbeEnvironment): Long? {
        checkMainThread()
        val consent = if (route == ProbeRoute.NOTIFICATION) {
            state.notificationConsent
        } else {
            state.accessibilityConsent
        }
        val access = if (route == ProbeRoute.NOTIFICATION) {
            environment.notificationGranted
        } else {
            environment.accessibilityGranted
        }
        return if (ProbeSourcePolicy.mayCollect(
                environment.radio,
                consent,
                access,
                state.hardwareOverride
            )
        ) {
            buffer.generation
        } else {
            null
        }
    }

    fun publish(ticket: Long, observation: ProbeObservation): Long? {
        checkMainThread()
        if (!buffer.append(ticket, observation)) return null
        state = state.copy(records = buffer.snapshot())
        val record = state.records.last()
        observers.toList().forEach { it.added(ticket, record) }
        return record.id
    }

    fun annotateHeading(ticket: Long, recordId: Long, hint: ProbeHeadingKind) {
        checkMainThread()
        if (buffer.annotateHeading(ticket, recordId, hint)) {
            state = state.copy(records = buffer.snapshot())
            state.records.find { it.id == recordId }?.let { record ->
                observers.toList().forEach { it.updated(ticket, record) }
            }
        }
    }

    fun currentGeneration(): Long {
        checkMainThread()
        return buffer.generation
    }

    fun noteAccessibilityWindow(className: String) {
        checkMainThread()
        if (state.accessibilityConsent) {
            val safeClass = className.takeIf { it.matches(Regex("[A-Za-z0-9_.$]{1,256}")) }
            state = state.copy(lastAccessibilityWindowClass = safeClass)
        }
    }

    fun clear() {
        checkMainThread()
        ProbeSourceActions.clear()
        buffer.clear()
        state = state.copy(records = emptyList(), lastAccessibilityWindowClass = null)
        observers.toList().forEach { it.cleared(buffer.generation) }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }
}
