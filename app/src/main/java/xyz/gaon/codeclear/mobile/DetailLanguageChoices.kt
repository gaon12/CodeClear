package xyz.gaon.codeclear.mobile

import java.util.Locale
import xyz.gaon.codeclear.probe.LocalTarget

internal fun originalLanguageCode(language: String?): String? = language
    ?.substringBefore('-')?.lowercase(Locale.ROOT)
    ?.takeIf { it != "und" && it.matches(Regex("[a-z]{2,3}")) }

internal fun canTranslateDetailTo(target: LocalTarget, originalLanguage: String?): Boolean {
    val original = originalLanguageCode(originalLanguage) ?: return false
    return target.modelCode != original
}
