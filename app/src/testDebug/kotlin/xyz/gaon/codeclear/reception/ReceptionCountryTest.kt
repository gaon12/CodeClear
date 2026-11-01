package xyz.gaon.codeclear.reception

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceptionCountryTest {
    @Test
    fun plmnCountryUsesTheMessageNetworkInsteadOfTheDisplayLanguage() {
        assertEquals("KR", MccCountries.fromPlmn("45008"))
        assertEquals("US", MccCountries.fromPlmn("310260"))
        assertEquals("JP", MccCountries.fromPlmn("44010"))
        assertNull(MccCountries.fromPlmn("00101"))
        assertNull(MccCountries.fromPlmn("450"))
        assertNull(MccCountries.fromPlmn("45008extra"))
        assertNull(MccCountries.fromPlmn(null))
    }

    @Test
    fun countryFlagsAndNamesUseStoredIsoWithTheRequestedDisplayLocale() {
        val country = ReceptionCountry("KR", CountryEvidence.CBS_PLMN)
        assertEquals("🇰🇷", country.flag)
        assertNotEquals(country.displayName("ko"), country.displayName("de"))
        assertEquals("KR", normalizeCountry("kr"))
        assertNull(normalizeCountry("ZZ"))
        assertNull(normalizeCountry("KOR"))
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("IN", normalizeCountry("in"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
