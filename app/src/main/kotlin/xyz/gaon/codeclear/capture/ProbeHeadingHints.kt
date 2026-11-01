package xyz.gaon.codeclear.capture

enum class ProbeHeadingKind {
    NATIONAL,
    EXTREME,
    SEVERE,
    AMBER,
    PUBLIC_SAFETY,
    TEST,
    OPERATOR_DEFINED,
    EARTHQUAKE,
    TSUNAMI,
    EARTHQUAKE_AND_TSUNAMI,
    OTHER_EMERGENCY,
    OTHER,
    UNKNOWN
}

/** Exact matches against reviewed source resources are hints, never authoritative alert levels. */
class ProbeHeadingIndex(labels: List<Pair<ProbeHeadingKind, String>>) {
    private val entries = labels.filter { it.second.isNotBlank() && it.second.length <= 256 }
        .groupBy({ it.second.trim() }, { it.first })
        .mapValues { it.value.toSet() }

    fun infer(title: String): ProbeHeadingKind {
        if (title.length > 256) return ProbeHeadingKind.UNKNOWN
        // Reviewed AOSP dialogs can append their source timestamp on a second line.
        return entries[title.substringBefore('\n').trim()]?.singleOrNull()
            ?: ProbeHeadingKind.UNKNOWN
    }
}

object ProbeDialogQueue {
    private val suffix = Regex("  \\(1/([0-9]{1,4})\\)$")

    /** Reviewed AOSP code appends ASCII counts independently of its localized button label. */
    fun reportedMultipleCount(button: String): Int? {
        if (button.length > 256) return null
        return suffix.find(button)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 2..9999 }
    }
}
