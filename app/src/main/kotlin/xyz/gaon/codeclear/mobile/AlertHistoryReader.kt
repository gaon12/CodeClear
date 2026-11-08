package xyz.gaon.codeclear.mobile

import android.os.CancellationSignal

internal data class HistoryView(
    val records: List<SavedAlert>,
    val latest: SavedAlert?,
    val detail: SavedAlert?
)

/** Reads the bounded product view on the history worker; callers own cancellation and delivery. */
internal class AlertHistoryReader(private val store: AlertHistoryStore) {
    fun read(request: MobileState, cancellation: CancellationSignal): HistoryView = HistoryView(
        store.page(
            request.query,
            request.options.target.languageTag,
            request.beforeId,
            cancellation = cancellation,
            engine = request.options.engine,
            groupIdenticalOriginals = true
        ),
        store.page(
            "",
            request.options.target.languageTag,
            limit = 1,
            cancellation = cancellation,
            engine = request.options.engine,
            nativeOnly = true
        )
            .firstOrNull() ?: store.page(
            "",
            request.options.target.languageTag,
            limit = 1,
            cancellation = cancellation,
            engine = request.options.engine
        ).firstOrNull(),
        request.detailId?.let {
            store.find(
                it,
                request.detailTarget ?: request.options.target.languageTag,
                request.detailEngine ?: request.options.engine,
                withObservations = true,
                observationBeforeId = request.observationBeforeId,
                cancellation = cancellation
            )
        }
    )
}
