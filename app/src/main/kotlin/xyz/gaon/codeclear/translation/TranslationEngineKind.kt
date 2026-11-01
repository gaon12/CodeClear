package xyz.gaon.codeclear.translation

/** Stable identifiers are persisted with results so changing engines never relabels old output. */
enum class TranslationEngineKind(val key: String) {
    ML_KIT("mlkit"),
    SYSTEM("system"),
    MY_MEMORY("mymemory")
}
