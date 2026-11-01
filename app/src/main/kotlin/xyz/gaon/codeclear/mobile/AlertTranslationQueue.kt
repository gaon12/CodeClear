package xyz.gaon.codeclear.mobile

import java.util.concurrent.atomic.AtomicLong
import xyz.gaon.codeclear.probe.LocalEngine
import xyz.gaon.codeclear.probe.LocalInput
import xyz.gaon.codeclear.probe.LocalResult
import xyz.gaon.codeclear.probe.LocalTarget
import xyz.gaon.codeclear.translation.TranslationEngineKind

internal data class TranslationSelection(
    val target: LocalTarget,
    val engine: TranslationEngineKind,
    val detailId: Long?,
    val detailTarget: String?
)

/** Owns one translation at a time; only the controller can provide an authorized selection. */
internal class AlertTranslationQueue(
    private val store: AlertHistoryStore,
    private val worker: HistoryWorker,
    private val engine: LocalEngine,
    private val selection: () -> TranslationSelection?,
    private val refresh: () -> Unit,
    private val translated: (Long, String, TranslationEngineKind) -> Unit,
    private val cancelEngine: () -> Unit
) : AutoCloseable {
    private data class Work(val ticket: TranslationTicket, val epoch: Long, val modelEpoch: Long)
    private data class Candidate(val ticket: TranslationTicket, val cached: SavedTranslation?)
    private val workEpoch = AtomicLong()
    private val modelEpoch = AtomicLong()
    private var selecting = false
    private var active: Work? = null
    private var engineRunning = false
    private var closed = false

    // Controller work shares this boundary so late retries and peer saves cannot revive old work.
    val generation: Long get() = workEpoch.get()

    fun invalidate() {
        workEpoch.incrementAndGet()
        cancelEngine()
    }

    fun storageFailed() {
        selecting = false
        invalidate()
        if (!engineRunning) active = null
    }

    fun modelsPrepared(target: String?, selectedEngine: TranslationEngineKind) {
        modelEpoch.incrementAndGet()
        worker.submit({ store.requeueMissingModels(target, selectedEngine) }) {
            refresh()
            kick()
        }
    }

    fun kick() {
        if (closed || active != null || selecting) return
        val request = selection() ?: return
        selecting = true
        val epoch = generation
        worker.submit({
            val detailTicket = if (request.detailId != null && request.detailTarget != null) {
                store.beginTranslation(
                    request.detailId,
                    request.detailTarget,
                    engine = request.engine
                )
            } else {
                null
            }
            val candidate = detailTicket?.let { ticket ->
                Candidate(
                    ticket,
                    store.cachedTranslation(ticket.original, ticket.target, request.engine)
                )
            }
            candidate ?: store.nextQueued(request.target.languageTag, request.engine)?.let { id ->
                store.beginTranslation(id, request.target.languageTag, engine = request.engine)
                    ?.let { ticket ->
                        Candidate(
                            ticket,
                            store.cachedTranslation(
                                ticket.original,
                                request.target.languageTag,
                                request.engine
                            )
                        )
                    }
            }
        }) { candidate ->
            selecting = false
            if (epoch != generation) {
                kick()
                return@submit
            }
            if (candidate != null) {
                val job = Work(candidate.ticket, epoch, modelEpoch.get())
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
        // Model preparation is explicit; queue processing never downloads models.
        engineRunning = true
        engine.run(
            LocalInput(job.ticket.alertId, job.ticket.original, target, job.ticket.engine),
            false
        ) { result ->
            engineRunning = false
            val saved = when (result) {
                is LocalResult.Ready -> SavedTranslation(
                    TranslationPhase.READY,
                    result.text,
                    result.source,
                    engine = job.ticket.engine,
                    sameLanguage = result.sameLanguage
                )

                LocalResult.Quota -> SavedTranslation(TranslationPhase.QUOTA)

                LocalResult.TooLong -> SavedTranslation(TranslationPhase.TOO_LONG)

                LocalResult.NeedsModels -> SavedTranslation(TranslationPhase.NEEDS_MODELS)

                LocalResult.UnknownLanguage -> SavedTranslation(TranslationPhase.UNKNOWN_LANGUAGE)

                LocalResult.Unavailable -> SavedTranslation(TranslationPhase.UNAVAILABLE)

                LocalResult.UnsupportedPair -> SavedTranslation(TranslationPhase.UNSUPPORTED_PAIR)

                LocalResult.Failed -> SavedTranslation(TranslationPhase.FAILED)
            }
            finish(job, saved.copy(engine = job.ticket.engine))
        }
    }

    private fun finish(job: Work, result: SavedTranslation) {
        if (closed || active != job) return
        worker.submit({
            if (job.epoch != generation) return@submit false
            val saved = store.finishTranslation(job.ticket, result)
            // Model preparation can finish before an older SDK attempt reports missing packs.
            if (saved && result.phase == TranslationPhase.NEEDS_MODELS &&
                job.modelEpoch != modelEpoch.get() && job.epoch == generation
            ) {
                store.queueRetry(job.ticket.alertId, job.ticket.target, job.ticket.engine)
            }
            saved
        }) { saved ->
            if (active == job) active = null
            if (saved && job.epoch == generation) {
                if (result.phase == TranslationPhase.READY) {
                    translated(job.ticket.alertId, job.ticket.target, job.ticket.engine)
                }
                refresh()
            }
            kick()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        invalidate()
    }
}
