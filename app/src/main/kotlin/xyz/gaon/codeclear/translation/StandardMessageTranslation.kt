package xyz.gaon.codeclear.translation

/** Identifies application-owned wording without attributing it to the selected neural engine. */
data class StandardTranslationAttribution(val templateId: String, val catalogRevision: String) {
    init {
        require(templateId.matches(idPattern))
        require(catalogRevision.matches(revisionPattern))
    }

    private companion object {
        val idPattern = Regex("[a-z0-9][a-z0-9.-]{0,95}")
        val revisionPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}

data class StandardMessageTranslation(
    val text: String,
    val sourceLanguage: String,
    val attribution: StandardTranslationAttribution
)

enum class StandardSlotKind {
    TIME,
    DATE_TIME,
    MAGNITUDE,
    DISTANCE,
    INTENSITY,
    NUMBER,
    AREA,
    AGENCY,
    URL
}

/** Every variable must survive rendering; unknown names never become unrestricted text slots. */
data class StandardMessageTemplate(
    val id: String,
    val sourceLanguage: String,
    val jurisdiction: String,
    val sourceText: String,
    val targets: Map<String, String>,
    val slots: Map<String, StandardSlotKind> = emptyMap(),
    val preserveNames: Boolean = false,
    val referenceId: String
)

fun interface StandardNameResolver {
    /** Null means unlisted, ambiguous, or lacking a reviewed target name. */
    fun resolve(
        jurisdiction: String,
        kind: StandardSlotKind,
        original: String,
        target: String
    ): String?
}
