package xyz.gaon.codeclear.mobile

import java.util.Locale
import xyz.gaon.codeclear.translation.TranslationTarget

// Compiled once: detail screens evaluate this on recomposition.
private val primaryLanguageCode = Regex("[a-z]{2,3}")

internal fun originalLanguageCode(language: String?): String? = language
    ?.substringBefore('-')?.lowercase(Locale.ROOT)
    ?.takeIf { it != "und" && it.matches(primaryLanguageCode) }

internal fun canTranslateDetailTo(target: TranslationTarget, originalLanguage: String?): Boolean {
    val original = originalLanguageCode(originalLanguage) ?: return false
    return target.modelCode != original
}
