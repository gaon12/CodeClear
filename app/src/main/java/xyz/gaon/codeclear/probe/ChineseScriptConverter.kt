package xyz.gaon.codeclear.probe

import com.ibm.icu.text.Transliterator

/** Confine this instance to the translation worker; ICU transliterators are mutable. */
class ChineseScriptConverter {
    private val simplified by lazy { Transliterator.getInstance("Traditional-Simplified") }
    private val traditional by lazy { Transliterator.getInstance("Simplified-Traditional") }

    fun convert(text: String, target: LocalTarget): String = when (target) {
        LocalTarget.SIMPLIFIED_CHINESE -> simplified.transliterate(text)
        LocalTarget.TRADITIONAL_CHINESE -> traditional.transliterate(text)
        else -> text
    }
}
