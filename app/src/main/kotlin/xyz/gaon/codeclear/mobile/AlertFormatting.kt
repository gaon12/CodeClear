package xyz.gaon.codeclear.mobile

import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.TranslationEngineKind

fun languageName(tag: String): String = when (tag) {
    "ko" -> "한국어"
    "zh-Hans" -> "简体中文"
    "zh-Hant" -> "繁體中文"
    "ja" -> "日本語"
    "fr" -> "Français"
    "de" -> "Deutsch"
    else -> "English"
}

internal fun translationStatusLabel(
    phase: TranslationPhase?,
    consent: Boolean,
    engine: TranslationEngineKind
): Int = if (!consent) {
    R.string.mobile_translation_off
} else {
    when (phase) {
        null -> R.string.mobile_not_translated

        TranslationPhase.QUEUED -> R.string.mobile_queued

        TranslationPhase.WORKING -> R.string.mobile_working

        TranslationPhase.NEEDS_MODELS -> if (engine == TranslationEngineKind.SYSTEM) {
            R.string.mobile_system_models_missing
        } else {
            R.string.mobile_models_missing
        }

        TranslationPhase.UNKNOWN_LANGUAGE -> R.string.mobile_unknown_language

        TranslationPhase.UNAVAILABLE -> if (engine == TranslationEngineKind.OWNED_SERVER) {
            R.string.mobile_owned_unavailable
        } else if (engine == TranslationEngineKind.MY_MEMORY) {
            R.string.mobile_remote_unavailable
        } else {
            R.string.mobile_system_unavailable
        }

        TranslationPhase.UNSUPPORTED_PAIR -> R.string.mobile_pair_unsupported

        TranslationPhase.QUOTA -> if (engine == TranslationEngineKind.OWNED_SERVER) {
            R.string.mobile_owned_quota
        } else {
            R.string.mobile_remote_quota
        }

        TranslationPhase.TOO_LONG -> if (engine == TranslationEngineKind.OWNED_SERVER) {
            R.string.mobile_owned_too_long
        } else {
            R.string.mobile_remote_too_long
        }

        TranslationPhase.FAILED -> R.string.mobile_failed

        TranslationPhase.READY -> R.string.mobile_failed
    }
}
