package xyz.gaon.codeclear.translation

enum class TranslationTarget(val languageTag: String, val modelCode: String) {
    KOREAN("ko", "ko"),
    SIMPLIFIED_CHINESE("zh-Hans", "zh"),
    TRADITIONAL_CHINESE("zh-Hant", "zh"),
    JAPANESE("ja", "ja"),
    ENGLISH("en", "en"),
    FRENCH("fr", "fr"),
    GERMAN("de", "de")
}

data class TranslationInput(
    val recordId: Long,
    val body: String,
    val target: TranslationTarget,
    val engine: TranslationEngineKind = TranslationEngineKind.ML_KIT
) {
    init {
        require(body.isNotBlank() && body.length <= 8192)
    }
}

sealed interface TranslationResult {
    data class Ready(
        val text: String,
        val source: String,
        val sameLanguage: Boolean,
        val issuer: IssuerClaim? = null
    ) : TranslationResult
    data object NeedsModels : TranslationResult
    data object UnknownLanguage : TranslationResult
    data object Unavailable : TranslationResult
    data object UnsupportedPair : TranslationResult
    data object Quota : TranslationResult
    data object TooLong : TranslationResult
    data object Failed : TranslationResult
}

sealed interface TranslationState {
    data object Idle : TranslationState
    data object Working : TranslationState
    data class Finished(val result: TranslationResult) : TranslationState
}

fun interface TranslationEngine {
    fun run(
        input: TranslationInput,
        allowModelDownload: Boolean,
        complete: (TranslationResult) -> Unit
    )
}

/** Main-thread coordinator: one active SDK operation and one replaceable pending input. */
class TranslationCoordinator(
    private val engine: TranslationEngine,
    private val changed: (TranslationState) -> Unit
) {
    private data class Job(val revision: Long, val input: TranslationInput, val download: Boolean)

    private var selected: TranslationInput? = null
    private var revision = 0L
    private var active: Job? = null
    private var pending: Job? = null
    var state: TranslationState = TranslationState.Idle
        private set

    fun select(input: TranslationInput?) {
        if (selected == input) return
        selected = input
        revision++
        pending = input?.let { Job(revision, it, false) }
        update(if (input == null) TranslationState.Idle else TranslationState.Working)
        startPending()
    }

    fun retry(allowModelDownload: Boolean = false) {
        val input = selected ?: return
        if (state == TranslationState.Working) return
        // A download action belongs only to the exact selected input and target.
        if (allowModelDownload &&
            state != TranslationState.Finished(TranslationResult.NeedsModels)
        ) {
            return
        }
        pending = Job(++revision, input, allowModelDownload)
        update(TranslationState.Working)
        startPending()
    }

    private fun startPending() {
        if (active != null) return
        val job = pending ?: return
        pending = null
        active = job
        engine.run(job.input, job.download) { result ->
            if (active != job) return@run
            active = null
            if (revision == job.revision && selected == job.input) {
                update(TranslationState.Finished(result))
            }
            startPending()
        }
    }

    private fun update(value: TranslationState) {
        state = value
        changed(value)
    }
}
