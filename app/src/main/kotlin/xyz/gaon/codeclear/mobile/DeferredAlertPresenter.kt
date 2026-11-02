package xyz.gaon.codeclear.mobile

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import xyz.gaon.codeclear.capture.ProbeSession
import xyz.gaon.codeclear.capture.SourceWindowChange
import xyz.gaon.codeclear.capture.SourceWindowVisibility
import xyz.gaon.codeclear.capture.isUnlocked
import xyz.gaon.codeclear.translation.TranslationEngineKind

/** Optional, bounded presentation work never owns collection or translation. */
internal class DeferredAlertPresenter(
    private val context: Context,
    private val host: DeferredAlertSurface
) {
    private val queue = DeferredAlertQueue()
    private val main = Handler(Looper.getMainLooper())
    private var options: MobileOptions? = null
    private var activatedAt = Long.MAX_VALUE
    private var subscription: AutoCloseable? = null
    private var receiverRegistered = false
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = clear()
    }
    private val poll = Runnable { present() }

    fun authorize(value: MobileOptions?) {
        val authorized = value?.takeIf {
            it.deferredOverlayEnabled && it.notificationConsent && it.accessibilityConsent
        }
        if (options == authorized) return
        clear()
        subscription?.close()
        subscription = null
        options = authorized
        activatedAt = System.currentTimeMillis()
        if (options != null) {
            subscription = SourceWindowVisibility.observe { change ->
                host.hide()
                if (change == SourceWindowChange.UNAVAILABLE) {
                    clear()
                } else {
                    queue.sourceAppeared()
                    schedule()
                }
            }
        }
    }

    fun observed(stored: StoredObservation, alert: CapturedAlert, window: Int?, display: Int?) {
        val current = options ?: return
        if (alert.capturedAtMillis < activatedAt || !collectionReady() || !deviceVisible()) {
            return
        }
        val wallNow = System.currentTimeMillis()
        if (stored.created && alert.source == CaptureSource.NOTIFICATION &&
            alert.postedAtMillis?.let { wallNow - it in -5000..15_000 } == true
        ) {
            host.hide()
            queue.sourceAppeared()
        }
        queue.observed(
            stored,
            alert,
            window,
            display,
            current.target.languageTag,
            current.engine,
            wallNow,
            SystemClock.elapsedRealtime()
        )
        schedule()
    }

    fun translated(id: Long, target: String, engine: TranslationEngineKind, body: String) {
        queue.translated(id, target, engine, body)
        schedule()
    }

    fun clear() {
        activatedAt = System.currentTimeMillis()
        queue.clear()
        host.hide()
        main.removeCallbacks(poll)
        stopWatchingScreen()
    }

    private fun stopWatchingScreen() {
        if (receiverRegistered) {
            receiverRegistered = false
            context.unregisterReceiver(screenOff)
        }
    }

    fun activityChanged() {
        host.hide()
        schedule()
    }

    private fun collectionReady(): Boolean = ProbeSession.state.let {
        it.notificationConsent && it.accessibilityConsent &&
            it.notificationConnected && it.accessibilityConnected
    }

    private fun deviceVisible(): Boolean = context.isUnlocked() &&
        context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun schedule() {
        main.removeCallbacks(poll)
        if (!queue.hasPending) {
            stopWatchingScreen()
            return
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                screenOff,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        main.post(poll)
    }

    private fun present() {
        try {
            presentIfReady()
        } catch (_: RuntimeException) {
            // A failed optional window must never crash the process that collects alerts.
            clear()
        }
    }

    private fun presentIfReady() {
        val current = options
        if (current == null || !collectionReady() || !deviceVisible() ||
            (!host.foreground && !Settings.canDrawOverlays(context))
        ) {
            return clear()
        }
        val candidate = queue.select(
            SystemClock.elapsedRealtime(),
            SourceWindowVisibility.snapshot()
        )
        if (candidate == null) {
            host.hide()
        } else {
            host.show(candidate, current) {
                queue.dismiss(candidate.id)
                schedule()
            }
        }
        if (queue.hasPending) {
            main.postDelayed(poll, 500)
        } else {
            clear()
        }
    }
}
