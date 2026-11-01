package xyz.gaon.codeclear.mobile

import java.util.Locale

object UiLanguages {
    val supported = listOf("en", "ko", "zh-Hans", "zh-Hant", "ja", "fr", "de")

    /** System preferences can include region variants or several tags; store one supported choice. */
    fun normalize(tags: String): String {
        if (tags.isBlank() || tags.length > 512) return ""
        val locale = Locale.forLanguageTag(tags.substringBefore(','))
        val candidate = if (locale.language == "zh") {
            when (locale.script) {
                "Hant" -> "zh-Hant"
                "Hans" -> "zh-Hans"
                else -> if (locale.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
            }
        } else {
            locale.language
        }
        return candidate.takeIf { it in supported }.orEmpty()
    }
}
