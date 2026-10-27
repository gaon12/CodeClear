package xyz.gaon.codeclear.probe

import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

/** Consent and temporary observations intentionally disappear when this diagnostic process ends. */
object ProbeSession {
    private val buffer = ProbeBuffer()
    var state by mutableStateOf(ProbeSessionState())
        private set

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

    fun publish(ticket: Long, observation: ProbeObservation) {
        checkMainThread()
        if (buffer.append(ticket, observation)) state = state.copy(records = buffer.snapshot())
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
        buffer.clear()
        state = state.copy(records = emptyList(), lastAccessibilityWindowClass = null)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }
}
