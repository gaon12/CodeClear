package xyz.gaon.codeclear.reception

import java.util.Locale

enum class CountryEvidence { CBS_PLMN, NETWORK_SNAPSHOT, PEER }

data class ReceptionCountry(val iso: String, val evidence: CountryEvidence) {
    init {
        require(iso == normalizeCountry(iso))
    }

    val flag: String get() = buildString {
        iso.forEach { appendCodePoint(0x1F1E6 + it.code - 'A'.code) }
    }

    fun displayName(languageTag: String): String = Locale.forLanguageTag("und-$iso")
        .getDisplayCountry(Locale.forLanguageTag(languageTag))
}

private val countryCodes = Locale.getISOCountries().toSet() + "XK"
fun normalizeCountry(iso: String?): String? = iso?.uppercase(Locale.ROOT)?.takeIf {
    it in
        countryCodes
}
