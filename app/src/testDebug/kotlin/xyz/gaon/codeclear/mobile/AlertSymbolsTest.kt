package xyz.gaon.codeclear.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.reception.CountryEvidence
import xyz.gaon.codeclear.reception.ReceptionCountry

class AlertSymbolsTest {
    private val original = CapturedAlert(
        "com.google.android.cellbroadcastreceiver",
        CaptureSource.NOTIFICATION,
        "Unrelated localized title",
        "[DRILL] Gate 3.",
        1_700_000_000_000
    )

    @Test
    fun structuredOfficialCategoriesOverrideConflictingTitleHints() {
        mapOf(
            4370 to AlertSymbolKind.CRITICAL,
            4383 to AlertSymbolKind.CRITICAL,
            4371 to AlertSymbolKind.URGENT,
            4384 to AlertSymbolKind.URGENT,
            4372 to AlertSymbolKind.SAFETY,
            4385 to AlertSymbolKind.SAFETY,
            4379 to AlertSymbolKind.MISSING,
            4392 to AlertSymbolKind.MISSING
        ).forEach { (category, kind) ->
            val symbol = alertSymbol(
                original.copy(
                    headingHint = "TEST",
                    context = ReceptionContext(ReceptionCountry("KR", CountryEvidence.CBS_PLMN)),
                    broadcast = BroadcastMetadata(1, 42, category, 3, 1, null)
                )
            )
            assertEquals(kind, symbol.kind)
            assertNotNull(symbol.official)
            assertNull(symbol.estimateLabel)
        }
    }

    @Test
    fun reviewedSourceHeadingHintsAreAlwaysEstimates() {
        mapOf(
            "NATIONAL" to AlertSymbolKind.CRITICAL,
            "EXTREME" to AlertSymbolKind.CRITICAL,
            "SEVERE" to AlertSymbolKind.URGENT,
            "PUBLIC_SAFETY" to AlertSymbolKind.SAFETY,
            "AMBER" to AlertSymbolKind.MISSING,
            "TEST" to AlertSymbolKind.TEST
        ).forEach { (hint, kind) ->
            val symbol = alertSymbol(original.copy(headingHint = hint))
            assertEquals(kind, symbol.kind)
            assertNull(symbol.official)
            assertNotNull(symbol.estimateLabel)
        }
        assertEquals(
            R.string.mobile_hint_national,
            alertSymbol(original.copy(headingHint = "NATIONAL")).estimateLabel
        )
    }

    @Test
    fun unknownAmbiguousAndHazardOnlyHintsDoNotInventALevel() {
        listOf(
            "UNKNOWN",
            "OTHER",
            "OPERATOR_DEFINED",
            "EARTHQUAKE",
            "TSUNAMI",
            "EARTHQUAKE_AND_TSUNAMI",
            "OTHER_EMERGENCY",
            "FUTURE_HINT"
        ).forEach { hint ->
            assertEquals(
                AlertSymbol(AlertSymbolKind.GENERAL),
                alertSymbol(original.copy(headingHint = hint))
            )
        }
    }

    @Test
    fun peersAndUnreviewedPackagesCannotSupplyTitleEstimates() {
        val claimed = original.copy(headingHint = "EXTREME")
        assertEquals(
            AlertSymbolKind.GENERAL,
            alertSymbol(claimed.copy(source = CaptureSource.PEER)).kind
        )
        assertEquals(
            AlertSymbolKind.GENERAL,
            alertSymbol(claimed.copy(sourcePackage = "unreviewed.receiver")).kind
        )
        assertEquals(
            AlertSymbolKind.GENERAL,
            alertSymbol(claimed.copy(sourcePackage = "com.android.shell")).kind
        )
    }

    @Test
    fun titlesAndRawCategoriesWithoutCountryEvidenceStayGeneric() {
        assertEquals(
            AlertSymbolKind.GENERAL,
            alertSymbol(original.copy(title = "Extreme emergency alert")).kind
        )
        assertEquals(AlertSymbolKind.GENERAL, alertSymbol(original.copy(title = "위급재난문자")).kind)
        assertEquals(
            AlertSymbolKind.GENERAL,
            alertSymbol(original.copy(broadcast = BroadcastMetadata(1, 1, 4370, 3, 0, null))).kind
        )
    }
}
