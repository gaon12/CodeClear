package xyz.gaon.codeclear.translation

private val bracketedIssuer = Regex(
    "\\[([^\\[\\]\\r\\n]{1,128})]|［([^［］\\r\\n]{1,128})］|" +
        "【([^【】\\r\\n]{1,128})】|「([^「」\\r\\n]{1,128})」|" +
        "〔([^〔〕\\r\\n]{1,128})〕|\\(([^()\\r\\n]{1,128})\\)|" +
        "（([^（）\\r\\n]{1,128})）"
)
private val labelledIssuer = Regex(
    "^[ \\t]*(?:발신[ \\t]*(?:기관|주체|자)?|발송[ \\t]*기관|보낸[ \\t]*기관|" +
        "발령[ \\t]*기관|sender|issued by|issuing (?:agency|authority)|from|" +
        "発信元|配信元|送信元|發布單位|发布单位|發布機關|发布机关|" +
        "發送單位|发送单位|émetteur|envoyé par|absender|herausgeber)" +
        "[ \\t]*[:：][ \\t]*([^\\r\\n]{1,256})$",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
)
private val explicitIssuerName = Regex("[\\p{L}\\p{M}\\p{N} .,'’&·()\\-]{2,128}")
private val koreanOrganization = Regex(
    "(?:[가-힣]{1,24}(?:[ ·][가-힣]{1,24}){0,3}(?:시청|군청|구청|도청|시|군|구|도)|" +
        "(?:[가-힣]{1,24}[ ·]){0,3}[가-힣]{0,24}(?:경찰청|경찰서|소방청|소방서|교육청|기상청|기상대|산림청|" +
        "질병관리청|행정안전부|국토교통부|보건복지부|국방부|환경부|" +
        "재난안전대책본부|재난안전본부|소방재난본부|소방본부|홍수통제소|공사|공단))"
)
private val eastAsianOrganization = Regex(
    "[一-龯々ぁ-ゖァ-ヺー]{1,32}(?:都|道|府|県|縣|市|区|區|町|村|役所|役場|" +
        "政府|氣象署|气象局|気象庁|気象台|消防局|消防署|警察局|警察署|" +
        "應變中心|应急管理局)"
)
private val englishOrganizationName = Regex("[A-Za-z][A-Za-z .,'’&()\\-]{1,127}")
private val englishOrganizationWord = Regex(
    "\\b(?:agency|government|council|police|fire department|weather service|" +
        "emergency management|bureau|ministry)\\b|^(?:city|county|municipality) of\\b",
    RegexOption.IGNORE_CASE
)

private data class IssuerCandidate(val name: String, val excerpt: String, val boundary: Boolean)

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

/** Bounded source patterns identify fallible claims without model work or changing stored alerts. */
internal fun estimateIssuer(original: String): IssuerClaim? {
    if (original.isBlank() || original.length > 8192 ||
        original.any { unsafeIssuerCharacter(it) && it !in "\n\r\t" }
    ) {
        return null
    }
    val text = original.trim()
    val matches = bracketedIssuer.findAll(text).take(33).toList()
    if (matches.size > 32) return null
    val labels = labelledIssuer.findAll(text).take(33).toList()
    if (matches.size + labels.size > 32) return null
    var prefixEnd = 0
    for (match in matches) {
        if (text.substring(prefixEnd, match.range.first).isNotBlank()) break
        prefixEnd = match.range.last + 1
    }
    var suffixStart = text.length
    for (match in matches.asReversed()) {
        if (text.substring(match.range.last + 1, suffixStart).isNotBlank()) break
        suffixStart = match.range.first
    }
    val candidates = matches.mapNotNull { match ->
        val name = bracketName(match)
        if (!organizationName(name)) return@mapNotNull null
        IssuerCandidate(
            name,
            match.value,
            match.range.last < prefixEnd || match.range.first >= suffixStart
        )
    } + labels.mapNotNull { match ->
        val value = match.groupValues[1].trim()
        val name = bracketedIssuer.matchEntire(value)?.let(::bracketName) ?: value
        if (!explicitIssuerName.matches(name) || name.none(Char::isLetter)) {
            return@mapNotNull null
        }
        IssuerCandidate(name, match.value, true)
    }
    if (candidates.map { it.name.lowercase() }.distinct().size != 1) return null
    val candidate = candidates.firstOrNull { it.boundary } ?: return null
    return evidencedIssuer(
        original,
        candidate.name,
        null,
        candidate.excerpt,
        IssuerBasis.TEXT_PATTERN_ESTIMATE
    )
}

private fun bracketName(match: MatchResult): String =
    match.groupValues.drop(1).first { it.isNotEmpty() }.trim()

private fun organizationName(name: String): Boolean =
    StandardTranslationCatalog.issuerLabels.any { it.equals(name, ignoreCase = true) } ||
        koreanOrganization.matches(name) || eastAsianOrganization.matches(name) ||
        (englishOrganizationName.matches(name) && englishOrganizationWord.containsMatchIn(name))

private fun unsafeIssuerCharacter(value: Char): Boolean =
    value.isISOControl() || value.code in 0x202a..0x202e || value.code in 0x2066..0x2069
