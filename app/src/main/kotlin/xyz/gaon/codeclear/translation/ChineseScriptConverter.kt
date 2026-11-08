package xyz.gaon.codeclear.translation

import android.icu.text.Transliterator

/**
 * Converts between Simplified and Traditional Chinese with the platform ICU transliterators
 * (public since API 29), so the app does not bundle a separate ICU library and its data.
 * Confine this instance to the translation worker; ICU transliterators are mutable.
 */
class ChineseScriptConverter {
    private val simplified by lazy { Transliterator.getInstance("Traditional-Simplified") }
    private val traditional by lazy { Transliterator.getInstance("Simplified-Traditional") }

    fun convert(text: String, target: TranslationTarget): String = when (target) {
        TranslationTarget.SIMPLIFIED_CHINESE -> simplified.transliterate(text)
        TranslationTarget.TRADITIONAL_CHINESE -> traditional.transliterate(text)
        else -> text
    }
}
