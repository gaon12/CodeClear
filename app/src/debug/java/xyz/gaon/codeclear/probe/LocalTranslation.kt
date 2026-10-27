package xyz.gaon.codeclear.probe

enum class LocalTarget(val languageTag: String, val modelCode: String) {
    KOREAN("ko", "ko"),
    SIMPLIFIED_CHINESE("zh-Hans", "zh"),
    TRADITIONAL_CHINESE("zh-Hant", "zh"),
    JAPANESE("ja", "ja"),
    ENGLISH("en", "en"),
    FRENCH("fr", "fr"),
    GERMAN("de", "de")
}

data class LocalInput(val recordId: Long, val body: String, val target: LocalTarget) {
    init {
        require(body.isNotBlank() && body.length <= 8192)
    }
}

sealed interface LocalResult {
    data class Ready(val text: String, val source: String, val sameLanguage: Boolean) : LocalResult
    data object NeedsModels : LocalResult
    data object UnknownLanguage : LocalResult
    data object Failed : LocalResult
}

sealed interface LocalState {
    data object Idle : LocalState
    data object Working : LocalState
    data class Finished(val result: LocalResult) : LocalState
}

fun interface LocalEngine {
    fun run(input: LocalInput, allowModelDownload: Boolean, complete: (LocalResult) -> Unit)
}

/** Main-thread coordinator: one active SDK operation and one replaceable pending input. */
class LocalTranslationCoordinator(
    private val engine: LocalEngine,
    private val changed: (LocalState) -> Unit
) {
    private data class Job(val revision: Long, val input: LocalInput, val download: Boolean)

    private var selected: LocalInput? = null
    private var revision = 0L
    private var active: Job? = null
    private var pending: Job? = null
    var state: LocalState = LocalState.Idle
        private set

    fun select(input: LocalInput?) {
        if (selected == input) return
        selected = input
        revision++
        pending = input?.let { Job(revision, it, false) }
        update(if (input == null) LocalState.Idle else LocalState.Working)
        startPending()
    }

    fun retry(allowModelDownload: Boolean = false) {
        val input = selected ?: return
        if (state == LocalState.Working) return
        // A download action belongs only to the exact selected input and target.
        if (allowModelDownload && state != LocalState.Finished(LocalResult.NeedsModels)) return
        pending = Job(++revision, input, allowModelDownload)
        update(LocalState.Working)
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
                update(LocalState.Finished(result))
            }
            startPending()
        }
    }

    private fun update(value: LocalState) {
        state = value
        changed(value)
    }
}
