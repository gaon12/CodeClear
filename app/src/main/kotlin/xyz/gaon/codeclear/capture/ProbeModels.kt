package xyz.gaon.codeclear.capture

import xyz.gaon.codeclear.translation.TranslationInput
import xyz.gaon.codeclear.translation.TranslationTarget
enum class ProbeRoute { NOTIFICATION, ACCESSIBILITY }

enum class ProbeOrigin { SYSTEM_CANDIDATE, SHELL_FIXTURE }

enum class ProbeRadio { PRESENT, ABSENT, UNKNOWN }

enum class ProbeBodyEvidence {
    VISIBLE_DIALOG_FIELD,
    EXPANDED_NOTIFICATION_FIELD,
    SHELL_FIXTURE_FIELD,
    SOURCE_NOTIFICATION_PAYLOAD,
    UNVERIFIED
}

enum class ProbeNotificationChannel {
    EMERGENCY_PRESENTATION,
    HIGH_PRIORITY_PRESENTATION,
    NON_EMERGENCY_PRESENTATION,
    VOICE_CALL_PRESENTATION,
    UNKNOWN
}

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
    val platformBoundaryPossible: Boolean = false,
    val windowClass: String? = null,
    val readDurationMillis: Long? = null,
    val bodyEvidence: ProbeBodyEvidence = ProbeBodyEvidence.UNVERIFIED,
    val notificationChannel: ProbeNotificationChannel = ProbeNotificationChannel.UNKNOWN,
    val headingHint: ProbeHeadingKind = ProbeHeadingKind.UNKNOWN,
    val reportedDialogQueueSize: Int? = null,
    val broadcastFields: ProbeBroadcastFields? = null,
    val sourceObservationKey: String? = null,
    val sourceWindowId: Int? = null,
    val sourceDisplayId: Int? = null
)

data class ProbeRecord(val id: Long, val observation: ProbeObservation)

fun ProbeRecord.localInput(target: TranslationTarget): TranslationInput? = observation.takeIf {
    it.bodyEvidence != ProbeBodyEvidence.UNVERIFIED && it.body.isNotBlank() &&
        !it.groupSummary && !it.limited
}?.let { TranslationInput(id, it.body, target) }

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

/** Temporary diagnostic observations remain separate from persistent product history. */
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

    fun annotateHeading(expectedGeneration: Long, recordId: Long, hint: ProbeHeadingKind): Boolean {
        if (expectedGeneration != generation) return false
        val index = records.indexOfFirst { it.id == recordId }
        if (index < 0) return false
        val record = records[index]
        if (record.observation.headingHint == hint) return false
        records[index] = record.copy(observation = record.observation.copy(headingHint = hint))
        return true
    }
}
