package xyz.gaon.codeclear.mobile

import xyz.gaon.codeclear.probe.LocalEngineKind

enum class CaptureSource { NOTIFICATION, DIALOG, ORIGINAL_NOTIFICATION }

data class BroadcastMetadata(
    val format: Int,
    val serial: Int,
    val category: Int,
    val priority: Int,
    val cmasClass: Int?,
    val etwsType: Int?
) {
    init {
        require(format in 1..2 && serial in 0..65535 && category in 0..65535 && priority in 0..3)
        require(cmasClass == null || cmasClass in -1..8)
        require(etwsType == null || etwsType in -1..4)
    }
}

/** Only the capture boundary can establish source provenance; the database does not authenticate it. */
data class CapturedAlert(
    val sourcePackage: String,
    val source: CaptureSource,
    val title: String,
    val original: String,
    val capturedAtMillis: Long,
    val postedAtMillis: Long? = null,
    val receivedAtMillis: Long? = null,
    val sourceIdentity: String? = null,
    val headingHint: String = "UNKNOWN",
    val broadcast: BroadcastMetadata? = null
) {
    init {
        require(sourcePackage.length <= 256 && sourcePackage.matches(PACKAGE))
        require(title.length <= 256 && original.isNotBlank() && original.length <= 8192)
        require(capturedAtMillis > 0)
        require(postedAtMillis == null || postedAtMillis > 0)
        require(receivedAtMillis == null || receivedAtMillis > 0)
        require(sourceIdentity == null || sourceIdentity.length in 1..512)
        require(headingHint.matches(Regex("[A-Z_]{1,40}")))
    }

    private companion object {
        val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}

enum class TranslationPhase { QUEUED, WORKING, READY, NEEDS_MODELS, UNKNOWN_LANGUAGE, FAILED }

data class SavedTranslation(
    val phase: TranslationPhase,
    val text: String? = null,
    val sourceLanguage: String? = null,
    val revision: Long = 0,
    val engine: LocalEngineKind = LocalEngineKind.ML_KIT,
    val sameLanguage: Boolean = false
)

data class SavedAlert(val id: Long, val capture: CapturedAlert, val translation: SavedTranslation?)

data class TranslationTicket(
    val alertId: Long,
    val target: String,
    val revision: Long,
    val original: String,
    val engine: LocalEngineKind = LocalEngineKind.ML_KIT
)

object HistoryText {
    val targets = setOf("ko", "zh-Hans", "zh-Hant", "ja", "en", "fr", "de")

    fun searchable(text: String): String = java.text.Normalizer
        .normalize(text.replace('\u0000', ' '), java.text.Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT)

    fun literalLike(query: String): String = "%" + searchable(query)
        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
