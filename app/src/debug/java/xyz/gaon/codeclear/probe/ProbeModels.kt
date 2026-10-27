package xyz.gaon.codeclear.probe

enum class ProbeRoute { NOTIFICATION, ACCESSIBILITY }

enum class ProbeOrigin { SYSTEM_CANDIDATE, SHELL_FIXTURE }

enum class ProbeRadio { PRESENT, ABSENT, UNKNOWN }

data class ProbeObservation(
    val packageName: String,
    val route: ProbeRoute,
    val origin: ProbeOrigin,
    val title: String,
    val body: String,
    val capturedAtMillis: Long,
    val postedAtMillis: Long? = null,
    val limited: Boolean = false,
    val groupSummary: Boolean = false,
    val platformBoundaryPossible: Boolean = false
)

data class ProbeRecord(val id: Long, val observation: ProbeObservation)

data class BoundedText(val value: String, val limited: Boolean) {
    companion object {
        fun read(text: CharSequence?, maximum: Int = 8192): BoundedText {
            require(maximum > 0)
            if (text == null) return BoundedText("", false)
            var end = minOf(text.length, maximum)
            // Preserve a complete UTF-16 character at the extraction boundary.
            if (end < text.length && end > 0 && text[end - 1].isHighSurrogate()) end--
            return BoundedText(text.subSequence(0, end).toString(), text.length > end)
        }
    }
}

/** Temporary diagnostic observations are separate from the future persistent user history. */
class ProbeBuffer(private val capacity: Int = 40) {
    init {
        require(capacity in 1..40)
    }

    private val records = ArrayDeque<ProbeRecord>()
    private var nextId = 0L
    var generation: Long = 0
        private set

    fun snapshot(): List<ProbeRecord> = records.toList()

    fun append(expectedGeneration: Long, observation: ProbeObservation): Boolean {
        if (expectedGeneration != generation) return false
        require(observation.body.length <= 8192 && observation.title.length <= 256)
        if (records.size == capacity) records.removeFirst()
        records.addLast(ProbeRecord(++nextId, observation))
        return true
    }

    fun clear() {
        generation++
        records.clear()
    }
}
