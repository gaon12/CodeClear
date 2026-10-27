package xyz.gaon.codeclear.mobile

import android.content.Context
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import xyz.gaon.codeclear.probe.CaptureObserver
import xyz.gaon.codeclear.probe.LocalEngine
import xyz.gaon.codeclear.probe.LocalInput
import xyz.gaon.codeclear.probe.LocalResult
import xyz.gaon.codeclear.probe.LocalTarget
import xyz.gaon.codeclear.probe.ProbeEnvironment
import xyz.gaon.codeclear.probe.ProbeNotificationListener
import xyz.gaon.codeclear.probe.ProbeRadio
import xyz.gaon.codeclear.probe.ProbeRecord
import xyz.gaon.codeclear.probe.ProbeRoute
import xyz.gaon.codeclear.probe.ProbeSession
import xyz.gaon.codeclear.probe.isUnlocked

data class MobileState(
    val loaded: Boolean = false,
    val options: MobileOptions = MobileOptions(),
    val records: List<SavedAlert> = emptyList(),
    val latest: SavedAlert? = null,
    val detailId: Long? = null,
    val detailTarget: String? = null,
    val detail: SavedAlert? = null,
    val query: String = "",
    val beforeId: Long = Long.MAX_VALUE,
    val loading: Boolean = false,
    val storageError: Boolean = false
)

