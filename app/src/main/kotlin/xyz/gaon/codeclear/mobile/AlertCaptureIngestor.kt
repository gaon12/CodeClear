package xyz.gaon.codeclear.mobile

import android.os.Looper
import java.util.concurrent.atomic.AtomicLong
import xyz.gaon.codeclear.capture.CaptureObserver
import xyz.gaon.codeclear.capture.ProbeEnvironment
import xyz.gaon.codeclear.capture.ProbeRadio
import xyz.gaon.codeclear.capture.ProbeRecord
import xyz.gaon.codeclear.capture.ProbeRoute
import xyz.gaon.codeclear.capture.ProbeSession
import xyz.gaon.codeclear.probe.ProbeNotificationListener
import xyz.gaon.codeclear.reception.ReceptionCountry

/** Maps consented source observations into history without owning screen or translation state. */
internal class AlertCaptureIngestor(
    private val store: AlertHistoryStore,
    private val worker: HistoryWorker,
    private val state: () -> MobileState,
    private val environment: () -> ProbeEnvironment,
    private val captured: (Long, CapturedAlert) -> Unit,
    private val networkCountry: () -> ReceptionCountry?,
    private val changed: () -> Unit
) : CaptureObserver,
    AutoCloseable {
    private data class ObservationKey(
        val source: String,
        val identity: String,
        val title: String,
        val body: String
    )
    private val captureGeneration = AtomicLong(ProbeSession.currentGeneration())
    private val observationIds = linkedMapOf<Long, Long>() // Accessed only on the database worker.

    // Main-thread owned and bounded to 128 entries.
    private val seenObservations = linkedMapOf<ObservationKey, Long>()
    private val observationAliases = linkedMapOf<Long, Long>()
    private var capturePaused = false
    private var closed = false

    fun syncConsent(refreshSources: Boolean = false) {
        if (closed || capturePaused) return
        val options = state().options
        val notification = options.notificationConsent && !capturePaused
        val accessibility = options.accessibilityConsent && !capturePaused
        val notificationChanged =
            ProbeSession.state.notificationConsent != notification
        val accessibilityChanged =
            ProbeSession.state.accessibilityConsent != accessibility
        if (notificationChanged) {
            ProbeSession.configure(ProbeRoute.NOTIFICATION, notification)
        }
        if (accessibilityChanged) {
            ProbeSession.configure(ProbeRoute.ACCESSIBILITY, accessibility)
        }
        if (notification &&
            (notificationChanged || accessibilityChanged || refreshSources)
        ) {
            ProbeNotificationListener.inspectConsentedSources()
        }
    }

    /** Debug diagnostics own temporary consent separately and must not write product history. */
    fun pauseCapture(paused: Boolean) {
        requireMain()
        if (closed) return
        capturePaused = paused
        if (paused) {
            ProbeSession.configure(ProbeRoute.NOTIFICATION, false)
            ProbeSession.configure(ProbeRoute.ACCESSIBILITY, false)
        }
        if (state().loaded) syncConsent(refreshSources = !paused)
    }

    override fun added(generation: Long, record: ProbeRecord) {
        requireMain()
        if (!state().loaded || closed || capturePaused ||
            generation != captureGeneration.get()
        ) {
            return
        }
        val environment = environment()
        val options = state().options
        val capture = record.toHistoryCapture(
            environment.systemPackages,
            environment.radio == ProbeRadio.PRESENT,
            options.notificationConsent && environment.notificationGranted,
            options.accessibilityConsent && environment.accessibilityGranted
        ) ?: return
        val key = capture.sourceIdentity?.let {
            ObservationKey(capture.sourcePackage, it, capture.title, capture.original)
        }
        val previous = key?.let(seenObservations::get)
        if (previous != null) {
            // A later heading review can refer to a suppressed duplicate's observation ID.
            observationAliases[record.id] = previous
            if (observationAliases.size > 128) {
                observationAliases.remove(observationAliases.keys.first())
            }
            return
        }
        if (key != null) {
            seenObservations[key] = record.id
            if (seenObservations.size > 128) seenObservations.remove(seenObservations.keys.first())
        }
        worker.submit({
            if (generation != captureGeneration.get()) return@submit null
            val enriched = capture.copy(
                context = capture.context.copy(
                    country = capture.context.country ?: networkCountry()
                )
            )
            store.insertObservation(enriched)?.also { observation ->
                val id = observation.id
                store.queueTranslation(id, options.target.languageTag, options.engine)
                observationIds[record.id] = observation.observationId
                if (observationIds.size > 128) observationIds.remove(observationIds.keys.first())
            }?.let { it to enriched }
        }) { result ->
            val (observation, enriched) = result ?: return@submit
            if (generation == captureGeneration.get() && !capturePaused) {
                if (observation.created) captured(observation.id, enriched)
                changed()
            }
        }
    }

    override fun updated(generation: Long, record: ProbeRecord) {
        requireMain()
        if (closed || capturePaused || generation != captureGeneration.get()) return
        val observationId = observationAliases[record.id] ?: record.id
        worker.submit({
            if (generation == captureGeneration.get()) {
                observationIds[observationId]?.let {
                    store.updateHeading(it, record.observation.headingHint.name)
                }
            }
        }) { changed() }
    }

    override fun cleared(generation: Long) {
        requireMain()
        captureGeneration.set(generation)
        seenObservations.clear()
        observationAliases.clear()
        if (!closed && !state().storageError) worker.submit({ observationIds.clear() }) {}
    }

    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())

    override fun close() {
        requireMain()
        if (closed) return
        closed = true
        captureGeneration.incrementAndGet()
        seenObservations.clear()
        observationAliases.clear()
    }
}
