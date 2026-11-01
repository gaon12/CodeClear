package xyz.gaon.codeclear.mobile

import android.content.Context
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong
import xyz.gaon.codeclear.nearby.NearbyOriginal
import xyz.gaon.codeclear.probe.CaptureObserver
import xyz.gaon.codeclear.probe.ProbeEnvironment
import xyz.gaon.codeclear.probe.ProbeNotificationListener
import xyz.gaon.codeclear.probe.ProbeRadio
import xyz.gaon.codeclear.probe.ProbeRecord
import xyz.gaon.codeclear.probe.ProbeRoute
import xyz.gaon.codeclear.probe.ProbeSession
import xyz.gaon.codeclear.probe.isUnlocked
import xyz.gaon.codeclear.reception.NetworkCountry
import xyz.gaon.codeclear.reception.ReceptionCountry
import xyz.gaon.codeclear.translation.TranslationEngine
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationTarget

data class MobileState(
    val loaded: Boolean = false,
    val options: MobileOptions = MobileOptions(),
    val records: List<SavedAlert> = emptyList(),
    val latest: SavedAlert? = null,
    val detailId: Long? = null,
    val detailTarget: String? = null,
    val detailEngine: TranslationEngineKind? = null,
    val detailSourceLanguage: String? = null,
    val detail: SavedAlert? = null,
    val query: String = "",
    val beforeId: Long = Long.MAX_VALUE,
    val loading: Boolean = false,
    val storageError: Boolean = false
)

