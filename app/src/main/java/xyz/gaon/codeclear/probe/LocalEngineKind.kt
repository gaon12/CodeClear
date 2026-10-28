package xyz.gaon.codeclear.probe

/** Stable identifiers are persisted with results so changing engines never relabels old output. */
enum class LocalEngineKind(val key: String) {
    ML_KIT("mlkit"),
    SYSTEM("system")
}
