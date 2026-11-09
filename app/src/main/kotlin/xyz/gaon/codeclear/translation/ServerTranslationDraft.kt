package xyz.gaon.codeclear.translation

import xyz.gaon.codeclear.reception.normalizeCountry

/** Correlation and unverified issuer data; neither establishes source authenticity. */
data class ServerTranslationDraft(
    val originalDigest: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translatedText: String,
    val issuer: IssuerClaim? = null
) {
    init {
        require(originalDigest.matches(Regex("[0-9a-f]{64}")))
        require(validLanguage(sourceLanguage) && validLanguage(targetLanguage))
        require(sourceLanguage != targetLanguage)
        require(translatedText.isNotBlank() && translatedText.length <= 16_384)
    }

    /** Correlation must be checked before attaching a delayed response to a saved original. */
    fun matches(originalDigest: String, targetLanguage: String): Boolean =
        this.originalDigest == originalDigest && this.targetLanguage == targetLanguage
}

/** Even a structured claim needs independent source verification before it can be called official. */
enum class IssuerBasis { STRUCTURED_SOURCE_CLAIM, EXTRACTED_FROM_TEXT, TEXT_PATTERN_ESTIMATE }

data class IssuerClaim(
    val originalName: String,
    val translatedName: String?,
    val basis: IssuerBasis,
    val countryIso: String? = null,
    val sourceExcerpt: String? = null
) {
    init {
        require(originalName.isNotBlank() && originalName.length <= 256)
        require(
            translatedName == null || (translatedName.isNotBlank() && translatedName.length <= 256)
        )
        require(countryIso == null || normalizeCountry(countryIso) == countryIso)
        require(
            sourceExcerpt == null || (
                sourceExcerpt.isNotBlank() && sourceExcerpt.length <= 2048 &&
                    sourceExcerpt.contains(originalName)
                )
        )
    }
}

private fun validLanguage(tag: String): Boolean =
    tag.length in 2..35 && tag.matches(Regex("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*"))
