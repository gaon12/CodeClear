package xyz.gaon.codeclear.mobile

import java.util.Locale
import xyz.gaon.codeclear.translation.TranslationTarget

data class ModelDownloadRequest(
    val languages: Set<String>,
    val body: String? = null,
    val target: TranslationTarget? = null,
    val sourceKnown: Boolean = true,
    val checkUpdates: Boolean = false,
    val started: Boolean = false,
    val visible: Boolean = true
) {
    init {
        require(languages.size <= 2 && languages.all { it in managedModelLanguages })
        require((body == null) == (target == null))
        require(body == null || (body.isNotBlank() && body.length <= 8192))
    }
}

internal fun modelLanguageName(language: String, locale: Locale): String = when {
    language == "zh" -> "简体中文 / 繁體中文"
    language in primaryModelLanguages -> languageName(language)
    else -> Locale.forLanguageTag(language).getDisplayLanguage(locale)
}