/** Owns database work and one local translation. Activities only render state and submit actions. */
class MobileController(
    private val context: Context,
    private val store: AlertHistoryStore,
    private val preferences: OptionsStorage,
    private val engine: LocalEngine,
    private val changed: (MobileState) -> Unit,
    private val translated: (Long, String) -> Unit = { _, _ -> },
    private val deleted: (Long?) -> Unit = {},
    private val releaseEngine: () -> Unit = {}
) : CaptureObserver,
    AutoCloseable {
    private data class Work(val ticket: TranslationTicket, val epoch: Long)
    private data class Candidate(val ticket: TranslationTicket, val cached: SavedTranslation?)
    private data class ObservationKey(
        val source: String,
        val identity: String,
        val title: String,
        val body: String
    )
    private data class ViewSnapshot(
        val records: List<SavedAlert>,
        val latest: SavedAlert?,
        val detail: SavedAlert?
    )
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128))
    private val workEpoch = AtomicLong()
    private val captureGeneration = AtomicLong(ProbeSession.currentGeneration())
    private val observationIds = linkedMapOf<Long, Long>() // Accessed only on the database worker.

    // Main-thread owned and bounded to 128 entries.
    private val seenObservations = linkedSetOf<ObservationKey>()
    private val subscription = ProbeSession.observe(this)
    private var queryRevision = 0L
    private var queryCancellation: CancellationSignal? = null
    private var searchDelay: Runnable? = null
    private var initializing = false
    private var selecting = false
    private var active: Work? = null
    private var engineRunning = false
    private var handlingFailure = false
    private var capturePaused = false

    @Volatile private var closed = false
    var state = MobileState()
        private set

    fun start() {
        requireMain()
        if (closed || initializing || state.loaded || !context.isUnlocked()) return
        initializing = true
        io({
            store.recoverInterruptedWork()
            preferences.load()
        }) { options ->
            initializing = false
            update(state.copy(loaded = true, options = options, storageError = false))
            syncCaptureConsent(refreshSources = true)
            refresh()
            kick()
        }
    }

    fun changeOptions(options: MobileOptions) {
        requireMain()
        if (!state.loaded || closed) return
        val changedTarget = options.target != state.options.target
        if (changedTarget) {
            queryRevision++
            queryCancellation?.cancel()
        }
        val changedWork = changedTarget || options.localConsent != state.options.localConsent
        if (changedWork) workEpoch.incrementAndGet()
        update(
            state.copy(
                options = options,
                storageError = false,
                records = state.records.map {
                    it.copy(translation = it.translation.takeUnless { changedTarget })
                },
                latest = state.latest?.let {
                    it.copy(translation = it.translation.takeUnless { changedTarget })
                },
                detail = state.detail?.let {
                    it.copy(
                        translation = it.translation.takeUnless {
                            changedTarget &&
                                state.detailTarget == null
                        }
                    )
                }
            )
        )
        syncCaptureConsent()
        io({
            preferences.save(options)
            if (changedWork) store.recoverInterruptedWork()
        }) {
            refresh()
            kick()
        }
    }

    private fun syncCaptureConsent(refreshSources: Boolean = false) {
        if (capturePaused) return
        val options = state.options
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
        capturePaused = paused
        if (paused) {
            ProbeSession.configure(ProbeRoute.NOTIFICATION, false)
            ProbeSession.configure(ProbeRoute.ACCESSIBILITY, false)
        }
        if (state.loaded) syncCaptureConsent(refreshSources = !paused)
    }

    override fun added(generation: Long, record: ProbeRecord) {
        requireMain()
        if (!state.loaded || closed || capturePaused ||
            generation != captureGeneration.get()
        ) {
            return
        }
        val environment = ProbeEnvironment.read(context)
        val options = state.options
        val capture = record.toHistoryCapture(
            environment.systemPackages,
            environment.radio == ProbeRadio.PRESENT,
            options.notificationConsent && environment.notificationGranted,
            options.accessibilityConsent && environment.accessibilityGranted
        ) ?: return
        val key = capture.sourceIdentity?.let {
            ObservationKey(capture.sourcePackage, it, capture.title, capture.original)
        }
        if (key != null && key in seenObservations) return
        if (key != null) {
            seenObservations += key
            if (seenObservations.size > 128) seenObservations.remove(seenObservations.first())
        }
        io({
            if (generation != captureGeneration.get()) return@io null
            store.insert(capture)?.also { id ->
                store.queueTranslation(id, options.target.languageTag)
                observationIds[record.id] = id
                if (observationIds.size > 128) observationIds.remove(observationIds.keys.first())
            }
        }) { id ->
            if (id != null) {
                refresh()
                kick()
            }
        }
    }

    override fun updated(generation: Long, record: ProbeRecord) {
        if (closed || capturePaused || generation != captureGeneration.get()) return
        io({
            if (generation == captureGeneration.get()) {
                observationIds[record.id]?.let {
                    store.updateHeading(it, record.observation.headingHint.name)
                }
            }
        }) { refresh() }
    }

    override fun cleared(generation: Long) {
        captureGeneration.set(generation)
        seenObservations.clear()
        if (!closed && !state.storageError) io({ observationIds.clear() }) {}
    }

    fun search(query: String) {
        requireMain()
        val bounded = query.take(256)
        searchDelay?.let(main::removeCallbacks)
        queryCancellation?.cancel()
        queryRevision++
        update(
            state.copy(
                query = bounded,
                beforeId = Long.MAX_VALUE,
                records = emptyList(),
                loading = true
            )
        )
        searchDelay = Runnable { refresh() }.also { main.postDelayed(it, 250) }
    }

    fun older() {
        val before = state.records.lastOrNull()?.id ?: return
        update(state.copy(beforeId = before))
        refresh()
    }

    fun newest() {
        update(state.copy(beforeId = Long.MAX_VALUE))
        refresh()
    }

    fun openDetail(id: Long?, target: String? = null) {
        requireMain()
        require(target == null || target in HistoryText.targets)
        update(state.copy(detailId = id?.takeIf { it > 0 }, detailTarget = target, detail = null))
        refresh()
    }

    fun refresh() {
        requireMain()
        if (!state.loaded || state.storageError || closed) return
        val revision = ++queryRevision
        queryCancellation?.cancel()
        val cancellation = CancellationSignal().also { queryCancellation = it }
        val request = state
        update(state.copy(loading = true))
        io({
            ViewSnapshot(
                store.page(
                    request.query,
                    request.options.target.languageTag,
                    request.beforeId,
                    cancellation = cancellation
                ),
                store.page("", request.options.target.languageTag, limit = 1).firstOrNull(),
                request.detailId?.let {
                    store.find(
                        it,
                        request.detailTarget ?: request.options.target.languageTag
                    )
                }
            )
        }) { view ->
            if (revision == queryRevision) {
                update(
                    state.copy(
                        records = view.records,
                        latest = view.latest,
                        detail = view.detail,
                        loading = false
                    )
                )
                prepareVisibleRecords()
            }
        }
    }

    private fun prepareVisibleRecords() {
        if (!state.options.localConsent || state.storageError) return
        val ids = listOfNotNull(
            state.latest,
            state.detail.takeIf {
                state.detailTarget == null ||
                    state.detailTarget == state.options.target.languageTag
            }
        )
            .filter { it.translation == null }.map { it.id }.distinct()
        if (ids.isEmpty()) return
        val target = state.options.target.languageTag
        // A target change prepares visible records, not an unrequested translation of all history.
        io({ ids.forEach { store.queueTranslation(it, target) } }) { kick() }
    }

    fun delete(id: Long?) {
        requireMain()
        workEpoch.incrementAndGet()
        queryRevision++
        queryCancellation?.cancel()
        ProbeSession.clear()
        deleted(id)
        update(state.copy(loading = true))
        io({
            if (id ==
                null
            ) {
                store.deleteAll()
            } else {
                store.delete(id)
            }
            store.recoverInterruptedWork()
        }) {
            refresh()
            kick()
        }
    }

    fun retry(id: Long, target: String = state.options.target.languageTag) {
        requireMain()
        require(target in HistoryText.targets)
        if (!state.options.localConsent || active != null || selecting || closed) return
        selecting = true
        val epoch = workEpoch.get()
        io({ store.beginTranslation(id, target, retry = true) }) { ticket ->
            selecting = false
            if (ticket != null && epoch == workEpoch.get()) run(Work(ticket, epoch)) else kick()
        }
    }

    fun modelsPrepared(target: String = state.options.target.languageTag) {
        io({ store.requeueMissingModels(target) }) {
            refresh()
            kick()
        }
    }

    fun retryStorage() {
        if (state.loaded) {
            changeOptions(state.options)
        } else {
            initializing = false
            start()
        }
    }

    private fun kick() {
        if (closed || !state.loaded || state.storageError || !state.options.localConsent ||
            active != null ||
            selecting
        ) {
            return
        }
        selecting = true
        val epoch = workEpoch.get()
        val target = state.options.target
        io({
            store.nextQueued(target.languageTag)?.let { id ->
                store.beginTranslation(id, target.languageTag)?.let { ticket ->
                    Candidate(ticket, store.cachedTranslation(ticket.original, target.languageTag))
                }
            }
        }) { candidate ->
            selecting = false
            if (epoch != workEpoch.get()) {
                kick()
                return@io
            }
            if (candidate != null) {
                val job = Work(candidate.ticket, epoch)
                if (candidate.cached == null) {
                    run(job)
                } else {
                    active = job
                    finish(job, candidate.cached)
                }
            }
        }
    }

    private fun run(job: Work) {
        active = job
        refresh()
        val target = LocalTarget.entries.first { it.languageTag == job.ticket.target }
        // Model preparation is a separate explicit action; background translation never downloads.
        engineRunning = true
        engine.run(LocalInput(job.ticket.alertId, job.ticket.original, target), false) { result ->
            engineRunning = false
            val saved = when (result) {
                is LocalResult.Ready -> SavedTranslation(
                    TranslationPhase.READY,
                    result.text,
                    result.source
                )

                LocalResult.NeedsModels -> SavedTranslation(TranslationPhase.NEEDS_MODELS)

                LocalResult.UnknownLanguage -> SavedTranslation(TranslationPhase.UNKNOWN_LANGUAGE)

                else -> SavedTranslation(TranslationPhase.FAILED)
            }
            finish(job, saved)
        }
    }

    private fun finish(job: Work, result: SavedTranslation) {
        if (closed || active != job) return
        io({
            if (job.epoch == workEpoch.get()) store.finishTranslation(job.ticket, result) else false
        }) { saved ->
            if (active == job) active = null
            if (saved && job.epoch == workEpoch.get()) {
                if (result.phase ==
                    TranslationPhase.READY
                ) {
                    translated(job.ticket.alertId, job.ticket.target)
                }
                refresh()
            }
            kick()
        }
    }

    private fun <T> io(block: () -> T, success: (T) -> Unit) {
        if (closed) return
        try {
            worker.execute {
                if (closed) return@execute
                try {
                    val result = block()
                    main.post { if (!closed) success(result) }
                } catch (_: OperationCanceledException) {
                    // A newer view request owns the UI; cancellation is not a storage failure.
                } catch (_: Exception) {
                    main.post { failStorage() }
                }
            }
        } catch (_: RejectedExecutionException) {
            failStorage()
        }
    }

    private fun failStorage() {
        if (closed || handlingFailure) return
        handlingFailure = true
        initializing = false
        selecting = false
        workEpoch.incrementAndGet()
        queryRevision++
        queryCancellation?.cancel()
        if (!engineRunning) active = null
        update(
            state.copy(
                storageError = true,
                loading = false,
                options = state.options.copy(
                    notificationConsent = false,
                    accessibilityConsent = false,
                    localConsent = false
                )
            )
        )
        syncCaptureConsent()
        handlingFailure = false
    }

    private fun update(value: MobileState) {
        state = value
        changed(value)
    }

    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())

    fun awaitClosed(timeoutMillis: Long): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        return worker.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        requireMain()
        if (closed) return
        closed = true
        workEpoch.incrementAndGet()
        subscription.close()
        queryCancellation?.cancel()
        main.removeCallbacksAndMessages(null)
        worker.queue.clear()
        worker.execute { store.close() }
        worker.shutdown()
        releaseEngine()
    }
}
