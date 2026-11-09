package xyz.gaon.codeclear.translation

private val bracketedIssuer = Regex("\\[([^\\[\\]\\r\\n]{1,256})]")

/** Correlation and literal evidence constrain a claim; they cannot authenticate the named sender. */
internal fun evidencedIssuer(
    original: String,
    name: String,
    translated: String?,
    excerpt: String,
    basis: IssuerBasis
): IssuerClaim? = runCatching {
    require(name.length in 1..256 && (translated == null || translated.length in 1..256))
    require(excerpt.length in 1..2048)
    require(original.length <= 8192 && excerpt.isNotBlank() && original.contains(excerpt))
    require(basis in setOf(IssuerBasis.EXTRACTED_FROM_TEXT, IssuerBasis.TEXT_PATTERN_ESTIMATE))
    require(
        name.none(::unsafeIssuerCharacter) && translated?.none(::unsafeIssuerCharacter) != false
    )
    require(excerpt.none { unsafeIssuerCharacter(it) && it !in "\n\r\t" })
    require(Charsets.UTF_8.newEncoder().canEncode(name + translated.orEmpty() + excerpt))
    IssuerClaim(name, translated, basis, sourceExcerpt = excerpt)
}.getOrNull()

/** Only reviewed explicit boundary labels are estimated; locations and unknown names stay unknown. */
internal fun estimateIssuer(original: String): IssuerClaim? {
    if (original.isBlank() || original.length > 8192 ||
        original.any { unsafeIssuerCharacter(it) && it !in "\n\r\t" }
    ) {
        return null
    }
    val text = original.trim()
    val matches = bracketedIssuer.findAll(text).take(33).toList()
    if (matches.size > 32) return null
    val reviewed = matches.filter {
        it.groupValues[1].trim() in
            StandardTranslationCatalog.issuerLabels
    }
    if (reviewed.map { it.groupValues[1].trim() }.distinct().size != 1) return null
    val boundary = reviewed.firstOrNull { it.range.first == 0 || it.range.last == text.lastIndex }
        ?: return null
    return evidencedIssuer(
        original,
        boundary.groupValues[1].trim(),
        null,
        boundary.value,
        IssuerBasis.TEXT_PATTERN_ESTIMATE
    )
}

private fun unsafeIssuerCharacter(value: Char): Boolean =
    value.isISOControl() || value.code in 0x202a..0x202e || value.code in 0x2066..0x2069