/** Coordinates product state and consent; workers own history execution and translation jobs. */
class MobileController(
    private val context: Context,
    private val store: AlertHistoryStore,
    private val preferences: OptionsStorage,
    engine: TranslationEngine,
    private val changed: (MobileState) -> Unit,
    translated: (Long, String, TranslationEngineKind) -> Unit = { _, _, _ -> },
    private val deleted: (Long?) -> Unit = {},
    private val releaseEngine: () -> Unit = {},
    cancelEngine: () -> Unit = {},
    private val captured: (Long, CapturedAlert) -> Unit = { _, _ -> },
    private val networkCountry: () -> ReceptionCountry? = NetworkCountry(context)::read,
    private val optionsReady: (MobileOptions) -> Unit = {}
) : CaptureObserver,
    AutoCloseable {
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
    private val worker = HistoryWorker(store::close, ::failStorage)
    private val translations = AlertTranslationQueue(
        store,
        worker,
        engine,
        ::translationSelection,
        ::refresh,
        translated,
        cancelEngine
    )
    private val captureGeneration = AtomicLong(ProbeSession.currentGeneration())
    private val observationIds = linkedMapOf<Long, Long>() // Accessed only on the database worker.

    // Main-thread owned and bounded to 128 entries.
    private val seenObservations = linkedSetOf<ObservationKey>()
    private val subscription = ProbeSession.observe(this)
    private var queryRevision = 0L
    private var queryCancellation: CancellationSignal? = null
    private var searchDelay: Runnable? = null
    private var initializing = false
    private var optionsRevision = 0L
    private var optionsSaving = false
    private var stopNearbyOnLoad = false
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
            val stopRequested = stopNearbyOnLoad
            stopNearbyOnLoad = false
            val resolved = if (stopRequested) options.copy(nearbyEnabled = false) else options
            optionsReady(resolved)
            update(state.copy(loaded = true, options = resolved, storageError = false))
            if (stopRequested) io({ preferences.save(resolved) }) {}
            syncCaptureConsent(refreshSources = true)
            refresh()
            translations.kick()
        }
    }

    fun stopNearbySharing() {
        requireMain()
        if (state.loaded) {
            changeOptions(state.options.copy(nearbyEnabled = false))
        } else {
            stopNearbyOnLoad = true
        }
    }

    fun changeOptions(options: MobileOptions) {
        requireMain()
        if (!state.loaded || closed) return
        val revision = ++optionsRevision
        optionsSaving = true
        val changedTarget = options.target != state.options.target
        val changedEngine = options.engine != state.options.engine
        val changedOutput = changedTarget || changedEngine
        if (changedOutput) {
            queryRevision++
            queryCancellation?.cancel()
        }
        val changedWork =
            changedOutput || options.translationAllowed != state.options.translationAllowed ||
                options.serverAllowed != state.options.serverAllowed ||
                options.labsEnabled != state.options.labsEnabled
        if (changedWork) {
            translations.invalidate()
        }
        update(
            state.copy(
                options = options,
                storageError = false,
                records = state.records.map {
                    it.copy(translation = it.translation.takeUnless { changedOutput })
                },
                latest = state.latest?.let {
                    it.copy(translation = it.translation.takeUnless { changedOutput })
                },
                detail = state.detail?.let {
                    it.copy(
                        translation = it.translation.takeUnless {
                            (changedTarget && state.detailTarget == null) ||
                                (changedEngine && state.detailEngine == null)
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
            if (revision == optionsRevision) {
                optionsSaving = false
                optionsReady(options)
                refresh()
                translations.kick()
            }
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
            val enriched = capture.copy(
                context = capture.context.copy(
                    country = capture.context.country ?: networkCountry()
                )
            )
            store.insertObservation(enriched)?.also { observation ->
                val id = observation.id
                store.queueTranslation(id, options.target.languageTag, options.engine)
                observationIds[record.id] = id
                if (observationIds.size > 128) observationIds.remove(observationIds.keys.first())
            }?.let { it to enriched }
        }) { result ->
            val (observation, enriched) = result ?: return@io
            if (generation == captureGeneration.get() && !capturePaused) {
                if (observation.created) captured(observation.id, enriched)
                refresh()
                translations.kick()
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

    fun saveNearby(
        original: NearbyOriginal,
        allowed: () -> Boolean,
        translate: Boolean = true,
        complete: (Long?) -> Unit
    ) {
        requireMain()
        if (!state.loaded || state.storageError || closed || !allowed()) {
            complete(null)
            return
        }
        val epoch = translations.generation
        val options = state.options
        io({
            if (epoch != translations.generation || !allowed()) return@io null
            store.insert(
                CapturedAlert(
                    sourcePackage = "xyz.gaon.codeclear.nearby",
                    source = CaptureSource.PEER,
                    title = original.title,
                    original = original.body,
                    capturedAtMillis = System.currentTimeMillis(),
                    sourceIdentity = original.root
                )
            )?.also {
                if (translate && options.translationAllowed) {
                    store.queueTranslation(
                        it,
                        options.target.languageTag,
                        options.engine
                    )
                }
            }
        }, onFailure = { complete(null) }) { id ->
            refresh()
            translations.kick()
            complete(id.takeIf { epoch == translations.generation && allowed() })
        }
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

    fun openDetail(id: Long?, target: String? = null, engine: TranslationEngineKind? = null) {
        requireMain()
        require(target == null || target in HistoryText.targets)
        val validId = id?.takeIf { it > 0 }
        update(
            state.copy(
                detailId = validId,
                detailSourceLanguage = if (validId != null && validId == state.detailId) {
                    state.detailSourceLanguage ?: state.detail?.translation?.sourceLanguage
                } else {
                    null
                },
                detailTarget = target.takeIf { validId != null },
                detailEngine = if (validId ==
                    null
                ) {
                    null
                } else {
                    engine ?: target?.let { TranslationEngineKind.ML_KIT }
                },
                detail = null
            )
        )
        refresh()
    }

    fun changeDetailTarget(id: Long, target: String, originalLanguage: String) {
        requireMain()
        val choice = TranslationTarget.entries.firstOrNull { it.languageTag == target } ?: return
        if (id != state.detailId || !state.loaded || state.storageError || closed ||
            !canTranslateDetailTo(choice, originalLanguage)
        ) {
            return
        }
        val engine = state.options.engine
        openDetail(id, target, engine)
        update(state.copy(detailSourceLanguage = originalLanguageCode(originalLanguage)))
        if (!state.options.translationAllowed) return
        val epoch = translations.generation
        io({
            if (epoch == translations.generation) store.queueRetry(id, target, engine)
        }) {
            refresh()
            translations.kick()
        }
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
                    cancellation = cancellation,
                    engine = request.options.engine
                ),
                store.page(
                    "",
                    request.options.target.languageTag,
                    limit = 1,
                    engine = request.options.engine,
                    nativeOnly = true
                )
                    .firstOrNull() ?: store.page(
                    "",
                    request.options.target.languageTag,
                    limit = 1,
                    engine = request.options.engine
                ).firstOrNull(),
                request.detailId?.let {
                    store.find(
                        it,
                        request.detailTarget ?: request.options.target.languageTag,
                        request.detailEngine ?: request.options.engine
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
        if (!state.options.translationAllowed || state.storageError) return
        val ids = listOfNotNull(
            state.latest,
            state.detail.takeIf {
                (
                    state.detailTarget == null ||
                        state.detailTarget == state.options.target.languageTag
                    ) &&
                    (state.detailEngine == null || state.detailEngine == state.options.engine)
            }
        )
            .filter {
                it.translation == null &&
                    (it.capture.source != CaptureSource.PEER || it.id == state.detailId)
            }.map { it.id }.distinct()
        if (ids.isEmpty()) return
        val target = state.options.target.languageTag
        val selectedEngine = state.options.engine
        // A target change prepares visible records, not an unrequested translation of all history.
        io({
            ids.forEach { store.queueTranslation(it, target, selectedEngine) }
        }) { translations.kick() }
    }

    fun delete(id: Long?) {
        requireMain()
        translations.invalidate()
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
            translations.kick()
        }
    }

    fun retry(
        id: Long,
        target: String = state.options.target.languageTag,
        selectedEngine: TranslationEngineKind = state.options.engine
    ) {
        requireMain()
        require(target in HistoryText.targets)
        if (!state.loaded || state.storageError || !state.options.translationAllowed ||
            selectedEngine != state.options.engine || closed
        ) {
            return
        }
        val epoch = translations.generation
        io({
            epoch == translations.generation && store.queueRetry(id, target, selectedEngine)
        }) { queued ->
            if (queued && epoch == translations.generation) {
                refresh()
                translations.kick()
            }
        }
    }

    fun modelsPrepared(
        target: String? = null,
        selectedEngine: TranslationEngineKind = TranslationEngineKind.ML_KIT
    ) {
        requireMain()
        translations.modelsPrepared(target, selectedEngine)
    }

    fun retryStorage() {
        if (state.loaded) {
            changeOptions(state.options)
        } else {
            initializing = false
            start()
        }
    }

    private fun translationSelection(): TranslationSelection? {
        if (closed || !state.loaded || state.storageError || !state.options.translationAllowed ||
            optionsSaving
        ) {
            return null
        }
        return TranslationSelection(
            state.options.target,
            state.options.engine,
            state.detailId,
            state.detailTarget?.takeIf {
                it != state.options.target.languageTag && state.detailEngine == state.options.engine
            }
        )
    }

    private fun <T> io(block: () -> T, onFailure: () -> Unit = {}, success: (T) -> Unit) =
        worker.submit(block, onFailure, success)

    private fun failStorage() {
        if (closed || handlingFailure) return
        handlingFailure = true
        initializing = false
        optionsSaving = false
        optionsRevision++
        translations.storageFailed()
        queryRevision++
        queryCancellation?.cancel()
        update(
            state.copy(
                storageError = true,
                loading = false,
                options = state.options.copy(
                    notificationConsent = false,
                    accessibilityConsent = false,
                    localConsent = false,
                    serverAllowed = false,
                    nearbyEnabled = false
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
        return worker.awaitClosed(timeoutMillis)
    }

    override fun close() {
        requireMain()
        if (closed) return
        closed = true
        translations.close()
        subscription.close()
        queryCancellation?.cancel()
        main.removeCallbacksAndMessages(null)
        worker.close()
        releaseEngine()
    }
}
