package xyz.gaon.codeclear.translation

import com.ibm.icu.text.Transliterator

/** Confine this instance to the translation worker; ICU transliterators are mutable. */
class ChineseScriptConverter {
    private val simplified by lazy { Transliterator.getInstance("Traditional-Simplified") }
    private val traditional by lazy { Transliterator.getInstance("Simplified-Traditional") }

    fun convert(text: String, target: TranslationTarget): String = when (target) {
        TranslationTarget.SIMPLIFIED_CHINESE -> simplified.transliterate(text)
        TranslationTarget.TRADITIONAL_CHINESE -> traditional.transliterate(text)
        else -> text
    }
}
